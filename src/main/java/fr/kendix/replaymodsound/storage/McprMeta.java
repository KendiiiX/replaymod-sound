package fr.kendix.replaymodsound.storage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class McprMeta {
    private McprMeta() {
    }

    public static int readDurationMs(Path mcpr) {
        if (mcpr == null || !Files.isRegularFile(mcpr)) {
            return -1;
        }
        try (ZipFile zip = new ZipFile(mcpr.toFile())) {
            ZipEntry entry = zip.getEntry("metaData.json");
            if (entry == null) {
                return -1;
            }
            try (var reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
                if (obj.has("duration")) {
                    return obj.get("duration").getAsInt();
                }
            }
        } catch (Exception e) {
            return -1;
        }
        return -1;
    }

    public static boolean isComplete(Path mcpr) {
        if (mcpr == null || !Files.isRegularFile(mcpr)) {
            return false;
        }
        try {
            long size1 = Files.size(mcpr);
            if (size1 < 10_000) {
                return false;
            }
            Thread.sleep(400);
            long size2 = Files.size(mcpr);
            if (size1 != size2) {
                return false;
            }
            int duration = readDurationMs(mcpr);
            if (duration <= 0) {
                return false;
            }
            // Must have real packet data
            try (ZipFile zip = new ZipFile(mcpr.toFile())) {
                ZipEntry tmcpr = zip.getEntry("recording.tmcpr");
                if (tmcpr == null || tmcpr.getSize() < 100) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
