package fr.kendix.replaymodsound.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.replaymod.replaystudio.replay.ReplayFile;
import fr.kendix.replaymodsound.ReplayModSound;
import fr.kendix.replaymodsound.source.AudioSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Enumeration;

public final class ReplaySoundtrackIO {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ReplaySoundtrackIO() {
    }

    public static void writeIntoReplayFile(
            ReplayFile replayFile,
            Map<String, Path> capturedFiles,
            Map<String, AudioSource> sourcesById
    ) throws IOException {
        SoundtrackManifest manifest = new SoundtrackManifest();
        for (Map.Entry<String, Path> entry : capturedFiles.entrySet()) {
            String id = entry.getKey();
            Path file = entry.getValue();
            AudioSource source = sourcesById.get(id);
            String fileName = file.getFileName().toString();
            try (OutputStream out = replayFile.write(SoundtrackManifest.DIR + fileName)) {
                Files.copy(file, out);
            }
            SoundtrackEntry se = new SoundtrackEntry(
                    id,
                    source != null ? source.label() : id,
                    source != null ? source.type() : fr.kendix.replaymodsound.source.AudioSourceType.MICROPHONE,
                    fileName
            );
            manifest.sources.add(se);
        }
        try (OutputStream out = replayFile.write(SoundtrackManifest.MANIFEST_PATH)) {
            out.write(GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
        }
        // Do NOT call replayFile.save() here — premature save freezes duration=0 and Lunar rejects the replay.
        ReplayModSound.LOGGER.info("Wrote {} soundtrack(s) into open ReplayFile (no save)", manifest.sources.size());
    }

    public static void writeIntoMcprZip(Path mcprPath, Map<String, Path> capturedFiles, Map<String, AudioSource> sourcesById)
            throws IOException {
        if (!Files.isRegularFile(mcprPath)) {
            throw new IOException("Replay file missing: " + mcprPath);
        }
        Path temp = mcprPath.resolveSibling(mcprPath.getFileName().toString() + ".rms-tmp");
        Map<String, byte[]> extra = new HashMap<>();
        SoundtrackManifest manifest = new SoundtrackManifest();
        for (Map.Entry<String, Path> entry : capturedFiles.entrySet()) {
            String id = entry.getKey();
            Path file = entry.getValue();
            AudioSource source = sourcesById.get(id);
            String fileName = file.getFileName().toString();
            extra.put(SoundtrackManifest.DIR + fileName, Files.readAllBytes(file));
            manifest.sources.add(new SoundtrackEntry(
                    id,
                    source != null ? source.label() : id,
                    source != null ? source.type() : fr.kendix.replaymodsound.source.AudioSourceType.MICROPHONE,
                    fileName
            ));
        }
        extra.put(SoundtrackManifest.MANIFEST_PATH, GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));

        try (ZipFile zip = new ZipFile(mcprPath.toFile());
             ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(temp))) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry in = entries.nextElement();
                if (extra.containsKey(in.getName())) {
                    continue;
                }
                zos.putNextEntry(new ZipEntry(in.getName()));
                try (InputStream is = zip.getInputStream(in)) {
                    is.transferTo(zos);
                }
                zos.closeEntry();
            }
            for (Map.Entry<String, byte[]> e : extra.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        Files.deleteIfExists(mcprPath);
        Files.move(temp, mcprPath);
        ReplayModSound.LOGGER.info("Patched {} soundtrack(s) into {}", manifest.sources.size(), mcprPath);
    }

    public static Path writeSidecar(
            Path mcprPath,
            Map<String, Path> capturedFiles,
            Map<String, AudioSource> sourcesById
    ) throws IOException {
        Path dir = sidecarDir(mcprPath);
        Files.createDirectories(dir);
        SoundtrackManifest manifest = new SoundtrackManifest();
        for (Map.Entry<String, Path> entry : capturedFiles.entrySet()) {
            String id = entry.getKey();
            Path file = entry.getValue();
            AudioSource source = sourcesById.get(id);
            String fileName = file.getFileName().toString();
            Files.copy(file, dir.resolve(fileName), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            manifest.sources.add(new SoundtrackEntry(
                    id,
                    source != null ? source.label() : id,
                    source != null ? source.type() : fr.kendix.replaymodsound.source.AudioSourceType.MICROPHONE,
                    fileName
            ));
        }
        Files.writeString(dir.resolve("manifest.json"), GSON.toJson(manifest), StandardCharsets.UTF_8);
        return dir;
    }

    public static Path sidecarDir(Path mcprPath) {
        String name = mcprPath.getFileName().toString();
        if (name.endsWith(".mcpr")) {
            name = name.substring(0, name.length() - 5);
        }
        return mcprPath.resolveSibling(name + ".soundtracks");
    }

    public static void copySoundtracksBetweenMcpr(Path sourceMcpr, Path destMcpr) throws IOException {
        Map<String, byte[]> extra = new HashMap<>();
        try (ZipFile zip = new ZipFile(sourceMcpr.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry in = entries.nextElement();
                if (in.getName().startsWith(SoundtrackManifest.DIR)) {
                    try (InputStream is = zip.getInputStream(in)) {
                        extra.put(in.getName(), is.readAllBytes());
                    }
                }
            }
        }
        if (extra.isEmpty()) {
            throw new IOException("No soundtracks in source " + sourceMcpr);
        }
        mergeEntriesIntoMcpr(destMcpr, extra);
    }

    public static void copySidecarIntoMcpr(Path destMcpr, Path sidecarDir) throws IOException {
        Map<String, byte[]> extra = new HashMap<>();
        Path manifest = sidecarDir.resolve("manifest.json");
        if (!Files.isRegularFile(manifest)) {
            throw new IOException("No manifest in " + sidecarDir);
        }
        extra.put(SoundtrackManifest.MANIFEST_PATH, Files.readAllBytes(manifest));
        try (var stream = Files.list(sidecarDir)) {
            for (Path p : stream.toList()) {
                String name = p.getFileName().toString();
                if (name.endsWith(".ogg") || name.endsWith(".wav") || name.endsWith(".mp3")) {
                    extra.put(SoundtrackManifest.DIR + name, Files.readAllBytes(p));
                }
            }
        }
        mergeEntriesIntoMcpr(destMcpr, extra);
    }

    private static void mergeEntriesIntoMcpr(Path mcprPath, Map<String, byte[]> extra) throws IOException {
        Path temp = mcprPath.resolveSibling(mcprPath.getFileName().toString() + ".rms-tmp");
        try (ZipFile zip = new ZipFile(mcprPath.toFile());
             ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(temp))) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry in = entries.nextElement();
                if (extra.containsKey(in.getName())) {
                    continue;
                }
                zos.putNextEntry(new ZipEntry(in.getName()));
                try (InputStream is = zip.getInputStream(in)) {
                    is.transferTo(zos);
                }
                zos.closeEntry();
            }
            for (Map.Entry<String, byte[]> e : extra.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        Files.deleteIfExists(mcprPath);
        Files.move(temp, mcprPath);
    }

    public static Optional<SoundtrackManifest> readSidecarManifest(Path mcprPath) throws IOException {
        Path manifest = sidecarDir(mcprPath).resolve("manifest.json");
        if (!Files.isRegularFile(manifest)) {
            return Optional.empty();
        }
        try (Reader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            return Optional.ofNullable(GSON.fromJson(reader, SoundtrackManifest.class));
        }
    }

    public static Path copySidecarTracks(Path mcprPath, SoundtrackManifest manifest, Path destDir) throws IOException {
        Path src = sidecarDir(mcprPath);
        Files.createDirectories(destDir);
        for (SoundtrackEntry entry : manifest.sources) {
            Path in = src.resolve(entry.file);
            if (Files.isRegularFile(in)) {
                Files.copy(in, destDir.resolve(entry.file), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.writeString(destDir.resolve("manifest.json"), GSON.toJson(manifest), StandardCharsets.UTF_8);
        return destDir;
    }

    public static Optional<SoundtrackManifest> readManifest(ReplayFile replayFile) throws IOException {
        var opt = replayFile.get(SoundtrackManifest.MANIFEST_PATH);
        if (!opt.isPresent()) {
            return Optional.empty();
        }
        try (Reader reader = new InputStreamReader(opt.get(), StandardCharsets.UTF_8)) {
            SoundtrackManifest manifest = GSON.fromJson(reader, SoundtrackManifest.class);
            return Optional.ofNullable(manifest);
        }
    }

    public static Optional<SoundtrackManifest> readManifestFromZip(Path mcprPath) throws IOException {
        try (ZipFile zip = new ZipFile(mcprPath.toFile())) {
            ZipEntry entry = zip.getEntry(SoundtrackManifest.MANIFEST_PATH);
            if (entry == null) {
                return Optional.empty();
            }
            try (Reader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                return Optional.ofNullable(GSON.fromJson(reader, SoundtrackManifest.class));
            }
        }
    }

    public static Path extractTracks(ReplayFile replayFile, SoundtrackManifest manifest, Path destDir) throws IOException {
        Files.createDirectories(destDir);
        for (SoundtrackEntry entry : manifest.sources) {
            var opt = replayFile.get(SoundtrackManifest.DIR + entry.file);
            if (!opt.isPresent()) {
                continue;
            }
            Path out = destDir.resolve(entry.file);
            try (InputStream in = opt.get()) {
                Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path manifestOut = destDir.resolve("manifest.json");
        Files.writeString(manifestOut, GSON.toJson(manifest), StandardCharsets.UTF_8);
        return destDir;
    }

    public static void saveManifest(ReplayFile replayFile, SoundtrackManifest manifest) throws IOException {
        try (OutputStream out = replayFile.write(SoundtrackManifest.MANIFEST_PATH)) {
            out.write(GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
        }
        replayFile.save();
    }

    public static Path exportMixedAudio(Path trackDir, SoundtrackManifest manifest, Path outFile, ModConfigFfmpeg ffmpegCfg)
            throws IOException, InterruptedException {
        // Build ffmpeg amix filter from unmuted tracks
        java.util.List<SoundtrackEntry> active = new java.util.ArrayList<>();
        for (SoundtrackEntry e : manifest.sources) {
            if (!e.muted && e.includeInRender && Files.isRegularFile(trackDir.resolve(e.file))) {
                active.add(e);
            }
        }
        if (active.isEmpty()) {
            throw new IOException("No active tracks to export");
        }
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(ffmpegCfg.ffmpegPath());
        cmd.add("-y");
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("error");
        StringBuilder filter = new StringBuilder();
        for (int i = 0; i < active.size(); i++) {
            SoundtrackEntry e = active.get(i);
            cmd.add("-i");
            cmd.add(trackDir.resolve(e.file).toAbsolutePath().toString());
            double delay = Math.max(0, e.offsetMs) / 1000.0;
            filter.append("[").append(i).append(":a]")
                    .append("volume=").append(e.volume)
                    .append(",adelay=").append((long) (delay * 1000)).append("|").append((long) (delay * 1000))
                    .append("[a").append(i).append("];");
        }
        for (int i = 0; i < active.size(); i++) {
            filter.append("[a").append(i).append("]");
        }
        filter.append("amix=inputs=").append(active.size()).append(":dropout_transition=0[out]");
        cmd.add("-filter_complex");
        cmd.add(filter.toString());
        cmd.add("-map");
        cmd.add("[out]");
        cmd.add("-c:a");
        cmd.add("libopus");
        cmd.add("-b:a");
        cmd.add("192k");
        cmd.add(outFile.toAbsolutePath().toString());

        Process p = new ProcessBuilder(cmd).inheritIO().start();
        int code = p.waitFor();
        if (code != 0) {
            throw new IOException("ffmpeg mix failed with code " + code);
        }
        return outFile;
    }

    public interface ModConfigFfmpeg {
        String ffmpegPath();
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        in.transferTo(bos);
        return bos.toByteArray();
    }
}
