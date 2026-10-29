using System.Runtime.InteropServices;
using System.Runtime.Versioning;

namespace EmployeeMonitor.Sessions;

/// <summary>
/// A persistent, always-on-top, non-interactive banner shown on the employee's desktop for the whole time an
/// administrator is remotely controlling their screen. It exists so control is never silent: it is shown on both
/// the consent and the admin-configured unattended path, and removed the moment control ends or the session drops.
/// The banner runs its own message loop on a dedicated STA thread so it works from the windowless agent process.
/// </summary>
[SupportedOSPlatform("windows")]
internal sealed class ControlBanner
{
    private readonly object _gate = new();
    private Thread? _thread;
    private BannerForm? _form;
    private volatile bool _hideRequested;
    private string _text = "";

    public void Show(string adminName)
    {
        string who = string.IsNullOrWhiteSpace(adminName) ? "IT support" : adminName;
        string text = "●  Your screen is being remotely controlled by " + who;
        lock (_gate)
        {
            _text = text;
            if (_thread is { IsAlive: true })
            {
                _form?.UpdateText(text);
                return;
            }
            _hideRequested = false;
            _thread = new Thread(RunLoop) { IsBackground = true, Name = "EM-ControlBanner" };
            _thread.SetApartmentState(ApartmentState.STA);
            _thread.Start();
        }
    }

    public void Hide()
    {
        lock (_gate)
        {
            _hideRequested = true;
            var form = _form;
            if (form is not null)
            {
                try { form.BeginInvoke(new Action(form.Close)); } catch { /* already gone */ }
            }
            _thread = null;
        }
    }

    private void RunLoop()
    {
        try
        {
            using var form = new BannerForm(_text);
            _form = form;
            if (_hideRequested)
            {
                return;   // Hide() won the race before the window came up
            }
            System.Windows.Forms.Application.Run(form);
        }
        catch
        {
            // best-effort: a missing desktop (e.g. Session 0) just means no banner, never a crash
        }
        finally
        {
            _form = null;
        }
    }

    /// <summary>Borderless, top-most, non-activating strip pinned to the top-centre of the primary screen.</summary>
    private sealed class BannerForm : System.Windows.Forms.Form
    {
        private readonly System.Windows.Forms.Label _label;
        private readonly System.Windows.Forms.Timer _topMostTimer;

        public BannerForm(string text)
        {
            FormBorderStyle = System.Windows.Forms.FormBorderStyle.None;
            StartPosition = System.Windows.Forms.FormStartPosition.Manual;
            ShowInTaskbar = false;
            TopMost = true;
            BackColor = System.Drawing.Color.FromArgb(200, 30, 30);
            Size = new System.Drawing.Size(560, 34);
            MinimizeBox = false;
            MaximizeBox = false;
            ControlBox = false;

            _label = new System.Windows.Forms.Label
            {
                Dock = System.Windows.Forms.DockStyle.Fill,
                TextAlign = System.Drawing.ContentAlignment.MiddleCenter,
                ForeColor = System.Drawing.Color.White,
                Font = new System.Drawing.Font("Segoe UI", 10f, System.Drawing.FontStyle.Bold),
                Text = text,
            };
            Controls.Add(_label);

            Reposition();

            // Re-assert top-most periodically so the banner stays above apps that go full-screen after it appears.
            _topMostTimer = new System.Windows.Forms.Timer { Interval = 1000 };
            _topMostTimer.Tick += (_, _) => KeepOnTop();
            _topMostTimer.Start();
        }

        public void UpdateText(string text)
        {
            try { BeginInvoke(new Action(() => _label.Text = text)); } catch { }
        }

        private void Reposition()
        {
            var screen = System.Windows.Forms.Screen.PrimaryScreen?.Bounds
                         ?? new System.Drawing.Rectangle(0, 0, 1920, 1080);
            Location = new System.Drawing.Point(screen.Left + (screen.Width - Width) / 2, screen.Top + 8);
        }

        private void KeepOnTop()
        {
            try
            {
                if (IsHandleCreated)
                {
                    SetWindowPos(Handle, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
                }
            }
            catch { }
        }

        // Never take focus when shown.
        protected override bool ShowWithoutActivation => true;

        protected override System.Windows.Forms.CreateParams CreateParams
        {
            get
            {
                var cp = base.CreateParams;
                cp.ExStyle |= WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW | WS_EX_TOPMOST;
                return cp;
            }
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing)
            {
                _topMostTimer.Stop();
                _topMostTimer.Dispose();
            }
            base.Dispose(disposing);
        }

        private const int WS_EX_TOPMOST = 0x00000008;
        private const int WS_EX_TOOLWINDOW = 0x00000080;
        private const int WS_EX_NOACTIVATE = 0x08000000;
        private const uint SWP_NOSIZE = 0x0001;
        private const uint SWP_NOMOVE = 0x0002;
        private const uint SWP_NOACTIVATE = 0x0010;
        private static readonly IntPtr HWND_TOPMOST = new(-1);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool SetWindowPos(IntPtr hWnd, IntPtr hWndInsertAfter, int x, int y, int cx, int cy, uint uFlags);
    }
}
