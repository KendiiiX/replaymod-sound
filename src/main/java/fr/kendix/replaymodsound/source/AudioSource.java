package fr.kendix.replaymodsound.source;

import java.util.Objects;

public final class AudioSource {
    private final String id;
    private final String label;
    private final AudioSourceType type;
    /** ffmpeg device name (dshow/wasapi) */
    private final String deviceName;

    public AudioSource(String id, String label, AudioSourceType type, String deviceName) {
        this.id = Objects.requireNonNull(id);
        this.label = Objects.requireNonNull(label);
        this.type = Objects.requireNonNull(type);
        this.deviceName = Objects.requireNonNull(deviceName);
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public AudioSourceType type() {
        return type;
    }

    public String deviceName() {
        return deviceName;
    }
}
