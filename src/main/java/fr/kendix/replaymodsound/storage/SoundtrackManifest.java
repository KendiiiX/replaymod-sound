package fr.kendix.replaymodsound.storage;

import java.util.ArrayList;
import java.util.List;

public final class SoundtrackManifest {
    public static final String MANIFEST_PATH = "soundtracks/manifest.json";
    public static final String DIR = "soundtracks/";

    public int version = 1;
    public long timelineStartMs = 0;
    public List<SoundtrackEntry> sources = new ArrayList<>();

    public SoundtrackEntry find(String id) {
        for (SoundtrackEntry entry : sources) {
            if (entry.id.equals(id)) {
                return entry;
            }
        }
        return null;
    }
}
