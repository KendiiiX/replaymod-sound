package fr.kendix.replaymodsound.capture;

import fr.kendix.replaymodsound.ReplayModSound;
import fr.kendix.replaymodsound.config.ModConfig;
import fr.kendix.replaymodsound.source.AudioSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class PerSourceCaptureService {
    private final ModConfig config;
    private final Map<String, SourceCaptureSession> sessions = new LinkedHashMap<>();
    private Path workDir;
    private boolean running;
    private boolean paused;

    public PerSourceCaptureService(ModConfig config) {
        this.config = config;
    }

    public synchronized void start(List<AudioSource> sources, Path workDir) {
        stopInternal(false);
        this.workDir = workDir;
        try {
            Files.createDirectories(workDir);
        } catch (IOException e) {
            ReplayModSound.LOGGER.error("Cannot create capture dir {}", workDir, e);
            return;
        }
        for (AudioSource source : sources) {
            String device = source.deviceName() == null ? "" : source.deviceName().toLowerCase();
            boolean wav = device.equals("wasapi-native")
                    || device.startsWith("wasapi-process:")
                    || device.startsWith("wasapi-exclude:");
            Path out = workDir.resolve(source.id() + (wav ? ".wav" : ".ogg"));
            SourceCaptureSession session = new SourceCaptureSession(source, out, config);
            try {
                session.start();
                sessions.put(source.id(), session);
            } catch (IOException e) {
                ReplayModSound.LOGGER.error("Failed to start capture for {}", source.id(), e);
            }
        }
        // Detect ffmpeg that exits immediately (bad device like OBS-Audio without OBS)
        try {
            Thread.sleep(700);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        sessions.entrySet().removeIf(e -> {
            if (!e.getValue().isAlive()) {
                ReplayModSound.LOGGER.error("Capture died immediately for {} (device invalid?)", e.getKey());
                return true;
            }
            return false;
        });
        running = !sessions.isEmpty();
        paused = false;
        ReplayModSound.LOGGER.info("Capture started for {} source(s) in {}", sessions.size(), workDir);
    }

    public synchronized void setPaused(boolean pause) {
        if (!running || this.paused == pause) {
            return;
        }
        this.paused = pause;
        for (SourceCaptureSession session : sessions.values()) {
            try {
                if (pause) {
                    session.pause();
                } else {
                    session.resume();
                }
            } catch (IOException e) {
                ReplayModSound.LOGGER.warn("Pause/resume failed for {}", session.source().id(), e);
            }
        }
    }

    public synchronized Map<String, Path> stopAndCollect() {
        Map<String, Path> files = new LinkedHashMap<>();
        for (SourceCaptureSession session : sessions.values()) {
            session.stop();
            Path primary = session.outputFile();
            stitchSegments(primary);
            if (Files.isRegularFile(primary) && fileSize(primary) > 0) {
                files.put(session.source().id(), primary);
            }
        }
        sessions.clear();
        running = false;
        paused = false;
        return files;
    }

    public synchronized boolean isRunning() {
        return running;
    }

    public synchronized boolean isPaused() {
        return paused;
    }

    public synchronized int activeCount() {
        return sessions.size();
    }

    public synchronized Path getWorkDir() {
        return workDir;
    }

    private void stopInternal(boolean collect) {
        if (!sessions.isEmpty()) {
            if (collect) {
                stopAndCollect();
            } else {
                for (SourceCaptureSession session : sessions.values()) {
                    session.stop();
                }
                sessions.clear();
            }
        }
        running = false;
        paused = false;
    }

    private void stitchSegments(Path primary) {
        if (workDir == null) {
            return;
        }
        String base = primary.getFileName().toString().replace(".ogg", "");
        List<Path> segments = new ArrayList<>();
        if (Files.isRegularFile(primary) && fileSize(primary) > 0) {
            segments.add(primary);
        }
        try (var stream = Files.list(workDir)) {
            stream.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith(base + "_seg") && n.endsWith(".ogg");
                    })
                    .sorted()
                    .forEach(segments::add);
        } catch (IOException e) {
            ReplayModSound.LOGGER.warn("Segment list failed for {}", base, e);
            return;
        }
        if (segments.size() <= 1) {
            return;
        }
        try {
            Path listFile = workDir.resolve(base + "_concat.txt");
            StringBuilder sb = new StringBuilder();
            for (Path seg : segments) {
                sb.append("file '").append(seg.toAbsolutePath().toString().replace("\\", "/")).append("'\n");
            }
            Files.writeString(listFile, sb.toString());
            Path stitched = workDir.resolve(base + "_stitched.ogg");
            String ffmpeg = FfmpegLocator.resolve(config);
            ProcessBuilder pb = new ProcessBuilder(
                    ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
                    "-f", "concat", "-safe", "0", "-i", listFile.toAbsolutePath().toString(),
                    "-c", "copy", stitched.toAbsolutePath().toString()
            );
            Process p = pb.start();
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return;
            }
            if (p.exitValue() == 0 && Files.isRegularFile(stitched)) {
                Files.deleteIfExists(primary);
                Files.move(stitched, primary);
            }
        } catch (Exception e) {
            ReplayModSound.LOGGER.warn("Failed to stitch segments for {}", base, e);
        }
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }
}
