package fr.kendix.replaymodsound.capture;

import fr.kendix.replaymodsound.config.ModConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

public final class WasapiLoopbackLocator {
    private WasapiLoopbackLocator() {
    }

    public static Path resolve(ModConfig config) {
        if (config.wasapiLoopbackPath != null && !config.wasapiLoopbackPath.isBlank()) {
            Path p = Path.of(config.wasapiLoopbackPath.trim());
            if (Files.isRegularFile(p)) {
                return p.toAbsolutePath();
            }
        }
        Path gameBin = FabricLoader.getInstance().getGameDir().resolve("replaymodsound").resolve("bin").resolve("WasapiLoopback.exe");
        if (Files.isRegularFile(gameBin)) {
            return gameBin.toAbsolutePath();
        }
        // Dev / tools path next to common installs
        for (String candidate : new String[]{
                "d:\\replaymod-sound\\run-bin\\WasapiLoopback.exe",
                "d:\\replaymod-sound\\tools\\ffmpeg-wasapi\\WasapiLoopback.exe"
        }) {
            Path p = Path.of(candidate);
            if (Files.isRegularFile(p)) {
                return p.toAbsolutePath();
            }
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
                Path p = Path.of(dir, "WasapiLoopback.exe");
                if (Files.isRegularFile(p)) {
                    return p.toAbsolutePath();
                }
            }
        }
        return null;
    }

    public static boolean available(ModConfig config) {
        return resolve(config) != null;
    }
}
