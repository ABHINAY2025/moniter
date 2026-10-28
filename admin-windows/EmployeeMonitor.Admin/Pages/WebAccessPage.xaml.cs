using EmployeeMonitor.Admin.Core.Api;
using EmployeeMonitor.Admin.Core.ViewModels;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;

namespace EmployeeMonitor.Admin.Pages;

public sealed partial class WebAccessPage : Page
{
    public WebAccessViewModel ViewModel { get; }

    public WebAccessPage()
    {
        ViewModel = App.GetService<WebAccessViewModel>();
        InitializeComponent();
    }

    protected override async void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        await ViewModel.LoadPoliciesAsync();
        await ViewModel.LoadActivityAsync();
    }

    // ---------- Create / edit ----------

    private async void OnNewPolicy(object sender, RoutedEventArgs e) => await ShowPolicyEditorAsync(null);

    private async void OnEditPolicy(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: WebPolicyRowViewModel row })
        {
            await ShowPolicyEditorAsync(row);
        }
    }

    private async Task ShowPolicyEditorAsync(WebPolicyRowViewModel? existing)
    {
        var name = new TextBox { Header = "Name", Text = existing?.Name ?? "", PlaceholderText = "e.g. No streaming" };
        var description = new TextBox { Header = "Description (optional)", Text = existing?.Description ?? "" };
        var priority = new NumberBox
        {
            Header = "Priority (higher wins)",
            Value = existing?.Priority ?? 100,
            Minimum = 1,
            Maximum = 1000,
            SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Inline,
        };
        var enabled = new ToggleSwitch { Header = "Enabled", IsOn = existing?.Enabled ?? true };
        var over = new CheckBox { Content = "Allow blocking essential/critical domains (not recommended)" };

        var block = DomainBox("Block these domains (one per line)", existing?.BlockDomains);
        var allow = DomainBox("Always allow (exceptions, one per line)", existing?.AllowDomains);
        var monitor = DomainBox("Monitor only (one per line)", existing?.MonitorDomains);

        var error = new InfoBar
        {
            Severity = InfoBarSeverity.Error,
            IsOpen = false,
            IsClosable = false,
        };

        var content = new StackPanel { Spacing = 12, Width = 460 };
        content.Children.Add(error);
        content.Children.Add(name);
        content.Children.Add(description);
        content.Children.Add(priority);
        content.Children.Add(enabled);
        content.Children.Add(block);
        content.Children.Add(allow);
        content.Children.Add(monitor);
        if (existing?.CategoryNote is { } note)
        {
            content.Children.Add(new InfoBar
            {
                Severity = InfoBarSeverity.Informational,
                IsOpen = true,
                IsClosable = false,
                Message = note,
            });
        }
        content.Children.Add(over);
        content.Children.Add(new TextBlock
        {
            TextWrapping = TextWrapping.Wrap,
            FontSize = 12,
            Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"],
            Text = "Blocking uses the browsers' own policy (Chrome/Edge). Domain-level only — no page contents, "
                 + "no HTTPS interception.",
        });

        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = existing is null ? "New web policy" : "Edit web policy",
            Content = new ScrollViewer { Content = content, VerticalScrollBarVisibility = ScrollBarVisibility.Auto },
            PrimaryButtonText = existing is null ? "Create" : "Save",
            CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Primary,
        };

        dialog.PrimaryButtonClick += async (_, args) =>
        {
            var deferral = args.GetDeferral();
            try
            {
                if (string.IsNullOrWhiteSpace(name.Text))
                {
                    args.Cancel = true;
                    Fail(error, "A name is required.");
                    return;
                }
                int prio = double.IsNaN(priority.Value) ? 100 : (int)Math.Clamp(priority.Value, 1, 1000);
                var rules = new List<WebRuleDto>();
                rules.AddRange(Rules(block, "BLOCK"));
                rules.AddRange(Rules(allow, "ALLOW"));
                rules.AddRange(Rules(monitor, "MONITOR"));
                // Preserve any category rules verbatim; the domain editor must never silently drop them.
                if (existing is not null)
                {
                    rules.AddRange(existing.CategoryRules);
                }

                var input = new WebPolicyInput(name.Text.Trim(),
                    string.IsNullOrWhiteSpace(description.Text) ? null : description.Text.Trim(),
                    enabled.IsOn, prio, over.IsChecked == true, rules);

                bool ok = await ViewModel.SavePolicyAsync(existing?.Id, input);
                if (!ok)
                {
                    args.Cancel = true;
                    Fail(error, ViewModel.Error ?? "Could not save the policy.");
                }
            }
            finally
            {
                deferral.Complete();
            }
        };

        await dialog.ShowAsync();
        await ViewModel.LoadActivityAsync();
    }

    private static TextBox DomainBox(string header, IReadOnlyList<string>? domains) => new()
    {
        Header = header,
        AcceptsReturn = true,
        TextWrapping = TextWrapping.Wrap,
        Height = 86,
        Text = domains is null ? "" : string.Join(Environment.NewLine, domains),
    };

    private static IEnumerable<WebRuleDto> Rules(TextBox box, string action) =>
        box.Text.Split('\n')
            .Select(l => l.Trim())
            .Where(l => l.Length > 0)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .Select(d => new WebRuleDto(null, d, null, action));

    private static void Fail(InfoBar bar, string message)
    {
        bar.Message = message;
        bar.IsOpen = true;
    }

    // ---------- Delete ----------

    private async void OnDeletePolicy(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: WebPolicyRowViewModel row })
        {
            return;
        }
        var confirm = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = "Delete policy?",
            Content = new TextBlock
            {
                TextWrapping = TextWrapping.Wrap,
                Text = $"Delete \"{row.Name}\"? Devices it is assigned to will stop enforcing its rules.",
            },
            PrimaryButtonText = "Delete",
            CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Close,
        };
        if (await confirm.ShowAsync() == ContentDialogResult.Primary)
        {
            await ViewModel.DeletePolicyCommand.ExecuteAsync(row);
        }
    }

    // ---------- Assignments ----------

    private async void OnAssignPolicy(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: WebPolicyRowViewModel row })
        {
            return;
        }

        var current = new List<WebAssignmentDto>(await ViewModel.LoadAssignmentsAsync(row.Id));
        var employees = await ViewModel.GetEmployeesAsync();
        var devices = await ViewModel.GetDevicesAsync();

        var error = new InfoBar { Severity = InfoBarSeverity.Error, IsOpen = false, IsClosable = false };
        var secondary = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];

        var typeBox = new ComboBox { Header = "Assign to", SelectedIndex = 0, HorizontalAlignment = HorizontalAlignment.Stretch };
        typeBox.Items.Add(new ComboBoxItem { Content = "Employee", Tag = "EMPLOYEE" });
        typeBox.Items.Add(new ComboBoxItem { Content = "Device", Tag = "DEVICE" });

        var targetBox = new ComboBox
        {
            Header = "Target",
            HorizontalAlignment = HorizontalAlignment.Stretch,
            DisplayMemberPath = nameof(Target.Label),
        };

        void Repopulate()
        {
            string type = (typeBox.SelectedItem as ComboBoxItem)?.Tag as string ?? "EMPLOYEE";
            targetBox.ItemsSource = type == "DEVICE"
                ? devices.Select(d => new Target(d.Id, $"{d.Hostname} ({d.DeviceCode})")).ToList()
                : employees.Select(x => new Target(x.Id, $"{x.FullName} ({x.EmployeeCode})")).ToList();
            targetBox.SelectedIndex = targetBox.Items.Count > 0 ? 0 : -1;
        }
        typeBox.SelectionChanged += (_, _) => Repopulate();
        Repopulate();

        var assignmentsPanel = new StackPanel { Spacing = 2 };

        void Render()
        {
            assignmentsPanel.Children.Clear();
            if (current.Count == 0)
            {
                assignmentsPanel.Children.Add(new TextBlock
                {
                    Text = "None yet.", FontSize = 12, Foreground = secondary,
                });
                return;
            }
            foreach (var a in current)
            {
                var captured = a;
                var g = new Grid { ColumnSpacing = 10, Padding = new Thickness(0, 4, 0, 4) };
                g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                var info = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
                info.Children.Add(new TextBlock { Text = a.TargetLabel ?? $"{a.TargetType} {a.TargetId}" });
                info.Children.Add(new TextBlock { Text = a.TargetType, FontSize = 11, Foreground = secondary });
                Grid.SetColumn(info, 0);
                var remove = new Button { Content = "Remove", VerticalAlignment = VerticalAlignment.Center };
                Grid.SetColumn(remove, 1);
                remove.Click += async (_, _) =>
                {
                    if (await ViewModel.UnassignAsync(row.Id, captured.Id))
                    {
                        current.Remove(captured);
                        Render();
                    }
                    else
                    {
                        Fail(error, ViewModel.Error ?? "Could not remove the assignment.");
                    }
                };
                g.Children.Add(info);
                g.Children.Add(remove);
                assignmentsPanel.Children.Add(g);
            }
        }
        Render();

        var content = new StackPanel { Spacing = 12, Width = 440 };
        content.Children.Add(error);
        content.Children.Add(new TextBlock { Text = "Current assignments", Style = (Style)Application.Current.Resources["ColHeader"] });
        content.Children.Add(new ScrollViewer { Content = assignmentsPanel, MaxHeight = 160, VerticalScrollBarVisibility = ScrollBarVisibility.Auto });
        content.Children.Add(new TextBlock
        {
            Text = "An employee assignment applies to all of that employee's devices.",
            FontSize = 12, TextWrapping = TextWrapping.Wrap, Foreground = secondary,
        });
        content.Children.Add(typeBox);
        content.Children.Add(targetBox);

        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = $"Assignments — {row.Name}",
            Content = content,
            PrimaryButtonText = "Add",
            CloseButtonText = "Done",
            DefaultButton = ContentDialogButton.Primary,
        };

        dialog.PrimaryButtonClick += async (_, args) =>
        {
            var deferral = args.GetDeferral();
            try
            {
                args.Cancel = true;   // keep the dialog open to add more / review
                if (targetBox.SelectedItem is not Target t)
                {
                    Fail(error, "Pick a target to assign.");
                    return;
                }
                string type = (typeBox.SelectedItem as ComboBoxItem)?.Tag as string ?? "EMPLOYEE";
                if (current.Any(a => string.Equals(a.TargetType, type, StringComparison.OrdinalIgnoreCase) && a.TargetId == t.Id))
                {
                    Fail(error, "That target is already assigned.");
                    return;
                }
                error.IsOpen = false;
                var created = await ViewModel.AssignAsync(row.Id, type, t.Id);
                if (created is not null)
                {
                    current.Add(created);
                    Render();
                }
                else
                {
                    Fail(error, ViewModel.Error ?? "Could not assign.");
                }
            }
            finally
            {
                deferral.Complete();
            }
        };

        await dialog.ShowAsync();
    }

    private sealed record Target(long Id, string Label);
}
