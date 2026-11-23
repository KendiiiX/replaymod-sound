package fr.kendix.replaymodsound.capture;

import fr.kendix.replaymodsound.ReplayModSound;
import fr.kendix.replaymodsound.config.ModConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FfmpegCapabilities {
    private static final AtomicBoolean checked = new AtomicBoolean(false);
    private static volatile boolean wasapi;

    private FfmpegCapabilities() {
    }

    public static boolean hasWasapi(ModConfig config) {
        ensure(config);
        return wasapi;
    }

    private static void ensure(ModConfig config) {
        if (checked.get()) {
            return;
        }
        synchronized (FfmpegCapabilities.class) {
            if (checked.get()) {
                return;
            }
            try {
                String ffmpeg = FfmpegLocator.resolve(config);
                ProcessBuilder pb = new ProcessBuilder(ffmpeg, "-hide_banner", "-formats");
                pb.redirectErrorStream(true);
                Process process = pb.start();
                boolean found = false;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.toLowerCase(Locale.ROOT).contains("wasapi")) {
                            found = true;
                            break;
                        }
                    }
                }
                process.waitFor();
                wasapi = found;
                ReplayModSound.LOGGER.info("ffmpeg wasapi support: {}", wasapi);
            } catch (Exception e) {
                wasapi = false;
                ReplayModSound.LOGGER.warn("Could not probe ffmpeg formats", e);
            }
            checked.set(true);
        }
    }
}
