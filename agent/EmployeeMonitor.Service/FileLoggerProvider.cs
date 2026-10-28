using Microsoft.Extensions.Logging;

namespace EmployeeMonitor.Service;

/// <summary>
/// Minimal dependency-free file logger. Because the agent runs windowless (no console) in the background, its logs
/// are written to a daily file under %ProgramData%\EmployeeMonitor\logs so enrollment/heartbeat issues can still be
/// diagnosed (diagnose.cmd shows the latest lines). Information level and above only.
/// </summary>
public sealed class FileLoggerProvider : ILoggerProvider
{
    private readonly object _gate = new();
    private readonly string _directory;

    public FileLoggerProvider(string directory)
    {
        _directory = directory;
        try { Directory.CreateDirectory(_directory); } catch { /* best effort */ }
    }

    public ILogger CreateLogger(string categoryName) => new FileLogger(categoryName, Write);

    private void Write(string line)
    {
        lock (_gate)
        {
            try
            {
                var path = Path.Combine(_directory, $"agent-{DateTime.Now:yyyyMMdd}.log");
                File.AppendAllText(path, line + Environment.NewLine);
            }
            catch
            {
                // Logging must never crash the agent.
            }
        }
    }

    public void Dispose()
    {
    }

    private sealed class FileLogger : ILogger
    {
        private readonly string _category;
        private readonly Action<string> _write;

        public FileLogger(string category, Action<string> write)
        {
            _category = category;
            _write = write;
        }

        public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;

        public bool IsEnabled(LogLevel logLevel) => logLevel >= LogLevel.Information;

        public void Log<TState>(LogLevel logLevel, EventId eventId, TState state, Exception? exception,
            Func<TState, Exception?, string> formatter)
        {
            if (!IsEnabled(logLevel))
            {
                return;
            }
            string shortCategory = _category.Contains('.') ? _category[(_category.LastIndexOf('.') + 1)..] : _category;
            string line = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {logLevel,-11} {shortCategory}: {formatter(state, exception)}";
            if (exception is not null)
            {
                line += Environment.NewLine + exception;
            }
            _write(line);
        }
    }
}
