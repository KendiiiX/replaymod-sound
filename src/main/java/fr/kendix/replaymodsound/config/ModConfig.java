package fr.kendix.replaymodsound.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import fr.kendix.replaymodsound.ReplayModSound;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

public final class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public String ffmpegPath = "ffmpeg";
    /** Optional path to WasapiLoopback.exe (PC desktop capture) */
    public String wasapiLoopbackPath = "";
    public int audioBitrate = 128000;

    /** Piste 1 — son PC (tout le desktop, Spotify inclus) */
    public boolean capturePc = true;
    /** Device loopback id (ex. desktop_wasapi_default) — vide = auto */
    public String selectedPcDeviceId = "";

    /** Piste 2 — micro */
    public boolean captureMic = true;
    /** Device micro id (ex. mic_microphone_tonor_…) — vide = premier micro */
    public String selectedMicDeviceId = "";

    /** Legacy — plus utilisé (Spotify n'est plus une piste séparée) */
    public boolean captureSpotify = false;
    public String spotifyProcessName = "Spotify";

    // legacy (migrated on load)
    public Set<String> enabledSourceIds = new LinkedHashSet<>();
    public boolean autoEnableDefaultMic = true;
    public boolean autoEnableDefaultLoopback = true;

    public static ModConfig load() {
        Path path = configPath();
        if (Files.isRegularFile(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                ModConfig loaded = GSON.fromJson(reader, ModConfig.class);
                if (loaded != null) {
                    if (loaded.enabledSourceIds == null) {
                        loaded.enabledSourceIds = new LinkedHashSet<>();
                    }
                    loaded.migrateLegacy();
                    return loaded;
                }
            } catch (Exception e) {
                ReplayModSound.LOGGER.warn("Failed to load config, using defaults", e);
            }
        }
        return new ModConfig();
    }

    private void migrateLegacy() {
        if ((selectedMicDeviceId == null || selectedMicDeviceId.isBlank()) && enabledSourceIds != null) {
            for (String id : enabledSourceIds) {
                if (id.startsWith("mic_")) {
                    selectedMicDeviceId = id;
                    captureMic = true;
                    break;
                }
            }
        }
        if ((selectedPcDeviceId == null || selectedPcDeviceId.isBlank()) && enabledSourceIds != null) {
            for (String id : enabledSourceIds) {
                if (id.startsWith("desktop_")) {
                    selectedPcDeviceId = id;
                    capturePc = true;
                    break;
                }
            }
        }
    }

    public void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            ReplayModSound.LOGGER.error("Failed to save config", e);
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("replaymodsound.json");
    }
}
