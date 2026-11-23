package fr.kendix.replaymodsound.source;

import fr.kendix.replaymodsound.ReplayModSound;
import fr.kendix.replaymodsound.capture.FfmpegLocator;
import fr.kendix.replaymodsound.capture.WasapiLoopbackLocator;
import fr.kendix.replaymodsound.config.ModConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SourceRegistry {
    public static final String TRACK_PC_ID = "pc";
    public static final String TRACK_MIC_ID = "mic";
    public static final String TRACK_SPOTIFY_ID = "spotify";

    private static final Pattern DSHOW_QUOTED = Pattern.compile("\"([^\"]+)\"");
    private static final String[] LOOPBACK_HINTS = {
            "stereo mix", "what u hear", "wave out", "loopback", "cable", "vb-audio",
            "obs-audio", "obs audio", "virtual cable", "voicemeeter"
    };

    private final ModConfig config;
    private final Map<String, AudioSource> mics = new LinkedHashMap<>();
    private final Map<String, AudioSource> pcDevices = new LinkedHashMap<>();
    private long lastRefreshMs;

    public SourceRegistry(ModConfig config) {
        this.config = config;
        refreshIfNeeded(true);
    }

    public synchronized void refreshIfNeeded(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastRefreshMs < 30_000L) {
            return;
        }
        lastRefreshMs = now;
        mics.clear();
        pcDevices.clear();
        try {
            enumerateDshow();
            if (WasapiLoopbackLocator.available(config)) {
                pcDevices.put("desktop_wasapi_native", new AudioSource(
                        "desktop_wasapi_native",
                        "Son PC entier (Windows)",
                        AudioSourceType.LOOPBACK,
                        "wasapi-native"
                ));
            }
        } catch (Exception e) {
            ReplayModSound.LOGGER.warn("Device enumeration failed", e);
        }
        ensureSelections();
    }

    public synchronized List<AudioSource> microphones() {
        refreshIfNeeded(false);
        return List.copyOf(mics.values());
    }

    public synchronized List<AudioSource> pcDevices() {
        refreshIfNeeded(false);
        return List.copyOf(pcDevices.values());
    }

    /** Active recording tracks: PC + Mic. */
    public synchronized List<AudioSource> enabled() {
        refreshIfNeeded(false);
        ensureSelections();
        List<AudioSource> out = new ArrayList<>(2);

        if (config.capturePc) {
            AudioSource pcDev = resolvePcDevice();
            if (pcDev != null) {
                out.add(new AudioSource(
                        TRACK_PC_ID,
                        "Son PC",
                        AudioSourceType.LOOPBACK,
                        pcDev.deviceName()
                ));
            }
        }
        if (config.captureMic) {
            AudioSource micDev = resolveMicDevice();
            if (micDev != null) {
                out.add(new AudioSource(
                        TRACK_MIC_ID,
                        "Micro (" + shortName(micDev.label()) + ")",
                        AudioSourceType.MICROPHONE,
                        micDev.deviceName()
                ));
            }
        }
        return out;
    }

    public synchronized AudioSource resolveMicDevice() {
        if (config.selectedMicDeviceId != null && mics.containsKey(config.selectedMicDeviceId)) {
            return mics.get(config.selectedMicDeviceId);
        }
        return mics.isEmpty() ? null : mics.values().iterator().next();
    }

    public synchronized AudioSource resolvePcDevice() {
        if (config.selectedPcDeviceId != null && pcDevices.containsKey(config.selectedPcDeviceId)) {
            return pcDevices.get(config.selectedPcDeviceId);
        }
        if (pcDevices.containsKey("desktop_wasapi_native")) {
            return pcDevices.get("desktop_wasapi_native");
        }
        for (AudioSource s : pcDevices.values()) {
            if (!s.deviceName().toLowerCase(Locale.ROOT).contains("obs-audio")) {
                return s;
            }
        }
        return pcDevices.isEmpty() ? null : pcDevices.values().iterator().next();
    }

    public synchronized void selectMic(String deviceId) {
        config.selectedMicDeviceId = deviceId == null ? "" : deviceId;
        config.save();
    }

    public synchronized void selectPc(String deviceId) {
        config.selectedPcDeviceId = deviceId == null ? "" : deviceId;
        config.save();
    }

    public synchronized void cycleMic(int delta) {
        List<AudioSource> list = microphones();
        if (list.isEmpty()) {
            return;
        }
        int idx = 0;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(config.selectedMicDeviceId)) {
                idx = i;
                break;
            }
        }
        idx = Math.floorMod(idx + delta, list.size());
        selectMic(list.get(idx).id());
    }

    public synchronized void cyclePc(int delta) {
        List<AudioSource> list = pcDevices();
        if (list.isEmpty()) {
            return;
        }
        int idx = 0;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(config.selectedPcDeviceId)) {
                idx = i;
                break;
            }
        }
        idx = Math.floorMod(idx + delta, list.size());
        selectPc(list.get(idx).id());
    }

    private void ensureSelections() {
        boolean changed = false;
        if ((config.selectedMicDeviceId == null || config.selectedMicDeviceId.isBlank() || !mics.containsKey(config.selectedMicDeviceId))
                && !mics.isEmpty()) {
            String tonor = mics.keySet().stream()
                    .filter(id -> id.contains("tonor"))
                    .findFirst()
                    .orElse(mics.keySet().iterator().next());
            config.selectedMicDeviceId = tonor;
            changed = true;
        }
        if ((config.selectedPcDeviceId == null || config.selectedPcDeviceId.isBlank() || !pcDevices.containsKey(config.selectedPcDeviceId))
                && !pcDevices.isEmpty()) {
            AudioSource pc = resolvePcDevice();
            if (pc != null) {
                config.selectedPcDeviceId = pc.id();
                changed = true;
            }
        }
        if (changed) {
            config.save();
        }
    }

    private void enumerateDshow() throws Exception {
        String ffmpeg = FfmpegLocator.resolve(config);
        ProcessBuilder pb = new ProcessBuilder(
                ffmpeg, "-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy"
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String lower = line.toLowerCase(Locale.ROOT);
                Matcher matcher = DSHOW_QUOTED.matcher(line);
                if (!matcher.find()) {
                    continue;
                }
                String name = matcher.group(1);
                boolean isAudio = lower.contains("(audio)");
                boolean isNone = lower.contains("(none)");
                if (!isAudio && !isNone) {
                    continue;
                }
                if (lower.contains("(video)")) {
                    continue;
                }
                if (isLoopbackName(name)) {
                    String id = sanitizeId("desktop_" + name);
                    pcDevices.putIfAbsent(id, new AudioSource(id, name, AudioSourceType.LOOPBACK, name));
                } else if (isAudio) {
                    String id = sanitizeId("mic_" + name);
                    mics.putIfAbsent(id, new AudioSource(id, name, AudioSourceType.MICROPHONE, name));
                }
            }
        }
        process.waitFor();
    }

    private static boolean isLoopbackName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String hint : LOOPBACK_HINTS) {
            if (lower.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private static String sanitizeId(String raw) {
        return raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private static String shortName(String label) {
        return label.replace(" (mic)", "").replace("Microphone (", "").replace(")", "");
    }

    public synchronized List<AudioSource> all() {
        List<AudioSource> all = new ArrayList<>();
        all.addAll(pcDevices());
        all.addAll(microphones());
        return all;
    }
}
