using System.Diagnostics;
using NAudio.Wave;

if (args.Length < 1)
{
    Console.Error.WriteLine("Usage: WasapiLoopback <output.wav> [--include NAME|PID] [--exclude NAME|PID]");
    return 1;
}

var output = args[0];
string? includeTarget = null;
string? excludeTarget = null;

for (int i = 1; i < args.Length; i++)
{
    if (args[i] == "--include" && i + 1 < args.Length) includeTarget = args[++i];
    else if (args[i] == "--exclude" && i + 1 < args.Length) excludeTarget = args[++i];
}

Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(output))!);

try
{
    if (!string.IsNullOrWhiteSpace(includeTarget))
    {
        return RunProcessCapture(output, includeTarget!, include: true);
    }

    if (!string.IsNullOrWhiteSpace(excludeTarget))
    {
        var pid = ResolvePid(excludeTarget!);
        if (pid == null)
        {
            Console.Error.WriteLine($"Exclude target '{excludeTarget}' not running — falling back to full desktop loopback");
            return RunFullLoopback(output);
        }

        return RunProcessCapture(output, excludeTarget!, include: false, forcedPid: pid);
    }

    return RunFullLoopback(output);
}
catch (Exception ex)
{
    Console.Error.WriteLine("WasapiLoopback failed: " + ex);
    return 2;
}

static int RunFullLoopback(string output)
{
    using var capture = new WasapiLoopbackCapture();
    // WASAPI loopback is usually IEEE float — Java Sound often fails to play it (silent PC track).
    // Always write PCM 16-bit LE so Minecraft/Java can open it.
    var srcFormat = capture.WaveFormat;
    var pcmFormat = new WaveFormat(srcFormat.SampleRate, 16, srcFormat.Channels);
    using var writer = new WaveFileWriter(output, pcmFormat);
    capture.DataAvailable += (_, e) =>
    {
        if (e.BytesRecorded <= 0) return;
        if (srcFormat.Encoding == WaveFormatEncoding.IeeeFloat)
        {
            byte[] pcm = Float32ToPcm16(e.Buffer, e.BytesRecorded);
            writer.Write(pcm, 0, pcm.Length);
        }
        else
        {
            writer.Write(e.Buffer, 0, e.BytesRecorded);
        }
    };
    capture.StartRecording();
    Console.Error.WriteLine($"WASAPI loopback (full desktop → pcm16 {pcmFormat.SampleRate}Hz)… press q to stop");
    WaitForQuit();
    capture.StopRecording();
    writer.Flush();
    Console.Error.WriteLine($"Stopped, wrote {new FileInfo(output).Length} bytes");
    return 0;
}

static byte[] Float32ToPcm16(byte[] src, int bytesRecorded)
{
    int samples = bytesRecorded / 4;
    byte[] dst = new byte[samples * 2];
    for (int i = 0, o = 0; i < samples; i++, o += 2)
    {
        float f = BitConverter.ToSingle(src, i * 4);
        if (f > 1f) f = 1f;
        if (f < -1f) f = -1f;
        short s = (short)Math.Round(f * short.MaxValue);
        dst[o] = (byte)(s & 0xFF);
        dst[o + 1] = (byte)((s >> 8) & 0xFF);
    }
    return dst;
}

static int RunProcessCapture(string output, string target, bool include, uint? forcedPid = null)
{
    uint? pid = forcedPid ?? ResolvePid(target);
    if (pid == null)
    {
        Console.Error.WriteLine($"Process '{target}' not found — writing empty WAV");
        WriteSilentWav(output, TimeSpan.Zero);
        return 0;
    }

    using var capture = new ProcessLoopbackCapture(pid.Value, include);
    WaveFileWriter? writer = null;
    capture.DataAvailable += (_, e) =>
    {
        if (writer == null)
        {
            writer = new WaveFileWriter(output, capture.WaveFormat);
        }
        writer.Write(e.Buffer, 0, e.BytesRecorded);
    };

    capture.StartRecording();
    Console.Error.WriteLine(
        $"WASAPI process loopback ({(include ? "include" : "exclude")} pid={pid})… press q to stop");
    WaitForQuit();
    capture.StopRecording();
    writer?.Flush();
    writer?.Dispose();

    if (writer == null)
    {
        // No audio packets arrived (e.g. Spotify silent) — still create a valid empty wav
        WriteSilentWav(output, TimeSpan.Zero);
    }

    long len = new FileInfo(output).Exists ? new FileInfo(output).Length : 0;
    Console.Error.WriteLine($"Stopped, wrote {len} bytes");
    return 0;
}

static uint? ResolvePid(string target)
{
    if (uint.TryParse(target, out uint pid))
    {
        try
        {
            Process.GetProcessById((int)pid);
            return pid;
        }
        catch
        {
            return null;
        }
    }

    return ProcessLoopbackCapture.FindProcessId(target);
}

static void WaitForQuit()
{
    try
    {
        while (true)
        {
            var ch = Console.In.Read();
            if (ch < 0) break;
            if (ch is 'q' or 'Q') break;
        }
    }
    catch
    {
        // stdin closed
    }
}

static void WriteSilentWav(string output, TimeSpan duration)
{
    var format = new WaveFormat(48000, 16, 2);
    using var writer = new WaveFileWriter(output, format);
    if (duration > TimeSpan.Zero)
    {
        int bytes = (int)(format.AverageBytesPerSecond * duration.TotalSeconds);
        writer.Write(new byte[bytes], 0, bytes);
    }
}
