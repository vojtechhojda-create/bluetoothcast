using NAudio.CoreAudioApi;
using NAudio.Wave;

// plays raw 16 bit pcm from stdin on the default output (phone -> pc audio)
static class Player
{
    public static void Run(int rate, int channels)
    {
        var fmt = new WaveFormat(rate, 16, channels);
        var buffer = new BufferedWaveProvider(fmt)
        {
            DiscardOnBufferOverflow = true,
            BufferDuration = TimeSpan.FromMilliseconds(250)
        };
        using var output = new WasapiOut(AudioClientShareMode.Shared, true, 40);
        output.Init(buffer);
        output.Play();

        var stdin = Console.OpenStandardInput();
        int align = fmt.BlockAlign;
        var tmp = new byte[8192 + align];
        int carry = 0;
        int n;
        while ((n = stdin.Read(tmp, carry, 8192)) > 0)
        {
            int total = carry + n;
            int usable = total - (total % align);
            buffer.AddSamples(tmp, 0, usable);
            carry = total - usable;
            if (carry > 0) Array.Copy(tmp, usable, tmp, 0, carry);
        }
    }
}
