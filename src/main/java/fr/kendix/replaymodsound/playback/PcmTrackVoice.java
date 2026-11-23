package fr.kendix.replaymodsound.playback;

import fr.kendix.replaymodsound.ReplayModSound;
import fr.kendix.replaymodsound.capture.FfmpegLocator;
import fr.kendix.replaymodsound.storage.SoundtrackEntry;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pump PCM into a SourceDataLine. Pause = stop writing + flush hardware buffer
 * (unlike Clip.stop(), which often leaves Windows buffer audible).
 */
final class PcmTrackVoice {
    private final SoundtrackEntry entry;
    private final AudioFormat format;
    private final byte[] pcm;
    private final int bytesPerMs;
    private final SourceDataLine line;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean paused = new AtomicBoolean(true);
    private final AtomicInteger playHead = new AtomicInteger(0);
    private final Thread pump;

    private PcmTrackVoice(SoundtrackEntry entry, AudioFormat format, byte[] pcm, SourceDataLine line) {
        this.entry = entry;
        this.format = format;
        this.pcm = pcm;
        this.bytesPerMs = Math.max(1, (int) (format.getFrameRate() * format.getFrameSize() / 1000.0));
        this.line = line;
        this.pump = new Thread(this::pumpLoop, "rms-pcm-" + entry.id);
        this.pump.setDaemon(true);
        this.pump.start();
    }

    static PcmTrackVoice open(SoundtrackEntry entry, Path audioFile) {
        try {
            Path wav = ensureWav(entry, audioFile);
            if (wav == null) {
                return null;
            }
            try (AudioInputStream raw = AudioSystem.getAudioInputStream(wav.toFile())) {
                AudioFormat base = raw.getFormat();
                AudioFormat pcmFmt = new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        base.getSampleRate() > 0 ? base.getSampleRate() : 48000f,
                        16,
                        base.getChannels() > 0 ? base.getChannels() : 2,
                        (base.getChannels() > 0 ? base.getChannels() : 2) * 2,
                        base.getSampleRate() > 0 ? base.getSampleRate() : 48000f,
                        false
                );
                AudioInputStream decoded = AudioSystem.isConversionSupported(pcmFmt, base)
                        ? AudioSystem.getAudioInputStream(pcmFmt, raw)
                        : raw;
                ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(1 << 20, (int) Files.size(wav)));
                byte[] buf = new byte[8192];
                int n;
                while ((n = decoded.read(buf)) >= 0) {
                    if (n > 0) {
                        bos.write(buf, 0, n);
                    }
                }
                byte[] data = bos.toByteArray();
                if (data.length == 0) {
                    return null;
                }
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, decoded.getFormat());
                SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
                // Small buffer → less residual audio after flush on pause
                int bufferBytes = (int) (decoded.getFormat().getFrameRate() * decoded.getFormat().getFrameSize() * 0.05);
                bufferBytes = Math.max(decoded.getFormat().getFrameSize() * 64, bufferBytes);
                line.open(decoded.getFormat(), bufferBytes);
                return new PcmTrackVoice(entry, decoded.getFormat(), data, line);
            }
        } catch (Exception e) {
            ReplayModSound.LOGGER.warn("Cannot open PCM voice for {}", entry.id, e);
            return null;
        }
    }

    void pauseHard() {
        paused.set(true);
        try {
            if (line.isRunning()) {
                line.stop();
            }
            line.flush(); // discard queued samples — critical on Windows
            silenceGain(true);
        } catch (Exception e) {
            ReplayModSound.LOGGER.debug("pauseHard {}", entry.id, e);
        }
    }

    void seekAndPlay(int replayMs) {
        long targetMs = Math.max(0, replayMs + entry.offsetMs);
        int pos = (int) Math.min(pcm.length, targetMs * bytesPerMs);
        // align to frame
        int frame = format.getFrameSize();
        if (frame > 1) {
            pos -= pos % frame;
        }
        playHead.set(Math.max(0, pos));
        applyGain();
        if (entry.muted) {
            pauseHard();
            return;
        }
        paused.set(false);
        try {
            if (!line.isActive()) {
                line.start();
            }
        } catch (Exception ignored) {
        }
    }

    void ensurePlayingAt(int replayMs) {
        if (entry.muted) {
            pauseHard();
            return;
        }
        if (paused.get()) {
            seekAndPlay(replayMs);
            return;
        }
        applyGain();
        try {
            if (!line.isRunning()) {
                line.start();
            }
        } catch (Exception ignored) {
        }
    }

    public void close() {
        running.set(false);
        paused.set(true);
        try {
            line.stop();
            line.flush();
            line.close();
        } catch (Exception ignored) {
        }
        try {
            pump.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void pumpLoop() {
        // ~10ms chunks
        int chunk = Math.max(format.getFrameSize() * 8, bytesPerMs * 10);
        chunk -= chunk % format.getFrameSize();
        byte[] silence = new byte[chunk];

        while (running.get()) {
            if (paused.get() || entry.muted) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }

            int head = playHead.get();
            if (head >= pcm.length) {
                pauseHard();
                continue;
            }

            int toWrite = Math.min(chunk, pcm.length - head);
            toWrite -= toWrite % format.getFrameSize();
            if (toWrite <= 0) {
                pauseHard();
                continue;
            }

            try {
                if (line.available() < toWrite) {
                    Thread.sleep(2);
                    continue;
                }
                float vol = entry.muted ? 0f : Math.max(0f, Math.min(2f, entry.volume));
                byte[] out = pcm;
                if (Math.abs(vol - 1f) > 0.01f) {
                    out = scalePcm16(pcm, head, toWrite, vol);
                    int written = line.write(out, 0, toWrite);
                    if (written > 0) {
                        playHead.addAndGet(written);
                    } else {
                        Thread.sleep(2);
                    }
                } else {
                    int written = line.write(pcm, head, toWrite);
                    if (written > 0) {
                        playHead.addAndGet(written);
                    } else {
                        Thread.sleep(2);
                    }
                }
            } catch (Exception e) {
                if (!running.get()) {
                    return;
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        // keep silence unused warning away
        if (silence.length == 0) {
            // no-op
        }
    }

    private void applyGain() {
        // Volume is applied in software (supports 200%); keep hardware at unity / mute only
        silenceGain(entry.muted || entry.volume <= 0.001f);
        if (entry.muted || entry.volume <= 0.001f) {
            return;
        }
        try {
            if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
                gain.setValue(0f); // 0 dB — scaling done in pump
            }
        } catch (Exception ignored) {
        }
    }

    private void silenceGain(boolean silent) {
        try {
            if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
                if (silent) {
                    gain.setValue(gain.getMinimum());
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static byte[] scalePcm16(byte[] src, int off, int len, float vol) {
        byte[] out = new byte[len];
        for (int i = 0; i + 1 < len; i += 2) {
            int sample = (src[off + i] & 0xFF) | (src[off + i + 1] << 8);
            // signed 16-bit little-endian
            short s = (short) sample;
            int scaled = Math.round(s * vol);
            if (scaled > Short.MAX_VALUE) scaled = Short.MAX_VALUE;
            if (scaled < Short.MIN_VALUE) scaled = Short.MIN_VALUE;
            out[i] = (byte) (scaled & 0xFF);
            out[i + 1] = (byte) ((scaled >> 8) & 0xFF);
        }
        return out;
    }

    private static Path ensureWav(SoundtrackEntry entry, Path audioFile) {
        try {
            // Always normalize via ffmpeg → pcm_s16le (WASAPI often writes float32 that Java plays as silence)
            Path wav = audioFile.resolveSibling(entry.id + "_play.wav");
            if (Files.isRegularFile(wav) && Files.size(wav) > 0
                    && Files.getLastModifiedTime(wav).compareTo(Files.getLastModifiedTime(audioFile)) >= 0
                    && looksLikePcm16(wav)) {
                return wav;
            }
            String ffmpeg = FfmpegLocator.resolve(ReplayModSound.get().getConfig());
            ProcessBuilder pb = new ProcessBuilder(
                    ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
                    "-i", audioFile.toAbsolutePath().toString(),
                    "-acodec", "pcm_s16le",
                    "-ac", "2", "-ar", "48000",
                    wav.toAbsolutePath().toString()
            );
            Process p = pb.start();
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() == 0 && Files.isRegularFile(wav) && Files.size(wav) > 0) {
                ReplayModSound.LOGGER.info("Normalized {} → {}", audioFile.getFileName(), wav.getFileName());
                return wav;
            }
        } catch (Exception e) {
            ReplayModSound.LOGGER.warn("ensureWav failed {}", entry.id, e);
        }
        return null;
    }

    /** Quick header sniff: PCM 16-bit (audio format 1) vs float (3). */
    private static boolean looksLikePcm16(Path wav) {
        try (var in = Files.newInputStream(wav)) {
            byte[] hdr = in.readNBytes(44);
            if (hdr.length < 36) {
                return false;
            }
            // "fmt " chunk audio format at offset 20 (typical PCM wav)
            int fmt = (hdr[20] & 0xFF) | ((hdr[21] & 0xFF) << 8);
            int bits = (hdr[34] & 0xFF) | ((hdr[35] & 0xFF) << 8);
            return fmt == 1 && bits == 16;
        } catch (Exception e) {
            return false;
        }
    }
}
