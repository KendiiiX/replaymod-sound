using System.Diagnostics;
using System.Runtime.InteropServices;
using NAudio.CoreAudioApi;
using NAudio.CoreAudioApi.Interfaces;
using NAudio.Wave;

/// <summary>
/// Per-process WASAPI loopback (Win10 2004+): include or exclude a process tree.
/// Activation via ActivateAudioInterfaceAsync, capture via NAudio.AudioClient.
/// </summary>
internal sealed class ProcessLoopbackCapture : IDisposable
{
    private const string VirtualDevice = "VAD\\Process_Loopback";
    private static readonly Guid IidIAudioClient = new("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2");

    private readonly uint _pid;
    private readonly bool _include;
    private AudioClient? _audioClient;
    private WaveFormat? _format;
    private Thread? _thread;
    private volatile bool _stopping;
    private EventWaitHandle? _frameEvent;

    public WaveFormat WaveFormat => _format ?? throw new InvalidOperationException("Not started");

    public event EventHandler<WaveInEventArgs>? DataAvailable;

    public ProcessLoopbackCapture(uint processId, bool includeProcessTree)
    {
        _pid = processId;
        _include = includeProcessTree;
    }

    public static uint? FindProcessId(string processName)
    {
        string name = processName.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)
            ? processName[..^4]
            : processName;
        Process[] procs;
        try
        {
            procs = Process.GetProcessesByName(name);
        }
        catch
        {
            return null;
        }

        if (procs.Length == 0)
        {
            return null;
        }

        Process? best = procs
            .OrderByDescending(p =>
            {
                try { return p.MainWindowHandle != IntPtr.Zero ? 1 : 0; }
                catch { return 0; }
            })
            .ThenByDescending(p =>
            {
                try { return p.WorkingSet64; }
                catch { return 0L; }
            })
            .FirstOrDefault();

        try
        {
            return best == null ? null : (uint)best.Id;
        }
        finally
        {
            foreach (var p in procs) p.Dispose();
        }
    }

    public void StartRecording()
    {
        ActivateAndInit();
        _stopping = false;
        _thread = new Thread(CaptureLoop) { IsBackground = true, Name = "rms-proc-loopback" };
        _thread.Start();
    }

    public void StopRecording()
    {
        _stopping = true;
        try { _audioClient?.Stop(); } catch { /* ignore */ }
        _thread?.Join(3000);
        _thread = null;
    }

    private void ActivateAndInit()
    {
        var activation = new AudioClientActivationParams
        {
            ActivationType = 1, // PROCESS_LOOPBACK
            ProcessLoopbackParams = new AudioClientProcessLoopbackParams
            {
                TargetProcessId = _pid,
                ProcessLoopbackMode = _include ? 0 : 1 // include : exclude
            }
        };

        int paramsSize = Marshal.SizeOf<AudioClientActivationParams>();
        IntPtr paramsPtr = Marshal.AllocHGlobal(paramsSize);
        IntPtr pvPtr = Marshal.AllocHGlobal(Marshal.SizeOf<PropVariantBlobHeader>());
        try
        {
            Marshal.StructureToPtr(activation, paramsPtr, false);
            var pv = new PropVariantBlobHeader
            {
                Vt = 0x0041, // VT_BLOB
                BlobSize = (uint)paramsSize,
                BlobData = paramsPtr
            };
            Marshal.StructureToPtr(pv, pvPtr, false);

            var handler = new ActivateCompletionHandler();
            WasapiNative.ActivateAudioInterfaceAsync(
                VirtualDevice,
                IidIAudioClient,
                pvPtr,
                handler,
                out var op);
            GC.KeepAlive(op);

            if (!handler.Done.WaitOne(TimeSpan.FromSeconds(10)))
            {
                throw new TimeoutException("ActivateAudioInterfaceAsync timed out");
            }

            if (handler.Hr != 0 || handler.ClientPtr == IntPtr.Zero)
            {
                throw Marshal.GetExceptionForHR(handler.Hr)
                       ?? new InvalidOperationException($"Activation failed HR=0x{handler.Hr:X8}");
            }

            var iac = (IAudioClient)Marshal.GetObjectForIUnknown(handler.ClientPtr);
            Marshal.Release(handler.ClientPtr);
            _audioClient = new AudioClient(iac);

            // Microsoft ApplicationLoopback: PCM 44.1k stereo + LOOPBACK|EVENTCALLBACK|AUTOCONVERTPCM
            _format = new WaveFormat(44100, 16, 2);
            _frameEvent = new EventWaitHandle(false, EventResetMode.AutoReset);

            var flags = AudioClientStreamFlags.Loopback
                        | AudioClientStreamFlags.EventCallback
                        | AudioClientStreamFlags.AutoConvertPcm;

            _audioClient.Initialize(
                AudioClientShareMode.Shared,
                flags,
                0,
                0,
                _format,
                Guid.Empty);

            _audioClient.SetEventHandle(_frameEvent.SafeWaitHandle.DangerousGetHandle());
            _ = _audioClient.AudioCaptureClient; // force service resolve early
            _audioClient.Start();
            Console.Error.WriteLine($"process-loopback ready pid={_pid} include={_include} format={_format}");
        }
        finally
        {
            Marshal.FreeHGlobal(pvPtr);
            Marshal.FreeHGlobal(paramsPtr);
        }
    }

    private void CaptureLoop()
    {
        if (_audioClient == null || _format == null || _frameEvent == null)
        {
            return;
        }

        var capture = _audioClient.AudioCaptureClient;
        int frameBytes = _format.BlockAlign;
        byte[] buffer = new byte[Math.Max(frameBytes * 4410, 4096)];

        while (!_stopping)
        {
            _frameEvent.WaitOne(100);
            if (_stopping) break;

            try
            {
                int packet = capture.GetNextPacketSize();
                while (packet != 0)
                {
                    var data = capture.GetBuffer(out int framesAvailable, out AudioClientBufferFlags flags, out _, out _);
                    int bytes = framesAvailable * frameBytes;
                    if (bytes > buffer.Length)
                    {
                        buffer = new byte[bytes];
                    }

                    if (flags.HasFlag(AudioClientBufferFlags.Silent) || data == IntPtr.Zero)
                    {
                        Array.Clear(buffer, 0, bytes);
                    }
                    else
                    {
                        Marshal.Copy(data, buffer, 0, bytes);
                    }

                    capture.ReleaseBuffer(framesAvailable);
                    DataAvailable?.Invoke(this, new WaveInEventArgs(buffer, bytes));
                    packet = capture.GetNextPacketSize();
                }
            }
            catch
            {
                if (_stopping) break;
            }
        }
    }

    public void Dispose()
    {
        StopRecording();
        _audioClient?.Dispose();
        _audioClient = null;
        _frameEvent?.Dispose();
        _frameEvent = null;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct AudioClientProcessLoopbackParams
    {
        public uint TargetProcessId;
        public int ProcessLoopbackMode;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct AudioClientActivationParams
    {
        public int ActivationType;
        public AudioClientProcessLoopbackParams ProcessLoopbackParams;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct PropVariantBlobHeader
    {
        public ushort Vt;
        public ushort Reserved1;
        public ushort Reserved2;
        public ushort Reserved3;
        public uint BlobSize;
        public IntPtr BlobData;
    }
}

[ComImport]
[Guid("41D949AB-9862-444A-80F6-C261334DA5EB")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IActivateAudioInterfaceCompletionHandler
{
    void ActivateCompleted(IActivateAudioInterfaceAsyncOperation activateOperation);
}

[ComImport]
[Guid("72A22D78-CDE4-431D-B8CC-843A71199B6D")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IActivateAudioInterfaceAsyncOperation
{
    void GetActivateResult(
        [MarshalAs(UnmanagedType.Error)] out int activateResult,
        out IntPtr activatedInterface);
}

[ComImport]
[Guid("94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IAgileObject
{
}

[ComVisible(true)]
[ClassInterface(ClassInterfaceType.None)]
internal sealed class ActivateCompletionHandler : IActivateAudioInterfaceCompletionHandler, IAgileObject
{
    public readonly ManualResetEvent Done = new(false);
    public int Hr;
    public IntPtr ClientPtr;

    public void ActivateCompleted(IActivateAudioInterfaceAsyncOperation activateOperation)
    {
        try
        {
            activateOperation.GetActivateResult(out Hr, out ClientPtr);
        }
        catch (Exception)
        {
            Hr = unchecked((int)0x80004005);
            ClientPtr = IntPtr.Zero;
        }
        finally
        {
            Done.Set();
        }
    }
}

internal static class WasapiNative
{
    [DllImport("Mmdevapi.dll", ExactSpelling = true, PreserveSig = false)]
    public static extern void ActivateAudioInterfaceAsync(
        [In, MarshalAs(UnmanagedType.LPWStr)] string deviceInterfacePath,
        [In, MarshalAs(UnmanagedType.LPStruct)] Guid riid,
        [In] IntPtr activationParams,
        [In] IActivateAudioInterfaceCompletionHandler completionHandler,
        out IActivateAudioInterfaceAsyncOperation activationOperation);
}
