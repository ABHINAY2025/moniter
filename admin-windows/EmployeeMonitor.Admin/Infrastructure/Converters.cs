using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Data;
using Microsoft.UI.Xaml.Media;
using Windows.UI;

namespace EmployeeMonitor.Admin.Infrastructure;

/// <summary>Presence status colours, harmonised with the light "sky" theme. Shared by the dot and pill converters.</summary>
internal static class StatusPalette
{
    public static Color Of(object? value)
    {
        var status = (value as string)?.ToUpperInvariant();
        return status switch
        {
            "ACTIVE" => Color.FromArgb(255, 31, 157, 87),    // green
            "IDLE" => Color.FromArgb(255, 201, 138, 18),     // amber
            "LOCKED" => Color.FromArgb(255, 62, 111, 240),   // blue (accent)
            "AWAY" => Color.FromArgb(255, 201, 138, 18),     // amber (Teams-style away)
            _ => Color.FromArgb(255, 122, 133, 153),         // slate-gray (offline/unknown)
        };
    }
}

/// <summary>Maps a presence status string to a solid status colour (used for the dot and the pill text).</summary>
public sealed class StatusToBrushConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, string language) =>
        new SolidColorBrush(StatusPalette.Of(value));

    public object ConvertBack(object value, Type targetType, object parameter, string language) =>
        throw new NotSupportedException();
}

/// <summary>Maps a presence status to a soft translucent capsule fill behind the status pill.</summary>
public sealed class StatusToPillBrushConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, string language)
    {
        Color c = StatusPalette.Of(value);
        return new SolidColorBrush(Color.FromArgb(38, c.R, c.G, c.B));   // ~15% tint
    }

    public object ConvertBack(object value, Type targetType, object parameter, string language) =>
        throw new NotSupportedException();
}

/// <summary>True -> Visible, False -> Collapsed. Pass parameter "invert" to flip.</summary>
public sealed class BoolToVisibilityConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, string language)
    {
        bool flag = value is bool b && b;
        if (string.Equals(parameter as string, "invert", StringComparison.OrdinalIgnoreCase))
        {
            flag = !flag;
        }
        return flag ? Visibility.Visible : Visibility.Collapsed;
    }

    public object ConvertBack(object value, Type targetType, object parameter, string language) =>
        throw new NotSupportedException();
}

/// <summary>Seconds (long/int, possibly null) -> compact "Xh Ym" / "Ym" / "Zs" duration text.</summary>
public sealed class SecondsToDurationConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, string language)
    {
        long seconds = value switch
        {
            long l => l,
            int i => i,
            _ => 0,
        };
        if (seconds <= 0)
        {
            return "0m";
        }
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        if (h > 0)
        {
            return m > 0 ? $"{h}h {m}m" : $"{h}h";
        }
        return m > 0 ? $"{m}m" : $"{seconds}s";
    }

    public object ConvertBack(object value, Type targetType, object parameter, string language) =>
        throw new NotSupportedException();
}

/// <summary>A count -> "N visit" / "N visits".</summary>
public sealed class CountToVisitsConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, string language)
    {
        long n = value switch { long l => l, int i => i, _ => 0 };
        return n == 1 ? "1 visit" : $"{n} visits";
    }

    public object ConvertBack(object value, Type targetType, object parameter, string language) =>
        throw new NotSupportedException();
}

/// <summary>Muted -> mute glyph; unmuted -> volume glyph (Segoe MDL2 Assets).</summary>
public sealed class MuteGlyphConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, string language) =>
        value is bool muted && muted ? "" : "";   // Mute : Volume

    public object ConvertBack(object value, Type targetType, object parameter, string language) =>
        throw new NotSupportedException();
}

/// <summary>Maps a web-access result label ("Blocked"/"Allowed"/"Observed"/…) to a status colour.</summary>
public sealed class WebResultToBrushConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, string language)
    {
        var label = (value as string)?.ToLowerInvariant() ?? "";
        Color c = label switch
        {
            "blocked" => Color.FromArgb(255, 201, 42, 42),       // red
            "allowed" => Color.FromArgb(255, 31, 157, 87),       // green
            "enforcement error" => Color.FromArgb(255, 201, 138, 18), // amber
            _ => Color.FromArgb(255, 122, 133, 153),             // slate (observed/unknown)
        };
        return new SolidColorBrush(c);
    }

    public object ConvertBack(object value, Type targetType, object parameter, string language) =>
        throw new NotSupportedException();
}

/// <summary>Non-empty string -> Visible (used to show error banners only when there is a message).</summary>
public sealed class StringToVisibilityConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, string language) =>
        string.IsNullOrWhiteSpace(value as string) ? Visibility.Collapsed : Visibility.Visible;

    public object ConvertBack(object value, Type targetType, object parameter, string language) =>
        throw new NotSupportedException();
}
