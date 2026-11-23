package fr.kendix.replaymodsound;

import com.replaymod.core.ReplayMod;
import com.replaymod.replay.ReplayModReplay;
import fr.kendix.replaymodsound.capture.PerSourceCaptureService;
import fr.kendix.replaymodsound.config.ModConfig;
import fr.kendix.replaymodsound.playback.MultiTrackSoundtrackPlayer;
import fr.kendix.replaymodsound.recording.RecordingLifecycle;
import fr.kendix.replaymodsound.source.SourceRegistry;
import fr.kendix.replaymodsound.storage.PendingSoundtrackRegistry;
import fr.kendix.replaymodsound.ui.CaptureSourcesScreen;
import fr.kendix.replaymodsound.ui.InReplaySoundtrackScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ReplayModSound implements ClientModInitializer {
    public static final String MOD_ID = "replaymodsound";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static ReplayModSound instance;

    private ModConfig config;
    private SourceRegistry sourceRegistry;
    private PerSourceCaptureService captureService;
    private PendingSoundtrackRegistry pendingRegistry;
    private MultiTrackSoundtrackPlayer soundtrackPlayer;
    private RecordingLifecycle recordingLifecycle;

    private KeyBinding openSourcesKey;
    private KeyBinding openTracksKey;
    private boolean f6Down;
    private boolean f7Down;
    private boolean jDown;
    private boolean rmKeysRegistered;
    private long lastTracksOpenMs;

    public static ReplayModSound get() {
        return instance;
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        this.config = ModConfig.load();
        this.sourceRegistry = new SourceRegistry(config);
        this.captureService = new PerSourceCaptureService(config);
        this.pendingRegistry = new PendingSoundtrackRegistry();
        this.soundtrackPlayer = new MultiTrackSoundtrackPlayer(pendingRegistry);
        this.recordingLifecycle = new RecordingLifecycle(config, sourceRegistry, captureService, pendingRegistry);

        openSourcesKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.replaymodsound.sources",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F7,
                "key.category.replaymodsound"
        ));
        openTracksKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.replaymodsound.tracks",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F6,
                "key.category.replaymodsound"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
        LOGGER.info("ReplayMod Sound initialized (ReplayMod 2.6.17 / 1.20.6)");
        System.out.println("[replaymodsound] initialized");
    }

    private void ensureReplayModKeys() {
        if (rmKeysRegistered) {
            return;
        }
        try {
            if (ReplayMod.instance == null) {
                return;
            }
            var registry = ReplayMod.instance.getKeyBindingRegistry();
            // RM onlyInReplay binding — works when overlay owns input
            registry.registerKeyBinding(
                    "replaymod.input.soundtracks",
                    GLFW.GLFW_KEY_F6,
                    () -> {
                        MinecraftClient client = MinecraftClient.getInstance();
                        if (client != null) {
                            openTracksScreen(client);
                        }
                    },
                    true
            );
            // Raw key path (KeyEventCallback) — fires even when vanilla KeyBinding is muted
            registry.registerRaw(GLFW.GLFW_KEY_F6, () -> {
                if (!isInReplay()) {
                    return false;
                }
                MinecraftClient client = MinecraftClient.getInstance();
                if (client == null) {
                    return false;
                }
                if (client.currentScreen instanceof InReplaySoundtrackScreen) {
                    return false;
                }
                openTracksScreen(client);
                return true;
            });
            registry.registerRaw(GLFW.GLFW_KEY_J, () -> {
                if (!isInReplay()) {
                    return false;
                }
                MinecraftClient client = MinecraftClient.getInstance();
                if (client == null || client.currentScreen instanceof InReplaySoundtrackScreen) {
                    return false;
                }
                openTracksScreen(client);
                return true;
            });
            rmKeysRegistered = true;
            LOGGER.info("Registered ReplayMod-only soundtrack keybinds (F6 / J)");
            System.out.println("[replaymodsound] RM keybinds registered");
        } catch (Throwable t) {
            LOGGER.warn("Could not register ReplayMod keybinds yet", t);
        }
    }

    private void onClientTick(MinecraftClient client) {
        if (client.getWindow() == null) {
            return;
        }

        ensureReplayModKeys();

        while (openSourcesKey.wasPressed()) {
            openSources(client);
        }
        while (openTracksKey.wasPressed()) {
            openTracksScreen(client);
        }

        long window = client.getWindow().getHandle();
        boolean f6 = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_F6) == GLFW.GLFW_PRESS;
        boolean f7 = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_F7) == GLFW.GLFW_PRESS;
        boolean j = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_J) == GLFW.GLFW_PRESS;

        boolean inReplay = isInReplay();
        Screen screen = client.currentScreen;
        boolean ourTracks = screen instanceof InReplaySoundtrackScreen;
        boolean ourSources = screen instanceof CaptureSourcesScreen;

        if (((f6 && !f6Down) || (j && !jDown)) && inReplay && !ourTracks) {
            openTracksScreen(client);
        }
        if (f7 && !f7Down && !ourSources) {
            openSources(client);
        }

        f6Down = f6;
        f7Down = f7;
        jDown = j;

        recordingLifecycle.tick();
        soundtrackPlayer.tick();
    }

    private static boolean isInReplay() {
        try {
            return ReplayModReplay.instance != null && ReplayModReplay.instance.getReplayHandler() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private void openSources(MinecraftClient client) {
        client.setScreen(new CaptureSourcesScreen(client.currentScreen, sourceRegistry, config));
    }

    /** Public for overlay button / RM keybind. */
    public void openTracksScreen(MinecraftClient client) {
        try {
            if (client.currentScreen instanceof InReplaySoundtrackScreen) {
                return; // déjà ouvert — évite d'empiler plusieurs panneaux
            }
            long now = System.currentTimeMillis();
            if (now - lastTracksOpenMs < 400) {
                return; // debounce raw+keybind+GLFW sur la même pression
            }
            lastTracksOpenMs = now;

            Screen parent = client.currentScreen;
            while (parent instanceof InReplaySoundtrackScreen nested) {
                parent = nested.parentAccessible();
            }
            client.setScreen(new InReplaySoundtrackScreen(parent, soundtrackPlayer));
        } catch (Throwable t) {
            LOGGER.error("Failed to open tracks screen", t);
            System.out.println("[replaymodsound] F6 screen failed: " + t);
            if (client.player != null) {
                client.player.sendMessage(Text.literal("§c[RMS] Erreur ouverture F6: " + t.getMessage()), false);
            }
        }
    }

    public ModConfig getConfig() {
        return config;
    }

    public SourceRegistry getSourceRegistry() {
        return sourceRegistry;
    }

    public PerSourceCaptureService getCaptureService() {
        return captureService;
    }

    public MultiTrackSoundtrackPlayer getSoundtrackPlayer() {
        return soundtrackPlayer;
    }

    public RecordingLifecycle getRecordingLifecycle() {
        return recordingLifecycle;
    }
}
