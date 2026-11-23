package fr.kendix.replaymodsound.playback;

import com.replaymod.replay.ReplayHandler;
import com.replaymod.replay.ReplayModReplay;
import com.replaymod.replay.ReplaySender;
import com.replaymod.replaystudio.replay.ReplayFile;
import fr.kendix.replaymodsound.ReplayModSound;
import fr.kendix.replaymodsound.capture.FfmpegLocator;
import fr.kendix.replaymodsound.storage.PendingSoundtrackRegistry;
import fr.kendix.replaymodsound.storage.ReplaySoundtrackIO;
import fr.kendix.replaymodsound.storage.SoundtrackEntry;
import fr.kendix.replaymodsound.storage.SoundtrackManifest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class MultiTrackSoundtrackPlayer {
    private final PendingSoundtrackRegistry pendingRegistry;

    private SoundtrackManifest manifest;
    private Path extractDir;
    private Path boundMcprPath;
    private Path rememberedMcpr;
    private ReplayFile boundReplayFile;
    private final List<PcmTrackVoice> voices = new ArrayList<>();
    private boolean loaded;
    private boolean loading;
    private boolean loadFailed;
    private int lastTimestamp = -1;
    private boolean wasPaused = true;
    private int frozenTicks;
    private Object lastHandler;
    /** Forced by ReplayMod setReplaySpeed(0) — instant, no wait for tick. */
    private volatile boolean forcePaused;

    public MultiTrackSoundtrackPlayer(PendingSoundtrackRegistry pendingRegistry) {
        this.pendingRegistry = pendingRegistry;
    }

    public boolean hasTracks() {
        return loaded && manifest != null && !manifest.sources.isEmpty();
    }

    public boolean isLoading() {
        return loading;
    }

    public SoundtrackManifest getManifest() {
        return manifest;
    }

    public Path getExtractDir() {
        return extractDir;
    }

    public ReplayFile getBoundReplayFile() {
        return boundReplayFile;
    }

    public synchronized void rememberMcpr(Path mcprPath) {
        this.rememberedMcpr = mcprPath;
        pendingRegistry.attachToFinalMcpr(mcprPath);
    }

    public synchronized void onReplayOpened(ReplayFile replayFile) {
        Path mcpr = rememberedMcpr;
        if (mcpr != null) {
            pendingRegistry.attachToFinalMcpr(mcpr);
        }
        startLoad(replayFile, mcpr);
    }

    public synchronized void loadFromReplay(ReplayFile replayFile) {
        try {
            loadInternal(replayFile, rememberedMcpr);
        } catch (Exception e) {
            ReplayModSound.LOGGER.error("loadFromReplay failed", e);
        }
    }

    public synchronized void loadFromMcprPath(Path mcprPath) {
        try {
            loadInternal(null, mcprPath);
        } catch (Exception e) {
            ReplayModSound.LOGGER.error("loadFromMcprPath failed", e);
        }
    }

    private void startLoad(ReplayFile replayFile, Path mcpr) {
        if (loading) {
            return;
        }
        Thread loader = new Thread(() -> {
            try {
                loadInternal(replayFile, mcpr);
            } catch (Exception e) {
                ReplayModSound.LOGGER.error("soundtrack load failed", e);
            }
        }, "rms-load");
        loader.setDaemon(true);
        loader.start();
    }

    private synchronized void loadInternal(ReplayFile replayFile, Path mcprPath) throws IOException {
        clearVoicesOnly();
        loading = true;
        loadFailed = false;
        try {
            if (mcprPath != null) {
                pendingRegistry.attachToFinalMcpr(mcprPath);
            }

            Optional<SoundtrackManifest> opt = Optional.empty();
            Path sidecarOrRaw = null;

            if (replayFile != null) {
                opt = ReplaySoundtrackIO.readManifest(replayFile);
            }
            if (opt.isEmpty() && mcprPath != null) {
                opt = ReplaySoundtrackIO.readManifestFromZip(mcprPath);
            }
            if (opt.isEmpty() && mcprPath != null) {
                Optional<Path> nearby = pendingRegistry.findSidecarNearby(mcprPath);
                if (nearby.isPresent()) {
                    sidecarOrRaw = nearby.get();
                    if (Files.isDirectory(sidecarOrRaw)) {
                        Path man = sidecarOrRaw.resolve("manifest.json");
                        if (Files.isRegularFile(man)) {
                            try (var reader = Files.newBufferedReader(man)) {
                                opt = Optional.ofNullable(new com.google.gson.Gson().fromJson(reader, SoundtrackManifest.class));
                            }
                        }
                    } else if (Files.isRegularFile(sidecarOrRaw)) {
                        opt = ReplaySoundtrackIO.readManifestFromZip(sidecarOrRaw);
                    }
                }
            }

            if (opt.isEmpty()) {
                loadFailed = true;
                ReplayModSound.LOGGER.info("Replay has no soundtracks (zip or sidecar) mcpr={}", mcprPath);
                chatOnce("§e[RMS] Pas de pistes audio sur ce replay");
                return;
            }

            this.manifest = opt.get();
            this.boundReplayFile = replayFile;
            this.boundMcprPath = mcprPath;
            this.extractDir = FabricLoader.getInstance().getGameDir()
                    .resolve("replaymodsound")
                    .resolve("playback")
                    .resolve(String.valueOf(System.currentTimeMillis()));
            Files.createDirectories(extractDir);

            boolean extracted = false;
            if (sidecarOrRaw != null && Files.isDirectory(sidecarOrRaw)) {
                for (SoundtrackEntry entry : manifest.sources) {
                    Path in = sidecarOrRaw.resolve(entry.file);
                    if (Files.isRegularFile(in)) {
                        Files.copy(in, extractDir.resolve(entry.file), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        extracted = true;
                    }
                }
            } else if (sidecarOrRaw != null && Files.isRegularFile(sidecarOrRaw)) {
                extractFromZip(sidecarOrRaw, manifest, extractDir);
                extracted = true;
            } else if (mcprPath != null && Files.isRegularFile(mcprPath)) {
                extractFromZip(mcprPath, manifest, extractDir);
                extracted = true;
            } else if (replayFile != null) {
                ReplaySoundtrackIO.extractTracks(replayFile, manifest, extractDir);
                extracted = true;
            }

            if (!extracted) {
                loadFailed = true;
                chatOnce("§c[RMS] Impossible d'extraire les pistes");
                return;
            }

            for (SoundtrackEntry entry : manifest.sources) {
                if (entry.id != null && "spotify".equalsIgnoreCase(entry.id)) {
                    continue; // plus de piste Spotify séparée
                }
                Path audio = extractDir.resolve(entry.file);
                if (!Files.isRegularFile(audio) || Files.size(audio) <= 0) {
                    continue;
                }
                PcmTrackVoice voice = PcmTrackVoice.open(entry, audio);
                if (voice != null) {
                    voices.add(voice);
                }
            }
            loaded = !voices.isEmpty();
            loadFailed = !loaded;
            chatOnce(loaded
                    ? "§a[RMS] " + voices.size() + " piste(s) audio prêtes (F6 / bouton Audio)"
                    : "§c[RMS] Manifest trouvé mais fichiers audio illisibles");
        } finally {
            loading = false;
        }
    }

    private static void extractFromZip(Path mcpr, SoundtrackManifest manifest, Path dest) throws IOException {
        try (ZipFile zip = new ZipFile(mcpr.toFile())) {
            for (SoundtrackEntry entry : manifest.sources) {
                ZipEntry ze = zip.getEntry(SoundtrackManifest.DIR + entry.file);
                if (ze == null) continue;
                try (var in = zip.getInputStream(ze)) {
                    Files.copy(in, dest.resolve(entry.file), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void clearVoicesOnly() {
        for (PcmTrackVoice voice : voices) {
            voice.close();
        }
        voices.clear();
        manifest = null;
        boundReplayFile = null;
        boundMcprPath = null;
        loaded = false;
        lastTimestamp = -1;
        wasPaused = true;
        frozenTicks = 0;
        forcePaused = true;
        if (extractDir != null) {
            try {
                deleteRecursive(extractDir);
            } catch (IOException ignored) {
            }
            extractDir = null;
        }
    }

    public synchronized void unload() {
        clearVoicesOnly();
        loading = false;
        loadFailed = false;
        lastHandler = null;
        rememberedMcpr = null;
    }

    public synchronized void tick() {
        ReplayHandler handler;
        try {
            handler = ReplayModReplay.instance == null ? null : ReplayModReplay.instance.getReplayHandler();
        } catch (Throwable t) {
            return;
        }

        if (handler == null) {
            if (loaded || loadFailed) {
                unload();
            }
            return;
        }

        if (!loaded && !loading && !loadFailed && handler != lastHandler) {
            lastHandler = handler;
            Path mcpr = rememberedMcpr;
            startLoad(handler.getReplayFile(), mcpr);
            return;
        }

        if (!loaded) {
            return;
        }

        ReplaySender sender = handler.getReplaySender();
        if (sender == null) {
            return;
        }
        int timestamp = sender.currentTimeStamp();
        boolean paused = forcePaused;
        try {
            if (sender.paused()) {
                paused = true;
            }
        } catch (Throwable ignored) {
            paused = true;
        }
        try {
            // ReplayMod sets speed to 0 on pause; catch it even if paused() lies
            if (sender.getReplaySpeed() <= 0.0001) {
                paused = true;
            }
        } catch (Throwable ignored) {
        }
        if (!paused && lastTimestamp >= 0 && timestamp == lastTimestamp && !wasPaused) {
            frozenTicks++;
            if (frozenTicks >= 1) {
                paused = true;
            }
        } else if (timestamp != lastTimestamp) {
            frozenTicks = 0;
        }

        if (paused) {
            hardPauseAll();
            wasPaused = true;
            lastTimestamp = timestamp;
            return;
        }

        boolean seekNeeded = Math.abs(timestamp - lastTimestamp) > 80 || wasPaused;
        if (seekNeeded) {
            for (PcmTrackVoice voice : voices) {
                voice.seekAndPlay(timestamp);
            }
        } else {
            for (PcmTrackVoice voice : voices) {
                voice.ensurePlayingAt(timestamp);
            }
        }
        wasPaused = false;
        lastTimestamp = timestamp;
    }

    /** Called immediately from ReplayMod setReplaySpeed mixin. */
    public void onReplaySpeedChanged(double speed) {
        if (speed <= 0.0001) {
            forcePaused = true;
            hardPauseAll();
        } else {
            forcePaused = false;
        }
    }

    private void hardPauseAll() {
        for (PcmTrackVoice voice : voices) {
            voice.pauseHard();
        }
    }

    public synchronized void persistManifestEdits() {
        if (manifest == null) {
            return;
        }
        try {
            if (boundReplayFile != null) {
                ReplaySoundtrackIO.saveManifest(boundReplayFile, manifest);
            }
            if (boundMcprPath != null) {
                Path dir = ReplaySoundtrackIO.sidecarDir(boundMcprPath);
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("manifest.json"),
                        new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(manifest));
            }
        } catch (Exception e) {
            ReplayModSound.LOGGER.warn("Could not save soundtrack manifest edits", e);
        }
    }

    public synchronized Path exportMix(Path outFile) throws Exception {
        if (manifest == null || extractDir == null) {
            throw new IOException("No tracks loaded");
        }
        String ffmpeg = FfmpegLocator.resolve(ReplayModSound.get().getConfig());
        return ReplaySoundtrackIO.exportMixedAudio(extractDir, manifest, outFile, () -> ffmpeg);
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    private static long lastChatMs;

    private static void chatOnce(String message) {
        long now = System.currentTimeMillis();
        if (now - lastChatMs < 2000 && message.contains("Pas de pistes")) {
            return;
        }
        lastChatMs = now;
        System.out.println(message);
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            client.execute(() -> {
                if (client.player != null) {
                    client.player.sendMessage(Text.literal(message), false);
                }
            });
        }
    }
}
