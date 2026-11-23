# ReplayMod Sound

Fabric 1.20.6 companion for **ReplayMod 2.6.17**: capture each selected audio source (mic / PC loopback) during a ReplayMod recording, store tracks inside the `.mcpr`, and manage them **in the replay** (mute / volume / offset / render export).

## Controls

| Key | Action |
|-----|--------|
| **F7** | Capture sources (enable mic / desktop devices) |
| **F6** | In-replay soundtrack panel (when a replay with tracks is open) |

## Requirements

- Minecraft 1.20.6 Fabric
- ReplayMod `1.20.6-2.6.17`
- `ffmpeg` on `PATH` with **dshow** (essentials OK for mics)

### PC / desktop audio

Your current `ffmpeg` essentials build has **no WASAPI**. For PC sound pick one:

1. Enable a dshow loopback device in F7: **OBS-Audio**, Stereo Mix, VB-Cable, Voicemeeter, etc.
2. Or install a **full** ffmpeg build (with `wasapi`) and set `ffmpegPath` in `config/replaymodsound.json`

## Build

```bash
gradlew build
```

Jar: `build/libs/replaymod-sound-1.0.0.jar`

Place next to ReplayMod in your mods folder.

## Notes

- One ffmpeg process per enabled source
- Tracks live under `soundtracks/` inside the `.mcpr`
- Export mix writes an `.ogg` under `.minecraft/replaymodsound/export/`
- F6 opens track mute / volume / offset / render-include while a soundtrack replay is open
