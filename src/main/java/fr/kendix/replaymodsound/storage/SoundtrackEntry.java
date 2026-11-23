package fr.kendix.replaymodsound.storage;

import fr.kendix.replaymodsound.source.AudioSourceType;

public final class SoundtrackEntry {
    public String id;
    public String label;
    public String type;
    public String file;
    public long offsetMs = 0;
    public float volume = 1.0f;
    public boolean muted = false;
    public boolean includeInRender = true;

    public SoundtrackEntry() {
    }

    public SoundtrackEntry(String id, String label, AudioSourceType type, String file) {
        this.id = id;
        this.label = label;
        this.type = type.name();
        this.file = file;
    }

    public AudioSourceType typeEnum() {
        try {
            return AudioSourceType.valueOf(type);
        } catch (Exception e) {
            return AudioSourceType.MICROPHONE;
        }
    }
}
