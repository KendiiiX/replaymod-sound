package fr.kendix.replaymodsound.capture;

import fr.kendix.replaymodsound.config.ModConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FfmpegLocator {
    private FfmpegLocator() {
    }

    public static String resolve(ModConfig config) {
        String configured = config.ffmpegPath == null || config.ffmpegPath.isBlank() ? "ffmpeg" : config.ffmpegPath.trim();
        if (configured.contains("/") || configured.contains("\\") || configured.endsWith(".exe")) {
            Path path = Path.of(configured);
            if (Files.isRegularFile(path)) {
                return path.toAbsolutePath().toString();
            }
        }
        // Prefer bundled next to WasapiLoopback (Lunar: <game>/replaymodsound/bin)
        for (String candidate : new String[]{
                FabricLoader.getInstance().getGameDir().resolve("replaymodsound").resolve("bin").resolve("ffmpeg.exe").toString(),
                "d:\\replaymod-sound\\tools\\ffmpeg-wasapi\\ffmpeg.exe",
                "d:\\replaymod-sound\\run-bin\\ffmpeg.exe"
        }) {
            Path p = Path.of(candidate);
            if (Files.isRegularFile(p)) {
                return p.toAbsolutePath().toString();
            }
        }
        String fromPath = findOnPath("ffmpeg.exe");
        if (fromPath == null) {
            fromPath = findOnPath("ffmpeg");
        }
        return fromPath != null ? fromPath : configured;
    }

    private static String findOnPath(String name) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return null;
        }
        for (String dir : pathEnv.split(PatternQuote())) {
            Path candidate = Path.of(dir, name);
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }
        return null;
    }

    private static String PatternQuote() {
        return File.pathSeparator;
    }
}
