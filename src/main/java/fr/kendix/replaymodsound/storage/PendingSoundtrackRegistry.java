package fr.kendix.replaymodsound.storage;

import fr.kendix.replaymodsound.ReplayModSound;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Remembers recent captures so we can re-attach audio after ReplayMod's
 * rename dialog copies a .mcpr without custom zip entries.
 */
public final class PendingSoundtrackRegistry {
    public static final class Entry {
        public final String stem;
        public final Path sidecarDir;
        public final Path rawMcpr;
        public final long createdAt;

        public Entry(String stem, Path sidecarDir, Path rawMcpr) {
            this.stem = stem;
            this.sidecarDir = sidecarDir;
            this.rawMcpr = rawMcpr;
            this.createdAt = System.currentTimeMillis();
        }
    }

    private final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>();

    public void register(Path outputPath, Path sidecarDir) {
        if (outputPath == null) {
            return;
        }
        String stem = stemOf(outputPath.getFileName().toString());
        Path raw = outputPath;
        // ReplayMod often keeps the recording under .../raw/name.mcpr
        if (outputPath.getParent() != null && !"raw".equalsIgnoreCase(outputPath.getParent().getFileName().toString())) {
            Path maybeRaw = outputPath.getParent().resolve("raw").resolve(outputPath.getFileName());
            if (Files.isRegularFile(maybeRaw)) {
                raw = maybeRaw;
            }
        }
        entries.add(0, new Entry(stem, sidecarDir, raw));
        while (entries.size() > 20) {
            entries.remove(entries.size() - 1);
        }
        ReplayModSound.LOGGER.info("Registered pending soundtracks stem={} sidecar={}", stem, sidecarDir);
    }

    public Optional<Entry> findBestFor(Path openedMcpr) {
        if (openedMcpr == null) {
            return Optional.empty();
        }
        String stem = stemOf(openedMcpr.getFileName().toString());
        for (Entry e : entries) {
            if (e.stem.equalsIgnoreCase(stem)) {
                return Optional.of(e);
            }
        }
        // Fallback: newest entry from last 30 minutes
        long cutoff = System.currentTimeMillis() - 30 * 60_000L;
        return entries.stream()
                .filter(e -> e.createdAt >= cutoff)
                .max(Comparator.comparingLong(e -> e.createdAt));
    }

    public Optional<Path> findSidecarNearby(Path openedMcpr) throws IOException {
        if (openedMcpr == null) {
            return Optional.empty();
        }
        Path direct = ReplaySoundtrackIO.sidecarDir(openedMcpr);
        if (Files.isRegularFile(direct.resolve("manifest.json"))) {
            return Optional.of(direct);
        }
        Path parent = openedMcpr.getParent();
        if (parent == null) {
            return Optional.empty();
        }
        // recording/<stem>.soundtracks and raw/<stem>.mcpr siblings
        List<Path> candidates = new ArrayList<>();
        Path recordingDir = parent.resolve("recording");
        Path rawDir = parent.resolve("raw");
        String stem = stemOf(openedMcpr.getFileName().toString());
        candidates.add(recordingDir.resolve(stem + ".soundtracks"));
        candidates.add(parent.resolve(stem + ".soundtracks"));
        if (Files.isDirectory(recordingDir)) {
            try (var s = Files.list(recordingDir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".soundtracks"))
                        .sorted(Comparator.comparingLong((Path p) -> {
                            try {
                                return Files.getLastModifiedTime(p).toMillis();
                            } catch (IOException e) {
                                return 0L;
                            }
                        }).reversed())
                        .forEach(candidates::add);
            }
        }
        for (Path c : candidates) {
            if (Files.isDirectory(c) && Files.isRegularFile(c.resolve("manifest.json"))) {
                return Optional.of(c);
            }
        }
        // raw mcpr that still has embedded soundtracks
        if (Files.isDirectory(rawDir)) {
            Path rawSame = rawDir.resolve(stem + ".mcpr");
            if (Files.isRegularFile(rawSame) && ReplaySoundtrackIO.readManifestFromZip(rawSame).isPresent()) {
                return Optional.of(rawSame); // caller must detect file vs dir
            }
            try (var s = Files.list(rawDir)) {
                Optional<Path> newest = s
                        .filter(p -> p.getFileName().toString().endsWith(".mcpr"))
                        .filter(Files::isRegularFile)
                        .sorted(Comparator.comparingLong((Path p) -> {
                            try {
                                return Files.getLastModifiedTime(p).toMillis();
                            } catch (IOException e) {
                                return 0L;
                            }
                        }).reversed())
                        .limit(5)
                        .filter(p -> {
                            try {
                                return ReplaySoundtrackIO.readManifestFromZip(p).isPresent();
                            } catch (IOException e) {
                                return false;
                            }
                        })
                        .findFirst();
                if (newest.isPresent()) {
                    return newest;
                }
            }
        }
        return Optional.empty();
    }

    public void attachToFinalMcpr(Path finalMcpr) {
        if (finalMcpr == null || !Files.isRegularFile(finalMcpr)) {
            return;
        }
        try {
            // Never patch incomplete Lunar-invalid replays (duration=0)
            if (!McprMeta.isComplete(finalMcpr)) {
                ReplayModSound.LOGGER.info("Skip attach — mcpr incomplete/duration=0: {}", finalMcpr);
                return;
            }
            if (ReplaySoundtrackIO.readManifestFromZip(finalMcpr).isPresent()) {
                return; // already has tracks
            }
            Optional<Entry> pending = findBestFor(finalMcpr);
            Path source = null;
            if (pending.isPresent()) {
                Entry e = pending.get();
                if (e.rawMcpr != null && Files.isRegularFile(e.rawMcpr)
                        && ReplaySoundtrackIO.readManifestFromZip(e.rawMcpr).isPresent()) {
                    source = e.rawMcpr;
                } else if (e.sidecarDir != null && Files.isDirectory(e.sidecarDir)) {
                    ReplaySoundtrackIO.copySidecarIntoMcpr(finalMcpr, e.sidecarDir);
                    // also place sidecar next to final name
                    Path destSide = ReplaySoundtrackIO.sidecarDir(finalMcpr);
                    copyDir(e.sidecarDir, destSide);
                    ReplayModSound.LOGGER.info("Attached sidecar into {}", finalMcpr);
                    return;
                }
            }
            if (source == null) {
                Optional<Path> nearby = findSidecarNearby(finalMcpr);
                if (nearby.isPresent()) {
                    Path n = nearby.get();
                    if (Files.isDirectory(n)) {
                        ReplaySoundtrackIO.copySidecarIntoMcpr(finalMcpr, n);
                        copyDir(n, ReplaySoundtrackIO.sidecarDir(finalMcpr));
                        return;
                    }
                    if (Files.isRegularFile(n)) {
                        source = n;
                    }
                }
            }
            if (source != null) {
                ReplaySoundtrackIO.copySoundtracksBetweenMcpr(source, finalMcpr);
                ReplayModSound.LOGGER.info("Copied soundtracks from {} into {}", source, finalMcpr);
            }
        } catch (Exception e) {
            ReplayModSound.LOGGER.error("attachToFinalMcpr failed for {}", finalMcpr, e);
        }
    }

    private static void copyDir(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        try (var stream = Files.list(from)) {
            for (Path p : stream.toList()) {
                if (Files.isRegularFile(p)) {
                    Files.copy(p, to.resolve(p.getFileName().toString()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static String stemOf(String name) {
        return name.endsWith(".mcpr") ? name.substring(0, name.length() - 5) : name;
    }
}
