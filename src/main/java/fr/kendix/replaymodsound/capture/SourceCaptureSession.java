package fr.kendix.replaymodsound.capture;

import fr.kendix.replaymodsound.ReplayModSound;
import fr.kendix.replaymodsound.config.ModConfig;
import fr.kendix.replaymodsound.source.AudioSource;
import fr.kendix.replaymodsound.source.AudioSourceType;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class SourceCaptureSession {
    private final AudioSource source;
    private final Path outputFile;
    private final ModConfig config;
    private Process process;
    private boolean paused;
    private boolean usingWasapiHelper;

    public SourceCaptureSession(AudioSource source, Path outputFile, ModConfig config) {
        this.source = source;
        this.outputFile = outputFile;
        this.config = config;
    }

    public AudioSource source() {
        return source;
    }

    public Path outputFile() {
        return outputFile;
    }

    public void start() throws IOException {
        Files.createDirectories(outputFile.getParent());
        if (Files.exists(outputFile)) {
            Files.delete(outputFile);
        }
        List<String> cmd = buildCommandTo(outputFile);
        ReplayModSound.LOGGER.info("Starting capture {}: {}", source.id(), String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectError(ProcessBuilder.Redirect.PIPE);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        process = pb.start();
        Thread drain = new Thread(() -> {
            try {
                process.getErrorStream().transferTo(OutputStream.nullOutputStream());
            } catch (IOException ignored) {
            }
        }, "rms-cap-err-" + source.id());
        drain.setDaemon(true);
        drain.start();
        paused = false;
    }

    public void pause() {
        if (process == null || !process.isAlive() || paused) {
            return;
        }
        stopProcess();
        paused = true;
    }

    public void resume() throws IOException {
        if (!paused) {
            return;
        }
        Path segment = siblingSegment(outputFile);
        List<String> cmd = buildCommandTo(segment);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectError(ProcessBuilder.Redirect.PIPE);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);
        process = pb.start();
        Thread drain = new Thread(() -> {
            try {
                process.getErrorStream().transferTo(OutputStream.nullOutputStream());
            } catch (IOException ignored) {
            }
        }, "rms-cap-err-" + source.id());
        drain.setDaemon(true);
        drain.start();
        paused = false;
    }

    public void stop() {
        stopProcess();
        paused = false;
    }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    private void stopProcess() {
        if (process == null) {
            return;
        }
        try {
            OutputStream stdin = process.getOutputStream();
            stdin.write('q');
            stdin.write('\n');
            stdin.flush();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
        } catch (Exception e) {
            ReplayModSound.LOGGER.warn("capture stop failed for {}, forcing", source.id(), e);
            process.destroyForcibly();
        } finally {
            process = null;
        }
    }

    private List<String> buildCommandTo(Path out) {
        List<String> cmd = new ArrayList<>();
        String device = source.deviceName();
        usingWasapiHelper = source.type() == AudioSourceType.LOOPBACK
                && device != null
                && (device.equalsIgnoreCase("wasapi-native")
                || device.toLowerCase().startsWith("wasapi-process:")
                || device.toLowerCase().startsWith("wasapi-exclude:"));

        if (usingWasapiHelper) {
            Path helper = WasapiLoopbackLocator.resolve(config);
            if (helper == null) {
                throw new IllegalStateException("WasapiLoopback.exe introuvable");
            }
            cmd.add(helper.toAbsolutePath().toString());
            cmd.add(ensureWav(out).toAbsolutePath().toString());
            if (device.toLowerCase().startsWith("wasapi-process:")) {
                cmd.add("--include");
                cmd.add(device.substring("wasapi-process:".length()).trim());
            } else if (device.toLowerCase().startsWith("wasapi-exclude:")) {
                cmd.add("--exclude");
                cmd.add(device.substring("wasapi-exclude:".length()).trim());
            }
            return cmd;
        }

        String ffmpeg = FfmpegLocator.resolve(config);
        cmd.add(ffmpeg);
        cmd.add("-y");
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("error");
        cmd.add("-f");
        cmd.add("dshow");
        cmd.add("-i");
        cmd.add("audio=" + source.deviceName());
        cmd.add("-c:a");
        cmd.add("libopus");
        cmd.add("-b:a");
        cmd.add(String.valueOf(config.audioBitrate));
        cmd.add("-ac");
        cmd.add("2");
        cmd.add("-ar");
        cmd.add("48000");
        cmd.add("-flush_packets");
        cmd.add("1");
        cmd.add(out.toAbsolutePath().toString());
        return cmd;
    }

    private static Path ensureWav(Path out) {
        String name = out.getFileName().toString();
        if (name.endsWith(".wav")) {
            return out;
        }
        if (name.endsWith(".ogg")) {
            return out.resolveSibling(name.substring(0, name.length() - 4) + ".wav");
        }
        return out.resolveSibling(name + ".wav");
    }

    static Path siblingSegment(Path primary) {
        String name = primary.getFileName().toString();
        String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : ".ogg";
        int i = 1;
        Path dir = primary.getParent();
        Path candidate;
        do {
            candidate = dir.resolve(base + "_seg" + i + ext);
            i++;
        } while (Files.exists(candidate));
        return candidate;
    }
}
