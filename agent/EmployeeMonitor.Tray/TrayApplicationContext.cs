using System.Drawing.Drawing2D;
using System.Runtime.Versioning;
using EmployeeMonitor.Agent.Api;
using EmployeeMonitor.Agent.Configuration;
using EmployeeMonitor.Sessions;
using Microsoft.Extensions.Logging.Abstractions;

namespace EmployeeMonitor.Tray;

/// <summary>
/// The visible "Company Monitoring Active" tray indicator. It makes monitoring transparent: the icon is always
/// shown and the menu reveals exactly which categories the current policy enables. It only reads the device's
/// own identity and policy (no personal data) via the agent endpoints.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class TrayApplicationContext : ApplicationContext
{
    private readonly NotifyIcon _icon;
    private readonly BackendClient _api;
    private readonly CredentialStore _credentials;
    private readonly Icon _activeIcon;

    public TrayApplicationContext(string baseUrl)
    {
        var http = new HttpClient { BaseAddress = new Uri(baseUrl), Timeout = TimeSpan.FromSeconds(15) };
        _api = new BackendClient(http, NullLogger<BackendClient>.Instance);

        var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "EmployeeMonitor");
        _credentials = new CredentialStore(new DpapiSecureStore(dir, NullLogger<DpapiSecureStore>.Instance),
            NullLogger<CredentialStore>.Instance);

        _activeIcon = BuildDotIcon(Color.LimeGreen);

        var menu = new ContextMenuStrip();
        menu.Items.Add("Company Monitoring", null, (_, _) => ShowStatus()).Enabled = true;
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("What is monitored…", null, (_, _) => ShowStatus());
        menu.Items.Add("Refresh", null, async (_, _) => await RefreshTooltip());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Exit", null, (_, _) => ExitThread());

        _icon = new NotifyIcon
        {
            Icon = _activeIcon,
            Visible = true,
            Text = "Company Monitoring Active",
            ContextMenuStrip = menu,
        };
        _icon.DoubleClick += (_, _) => ShowStatus();

        _ = RefreshTooltip();
    }

    private async Task RefreshTooltip()
    {
        var cred = _credentials.Load();
        if (cred is null)
        {
            // NotifyIcon.Text is capped at 63 chars.
            _icon.Text = "Company Monitoring (not enrolled)";
            return;
        }
        try
        {
            var me = await _api.GetIdentityAsync(cred.DeviceToken, CancellationToken.None);
            _icon.Text = Truncate($"Company Monitoring Active — {me.DeviceCode}", 63);
        }
        catch (Exception)
        {
            _icon.Text = "Company Monitoring Active";
        }
    }

    private async void ShowStatus()
    {
        var cred = _credentials.Load();
        if (cred is null)
        {
            MessageBox.Show("This device is not enrolled yet.", "Company Monitoring",
                MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        try
        {
            var me = await _api.GetIdentityAsync(cred.DeviceToken, CancellationToken.None);
            var policy = await _api.GetPolicyAsync(cred.DeviceToken, CancellationToken.None);
            bool liveActive = false;
            try
            {
                liveActive = (await _api.GetLiveViewStatusAsync(cred.DeviceToken, CancellationToken.None)).Active;
            }
            catch (Exception)
            {
                // status is best-effort
            }
            ShowStatusForm(me, policy.Settings, liveActive);
        }
        catch (Exception ex)
        {
            MessageBox.Show("Could not contact the monitoring server.\n\n" + ex.Message, "Company Monitoring",
                MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }

    [SupportedOSPlatform("windows")]
    private void ShowStatusForm(AgentIdentityResponse me, PolicySettings p, bool liveActive)
    {
        string Line(bool on, string label) => (on ? "✓  " : "✗  ") + label;

        var lines = new List<string>
        {
            $"Device:   {me.DeviceCode}  ({me.Hostname})",
            me.Employee is null ? "Employee: (unassigned)" : $"Employee: {me.Employee.FullName}",
            "",
            "Status:   Active",
            "",
            "This device is monitored for:",
            Line(p.SessionMonitoring, "Work session (login / logout, active / idle)"),
            Line(p.ApplicationMonitoring, "Foreground application usage"),
            Line(p.WebsiteMonitoring, "Website / domain usage"),
            Line(p.UsbMonitoring, "USB device events"),
            Line(p.NetworkMonitoring, "Network status"),
            Line(p.ZscalerMonitoring, "Zscaler status"),
            Line(p.Screenshots, $"Periodic screenshots (every {p.ScreenshotIntervalSeconds / 60} min)"),
            "",
            liveActive
                ? "Live screen:   ● ACTIVE — an authorized administrator is viewing this screen now"
                : $"Live screen:   {(p.LiveView ? "May be viewed by an authorized administrator" : "Disabled")}",
            $"Working hours: {p.MonitoringStart}–{p.MonitoringEnd} ({p.Timezone})",
            "",
            "Monitoring never captures keystrokes, passwords, private messages,",
            "webcam or microphone. Contact IT with any questions.",
        };

        using var form = new Form
        {
            Text = "Company Monitoring",
            FormBorderStyle = FormBorderStyle.FixedDialog,
            StartPosition = FormStartPosition.CenterScreen,
            MinimizeBox = false,
            MaximizeBox = false,
            ClientSize = new Size(460, 430),
            ShowInTaskbar = true,
        };
        var label = new Label
        {
            AutoSize = false,
            Dock = DockStyle.Fill,
            Padding = new Padding(16),
            Font = new Font("Segoe UI", 9.5f),
            Text = string.Join(Environment.NewLine, lines),
        };
        var ok = new Button { Text = "Close", Dock = DockStyle.Bottom, Height = 36, DialogResult = DialogResult.OK };
        form.Controls.Add(label);
        form.Controls.Add(ok);
        form.AcceptButton = ok;
        form.ShowDialog();
    }

    [SupportedOSPlatform("windows")]
    private static Icon BuildDotIcon(Color color)
    {
        using var bmp = new Bitmap(16, 16);
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Color.Transparent);
            using var brush = new SolidBrush(color);
            g.FillEllipse(brush, 2, 2, 12, 12);
            using var pen = new Pen(Color.FromArgb(40, 0, 0, 0));
            g.DrawEllipse(pen, 2, 2, 12, 12);
        }
        return Icon.FromHandle(bmp.GetHicon());
    }

    private static string Truncate(string value, int max) =>
        value.Length <= max ? value : value[..max];

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _icon.Visible = false;
            _icon.Dispose();
            _activeIcon.Dispose();
        }
        base.Dispose(disposing);
    }
}
