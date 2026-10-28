using EmployeeMonitor.Admin.Core.ViewModels;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Windows.System;

namespace EmployeeMonitor.Admin;

public sealed partial class LoginWindow : Window
{
    public LoginViewModel ViewModel { get; }

    public LoginWindow()
    {
        ViewModel = App.GetService<LoginViewModel>();
        InitializeComponent();
        Title = "Employee Monitor Admin";
        ViewModel.LoggedIn += OnLoggedIn;
    }

    private void OnPasswordChanged(object sender, RoutedEventArgs e)
    {
        ViewModel.Password = ((PasswordBox)sender).Password;
    }

    private void OnPasswordKeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key == VirtualKey.Enter && ViewModel.LoginCommand.CanExecute(null))
        {
            ViewModel.LoginCommand.Execute(null);
        }
    }

    private void OnLoggedIn(Core.Api.UserInfo user)
    {
        ViewModel.LoggedIn -= OnLoggedIn;
        ((App)Application.Current).SwitchWindow(new MainWindow());
    }
}
