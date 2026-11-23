package fr.kendix.replaymodsound.recording;

import com.replaymod.recording.ReplayModRecording;
import com.replaymod.recording.gui.GuiRecordingControls;
import com.replaymod.recording.handler.ConnectionEventHandler;
import com.replaymod.recording.packet.PacketListener;
import com.replaymod.replaystudio.replay.ReplayFile;
import fr.kendix.replaymodsound.ReplayModSound;
import fr.kendix.replaymodsound.capture.PerSourceCaptureService;
import fr.kendix.replaymodsound.config.ModConfig;
import fr.kendix.replaymodsound.mixin.PacketListenerAccessor;
import fr.kendix.replaymodsound.source.AudioSource;
import fr.kendix.replaymodsound.source.SourceRegistry;
import fr.kendix.replaymodsound.storage.McprMeta;
import fr.kendix.replaymodsound.storage.PendingSoundtrackRegistry;
import fr.kendix.replaymodsound.storage.ReplaySoundtrackIO;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RecordingLifecycle {
    private final ModConfig config;
    private final SourceRegistry sourceRegistry;
    private final PerSourceCaptureService captureService;
    private final PendingSoundtrackRegistry pendingRegistry;

    private PacketListener activeListener;
    private Path activeOutputPath;
    private ReplayFile activeReplayFile;
    private Boolean lastPaused;
    private boolean finalizeScheduled;

    public RecordingLifecycle(
            ModConfig config,
            SourceRegistry sourceRegistry,
            PerSourceCaptureService captureService,
            PendingSoundtrackRegistry pendingRegistry
    ) {
        this.config = config;
        this.sourceRegistry = sourceRegistry;
        this.captureService = captureService;
        this.pendingRegistry = pendingRegistry;
    }

    public void onRecordingStarted(PacketListener listener) {
        if (listener == null) {
            return;
        }
        if (listener != activeListener) {
            activeListener = listener;
            PacketListenerAccessor acc = (PacketListenerAccessor) listener;
            activeOutputPath = acc.replaymodsound$getOutputPath();
            activeReplayFile = acc.replaymodsound$getReplayFile();
            finalizeScheduled = false;
            lastPaused = null;
            ReplayModSound.LOGGER.info("Recording listener attached, output={}", activeOutputPath);
        }
        maybeStartCapture();
    }

    private void maybeStartCapture() {
        if (captureService.isRunning() || activeListener == null) {
            return;
        }
        GuiRecordingControls controls = findControls();
        if (controls != null && controls.isStopped()) {
            return;
        }
        Path workDir = FabricLoader.getInstance().getGameDir()
                .resolve("replaymodsound")
                .resolve("capture")
                .resolve(String.valueOf(System.currentTimeMillis()));

        List<AudioSource> enabled = sourceRegistry.enabled();
        if (enabled.isEmpty()) {
            chat("§e[RMS] Aucune source audio active (F7 pour configurer)");
            return;
        }
        captureService.start(enabled, workDir);
        int alive = captureService.activeCount();
        if (alive == 0) {
            chat("§c[RMS] Aucune capture ffmpeg/WASAPI active (F7)");
        } else {
            chat("§a[RMS] Capture audio: " + alive + " piste(s)");
        }
        if (controls != null && controls.isPaused()) {
            captureService.setPaused(true);
            lastPaused = true;
        }
    }

    public void onRecordingStopping(PacketListener listener) {
        if (finalizeScheduled) {
            return;
        }
        if (!captureService.isRunning()) {
            return;
        }
        Path out = activeOutputPath;
        finalizeCapture(out);
        activeListener = null;
        activeOutputPath = null;
        activeReplayFile = null;
        lastPaused = null;
    }

    public void tick() {
        syncFromConnectionHandler();
        if (activeListener == null) {
            return;
        }
        if (!captureService.isRunning()) {
            maybeStartCapture();
            return;
        }
        Boolean paused = readRecordingPaused();
        if (paused != null && (lastPaused == null || lastPaused != paused)) {
            captureService.setPaused(paused);
            lastPaused = paused;
        }
        if (isRecordingStopped()) {
            onRecordingStopping(activeListener);
        }
    }

    private void syncFromConnectionHandler() {
        try {
            if (ReplayModRecording.instance == null) {
                return;
            }
            ConnectionEventHandler handler = ReplayModRecording.instance.getConnectionEventHandler();
            if (handler == null) {
                return;
            }
            PacketListener listener = handler.getPacketListener();
            if (listener != null && listener != activeListener) {
                onRecordingStarted(listener);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Safe finalize: sidecar immediately, zip patch ONLY after ReplayMod wrote duration &gt; 0.
     * Never call ReplayFile.save() — that was freezing duration=0 and breaking Lunar.
     */
    private void finalizeCapture(Path outputPath) {
        if (finalizeScheduled) {
            return;
        }
        finalizeScheduled = true;

        Map<String, Path> files = captureService.stopAndCollect();
        if (files.isEmpty()) {
            chat("§c[RMS] Capture vide (check devices / F7)");
            return;
        }
        Map<String, AudioSource> byId = new HashMap<>();
        for (AudioSource s : sourceRegistry.enabled()) {
            byId.put(s.id(), s);
        }

        Path mcprHint = resolveMcprPath(outputPath);
        Path sidecar;
        try {
            sidecar = ReplaySoundtrackIO.writeSidecar(
                    mcprHint != null ? mcprHint : FabricLoader.getInstance().getGameDir().resolve("replaymodsound").resolve("last.mcpr"),
                    files,
                    byId
            );
            pendingRegistry.register(mcprHint, sidecar);
            chat("§a[RMS] Audio en sidecar: " + sidecar.getFileName() + " (patch .mcpr après save RM)");
        } catch (Exception e) {
            ReplayModSound.LOGGER.error("Sidecar write failed", e);
            chat("§c[RMS] Échec sidecar: " + e.getMessage());
            return;
        }

        final Path sidecarFinal = sidecar;
        final String stem = mcprHint != null ? stemOf(mcprHint.getFileName().toString()) : null;
        Thread t = new Thread(() -> {
            try {
                // Wait for ReplayMod to finish writing a COMPLETE mcpr (duration > 0)
                Path target = null;
                for (int i = 0; i < 60; i++) { // up to ~45s
                    Thread.sleep(750);
                    target = findCompletedMcpr(mcprHint, stem);
                    if (target != null) {
                        break;
                    }
                }
                if (target == null) {
                    chat("§e[RMS] .mcpr pas encore finalisé — audio OK en sidecar, patch plus tard à l'ouverture");
                    return;
                }
                if (ReplaySoundtrackIO.readManifestFromZip(target).isPresent()) {
                    chat("§a[RMS] Audio déjà dans " + target.getFileName());
                    return;
                }
                ReplaySoundtrackIO.writeIntoMcprZip(target, files, byId);
                // Sidecar next to final name
                Path destSide = ReplaySoundtrackIO.sidecarDir(target);
                if (!Files.isDirectory(destSide)) {
                    Files.createDirectories(destSide);
                    try (var stream = Files.list(sidecarFinal)) {
                        for (Path p : stream.toList()) {
                            if (Files.isRegularFile(p)) {
                                Files.copy(p, destSide.resolve(p.getFileName().toString()));
                            }
                        }
                    }
                }
                pendingRegistry.register(target, destSide);
                int dur = McprMeta.readDurationMs(target);
                chat("§a[RMS] Audio patché dans " + target.getFileName() + " (durée " + dur + "ms)");
            } catch (Exception e) {
                ReplayModSound.LOGGER.error("Finalize capture failed", e);
                chat("§c[RMS] Patch .mcpr échoué — sidecar utilisable");
            }
        }, "rms-finalize");
        t.setDaemon(true);
        t.start();
    }

    private Path findCompletedMcpr(Path hint, String stem) {
        // Prefer hint / raw sibling once complete
        if (hint != null) {
            Path raw = hint;
            if (hint.getParent() != null && !"raw".equalsIgnoreCase(hint.getParent().getFileName().toString())) {
                Path maybe = hint.getParent().resolve("raw").resolve(hint.getFileName());
                if (Files.isRegularFile(maybe)) {
                    raw = maybe;
                }
            }
            if (McprMeta.isComplete(raw)) {
                return raw;
            }
            if (McprMeta.isComplete(hint)) {
                return hint;
            }
        }
        // Renamed final in recordings root with same stem or newest complete
        Path recordings = FabricLoader.getInstance().getGameDir().resolve("replay_recordings");
        if (!Files.isDirectory(recordings)) {
            return null;
        }
        try (var stream = Files.list(recordings)) {
            var list = stream
                    .filter(p -> p.getFileName().toString().endsWith(".mcpr"))
                    .filter(Files::isRegularFile)
                    .toList();
            if (stem != null) {
                for (Path p : list) {
                    if (stemOf(p.getFileName().toString()).equalsIgnoreCase(stem) && McprMeta.isComplete(p)) {
                        return p;
                    }
                }
            }
            // Newest complete only (never touch duration=0)
            Path best = null;
            long bestTime = -1;
            for (Path p : list) {
                if (!McprMeta.isComplete(p)) {
                    continue;
                }
                long t = Files.getLastModifiedTime(p).toMillis();
                if (t > bestTime) {
                    bestTime = t;
                    best = p;
                }
            }
            // Only accept if recent (< 2 min) to avoid patching old replays
            if (best != null && System.currentTimeMillis() - bestTime < 120_000) {
                return best;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Path resolveMcprPath(Path outputPath) {
        if (outputPath == null) {
            return null;
        }
        if (Files.isRegularFile(outputPath)) {
            return outputPath;
        }
        String name = outputPath.getFileName().toString();
        if (!name.endsWith(".mcpr")) {
            Path withExt = outputPath.resolveSibling(name + ".mcpr");
            if (Files.isRegularFile(withExt)) {
                return withExt;
            }
        }
        return outputPath;
    }

    private static String stemOf(String name) {
        return name.endsWith(".mcpr") ? name.substring(0, name.length() - 5) : name;
    }

    private Boolean readRecordingPaused() {
        GuiRecordingControls controls = findControls();
        return controls == null ? null : controls.isPaused();
    }

    private boolean isRecordingStopped() {
        GuiRecordingControls controls = findControls();
        return controls != null && controls.isStopped();
    }

    private GuiRecordingControls findControls() {
        try {
            ConnectionEventHandler handler = ReplayModRecording.instance.getConnectionEventHandler();
            if (handler == null) {
                return null;
            }
            Field field = ConnectionEventHandler.class.getDeclaredField("guiControls");
            field.setAccessible(true);
            return (GuiRecordingControls) field.get(handler);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void chat(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.player != null) {
            client.execute(() -> {
                if (client.player != null) {
                    client.player.sendMessage(Text.literal(message), false);
                }
            });
        }
        System.out.println(message);
    }
}
