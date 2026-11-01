using System.Diagnostics;
using System.Text.Json;
using NAudio.CoreAudioApi;

static class Lister
{
    public static void Run()
    {
        var list = new List<object>();
        var seen = new HashSet<int>();
        using var en = new MMDeviceEnumerator();
        var dev = en.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
        var sessions = dev.AudioSessionManager.Sessions;
        for (int i = 0; i < sessions.Count; i++)
        {
            try
            {
                var s = sessions[i];
                if (s.IsSystemSoundsSession) continue;
                var pid = (int)s.GetProcessID;
                if (pid <= 0 || !seen.Add(pid)) continue;
                string name = "", title = "";
                try
                {
                    var p = Process.GetProcessById(pid);
                    name = p.ProcessName;
                    title = p.MainWindowTitle;
                }
                catch { }
                list.Add(new { pid, name, title, active = s.State == AudioSessionState.AudioSessionStateActive });
            }
            catch { }
        }
        Console.Out.Write(JsonSerializer.Serialize(list));
    }
}
