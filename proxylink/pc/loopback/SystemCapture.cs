using NAudio.Wave;
using NAudio.Wave.SampleProviders;

static class SystemCapture
{
    public static void Run()
    {
        var capture = new WasapiLoopbackCapture();
        var buffer = new BufferedWaveProvider(capture.WaveFormat)
        {
            DiscardOnBufferOverflow = true,
            BufferDuration = TimeSpan.FromMilliseconds(400),
            ReadFully = false
        };

        ISampleProvider samples = buffer.ToSampleProvider();
        if (samples.WaveFormat.Channels == 1)
        {
            samples = new MonoToStereoSampleProvider(samples);
        }
        else if (samples.WaveFormat.Channels > 2)
        {
            var mux = new MultiplexingSampleProvider(new[] { samples }, 2);
            mux.ConnectInputToOutput(0, 0);
            mux.ConnectInputToOutput(1, 1);
            samples = mux;
        }
        if (samples.WaveFormat.SampleRate != 48000)
        {
            samples = new WdlResamplingSampleProvider(samples, 48000);
        }
        var pcm = new SampleToWaveProvider16(samples);

        var stdout = Console.OpenStandardOutput();
        var tmp = new byte[19200];
        var done = new ManualResetEventSlim();

        capture.DataAvailable += (_, e) =>
        {
            buffer.AddSamples(e.Buffer, 0, e.BytesRecorded);
            int n;
            while ((n = pcm.Read(tmp, 0, tmp.Length)) > 0)
            {
                stdout.Write(tmp, 0, n);
            }
            stdout.Flush();
        };
        capture.RecordingStopped += (_, _) => done.Set();
        Console.CancelKeyPress += (_, e) =>
        {
            e.Cancel = true;
            capture.StopRecording();
        };

        capture.StartRecording();
        done.Wait();
    }
}
