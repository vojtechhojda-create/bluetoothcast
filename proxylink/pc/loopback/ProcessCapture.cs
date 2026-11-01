using System.Runtime.InteropServices;

// per-app capture through the windows application loopback api (process loopback).
// needs windows 10 build 20348+ or windows 11. output: 48 khz, 16 bit, stereo pcm on stdout.
static class ProcessCapture
{
    const uint LOOPBACK = 0x00020000;
    const uint EVENTCALLBACK = 0x00040000;
    const uint AUTOCONVERTPCM = 0x80000000;
    const uint SRC_DEFAULT_QUALITY = 0x08000000;
    const uint BUFFERFLAGS_SILENT = 2;

    static readonly Guid IID_IAudioClient = new("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2");
    static readonly Guid IID_IAudioCaptureClient = new("C8ADBD64-E71E-48a0-A4DE-185C395CD317");

    record Attempt(bool Float, uint Flags, long Duration);

    public static void Run(int pid)
    {
        var attempts = new[]
        {
            new Attempt(false, LOOPBACK | EVENTCALLBACK, 200000),
            new Attempt(false, LOOPBACK | EVENTCALLBACK | AUTOCONVERTPCM | SRC_DEFAULT_QUALITY, 200000),
            new Attempt(true, LOOPBACK | EVENTCALLBACK, 200000),
            new Attempt(false, LOOPBACK | EVENTCALLBACK, 1000000),
        };

        IAudioClient? client = null;
        bool isFloat = false;
        foreach (var a in attempts)
        {
            client = Activate(pid);
            if (client == null)
            {
                Console.Error.WriteLine("activation failed (needs windows 10 20348+ / 11, running target pid)");
                Environment.Exit(2);
            }
            var fmt = MakeFormat(a.Float);
            var ptr = Marshal.AllocHGlobal(Marshal.SizeOf<WAVEFORMATEX>());
            Marshal.StructureToPtr(fmt, ptr, false);
            int hr = client.Initialize(0, a.Flags, a.Duration, 0, ptr, IntPtr.Zero);
            Marshal.FreeHGlobal(ptr);
            if (hr >= 0)
            {
                isFloat = a.Float;
                break;
            }
            Console.Error.WriteLine($"initialize failed 0x{hr:x8}, trying next format");
            Marshal.ReleaseComObject(client);
            client = null;
        }
        if (client == null) Environment.Exit(3);

        var ev = new AutoResetEvent(false);
        client.SetEventHandle(ev.SafeWaitHandle.DangerousGetHandle());
        var iid = IID_IAudioCaptureClient;
        client.GetService(ref iid, out var svc);
        var cap = (IAudioCaptureClient)svc;
        client.Start();

        var stdout = Console.OpenStandardOutput();
        int frameBytes = isFloat ? 8 : 4;
        byte[] raw = new byte[0];
        byte[] pcm = new byte[0];

        while (true)
        {
            ev.WaitOne(200);
            bool wrote = false;
            while (true)
            {
                cap.GetNextPacketSize(out uint frames);
                if (frames == 0) break;
                cap.GetBuffer(out IntPtr data, out uint n, out uint flags, out _, out _);
                int bytes = (int)n * frameBytes;
                int outBytes = (int)n * 4;
                if (pcm.Length < outBytes) pcm = new byte[outBytes];
                if ((flags & BUFFERFLAGS_SILENT) != 0)
                {
                    Array.Clear(pcm, 0, outBytes);
                }
                else if (!isFloat)
                {
                    Marshal.Copy(data, pcm, 0, outBytes);
                }
                else
                {
                    var f = new float[n * 2];
                    Marshal.Copy(data, f, 0, f.Length);
                    for (int i = 0; i < f.Length; i++)
                    {
                        int v = (int)Math.Round(Math.Clamp(f[i], -1f, 1f) * 32767f);
                        pcm[i * 2] = (byte)(v & 0xff);
                        pcm[i * 2 + 1] = (byte)((v >> 8) & 0xff);
                    }
                }
                cap.ReleaseBuffer(n);
                stdout.Write(pcm, 0, outBytes);
                wrote = true;
            }
            if (wrote) stdout.Flush();
        }
    }

    static WAVEFORMATEX MakeFormat(bool isFloat)
    {
        ushort bits = (ushort)(isFloat ? 32 : 16);
        return new WAVEFORMATEX
        {
            wFormatTag = (ushort)(isFloat ? 3 : 1),
            nChannels = 2,
            nSamplesPerSec = 48000,
            wBitsPerSample = bits,
            nBlockAlign = (ushort)(2 * bits / 8),
            nAvgBytesPerSec = (uint)(48000 * 2 * bits / 8),
            cbSize = 0
        };
    }

    static IAudioClient? Activate(int pid)
    {
        var p = new ActivationParams { ActivationType = 1, TargetProcessId = (uint)pid, ProcessLoopbackMode = 0 };
        int size = Marshal.SizeOf<ActivationParams>();
        IntPtr blob = Marshal.AllocHGlobal(size);
        Marshal.StructureToPtr(p, blob, false);

        int pvSize = IntPtr.Size == 8 ? 24 : 16;
        IntPtr pv = Marshal.AllocHGlobal(pvSize);
        for (int i = 0; i < pvSize; i++) Marshal.WriteByte(pv, i, 0);
        Marshal.WriteInt16(pv, 0, 65); // VT_BLOB
        Marshal.WriteInt32(pv, 8, size);
        Marshal.WriteIntPtr(pv, IntPtr.Size == 8 ? 16 : 12, blob);

        var handler = new Handler();
        var iid = IID_IAudioClient;
        ActivateAudioInterfaceAsync("VAD\\Process_Loopback", ref iid, pv, handler, out _);
        bool ok = handler.Done.WaitOne(5000);

        Marshal.FreeHGlobal(pv);
        Marshal.FreeHGlobal(blob);
        if (!ok || handler.Result < 0 || handler.Client == null) return null;
        return (IAudioClient)handler.Client;
    }

    [DllImport("Mmdevapi.dll", ExactSpelling = true, PreserveSig = false)]
    static extern void ActivateAudioInterfaceAsync(
        [MarshalAs(UnmanagedType.LPWStr)] string deviceInterfacePath,
        ref Guid riid,
        IntPtr activationParams,
        IActivateAudioInterfaceCompletionHandler completionHandler,
        out IActivateAudioInterfaceAsyncOperation activationOperation);

    [StructLayout(LayoutKind.Sequential)]
    struct ActivationParams
    {
        public int ActivationType;
        public uint TargetProcessId;
        public int ProcessLoopbackMode;
    }

    [StructLayout(LayoutKind.Sequential, Pack = 2)]
    struct WAVEFORMATEX
    {
        public ushort wFormatTag;
        public ushort nChannels;
        public uint nSamplesPerSec;
        public uint nAvgBytesPerSec;
        public ushort nBlockAlign;
        public ushort wBitsPerSample;
        public ushort cbSize;
    }

    [ComImport, Guid("94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAgileObject { }

    [ComImport, Guid("41D949AB-9862-444A-80F6-C261334DA5EB"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IActivateAudioInterfaceCompletionHandler
    {
        [PreserveSig] int ActivateCompleted(IActivateAudioInterfaceAsyncOperation operation);
    }

    [ComImport, Guid("72A22D78-CDE4-431D-B8CC-843A71199B6D"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IActivateAudioInterfaceAsyncOperation
    {
        [PreserveSig] int GetActivateResult(out int activateResult, [MarshalAs(UnmanagedType.IUnknown)] out object activatedInterface);
    }

    [ComVisible(true)]
    class Handler : IActivateAudioInterfaceCompletionHandler, IAgileObject
    {
        public ManualResetEvent Done = new(false);
        public int Result = -1;
        public object? Client;

        public int ActivateCompleted(IActivateAudioInterfaceAsyncOperation operation)
        {
            try
            {
                operation.GetActivateResult(out Result, out var c);
                Client = c;
            }
            catch { Result = -1; }
            Done.Set();
            return 0;
        }
    }

    [ComImport, Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioClient
    {
        [PreserveSig] int Initialize(int shareMode, uint streamFlags, long hnsBufferDuration, long hnsPeriodicity, IntPtr pFormat, IntPtr audioSessionGuid);
        [PreserveSig] int GetBufferSize(out uint bufferFrameCount);
        [PreserveSig] int GetStreamLatency(out long latency);
        [PreserveSig] int GetCurrentPadding(out uint currentPadding);
        [PreserveSig] int IsFormatSupported(int shareMode, IntPtr pFormat, out IntPtr closestMatch);
        [PreserveSig] int GetMixFormat(out IntPtr deviceFormat);
        [PreserveSig] int GetDevicePeriod(out long defaultPeriod, out long minimumPeriod);
        [PreserveSig] int Start();
        [PreserveSig] int Stop();
        [PreserveSig] int Reset();
        [PreserveSig] int SetEventHandle(IntPtr eventHandle);
        [PreserveSig] int GetService(ref Guid riid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
    }

    [ComImport, Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IAudioCaptureClient
    {
        [PreserveSig] int GetBuffer(out IntPtr data, out uint numFramesToRead, out uint flags, out ulong devicePosition, out ulong qpcPosition);
        [PreserveSig] int ReleaseBuffer(uint numFramesRead);
        [PreserveSig] int GetNextPacketSize(out uint numFramesInNextPacket);
    }
}
