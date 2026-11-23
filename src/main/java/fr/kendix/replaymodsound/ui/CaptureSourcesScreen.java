package fr.kendix.replaymodsound.ui;

import fr.kendix.replaymodsound.capture.WasapiLoopbackLocator;
import fr.kendix.replaymodsound.config.ModConfig;
import fr.kendix.replaymodsound.source.AudioSource;
import fr.kendix.replaymodsound.source.SourceRegistry;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.text.Text;

public final class CaptureSourcesScreen extends Screen {
    private final Screen parent;
    private final SourceRegistry registry;
    private final ModConfig config;

    private CheckboxWidget pcToggle;
    private CheckboxWidget micToggle;
    private ButtonWidget micDeviceBtn;

    public CaptureSourcesScreen(Screen parent, SourceRegistry registry, ModConfig config) {
        super(Text.literal("ReplayMod Sound — 2 pistes"));
        this.parent = parent;
        this.registry = registry;
        this.config = config;
    }

    @Override
    protected void init() {
        registry.refreshIfNeeded(true);

        int cx = this.width / 2;
        int y = 48;

        pcToggle = CheckboxWidget.builder(Text.literal("Activer la piste Son PC"), this.textRenderer)
                .pos(cx - 160, y)
                .checked(config.capturePc)
                .build();
        addDrawableChild(pcToggle);
        y += 28;

        micToggle = CheckboxWidget.builder(Text.literal("Activer la piste Micro"), this.textRenderer)
                .pos(cx - 160, y)
                .checked(config.captureMic)
                .build();
        addDrawableChild(micToggle);
        y += 28;

        micDeviceBtn = ButtonWidget.builder(micDeviceLabel(), b -> {
            registry.cycleMic(1);
            b.setMessage(micDeviceLabel());
        }).dimensions(cx - 160, y, 320, 20).build();
        addDrawableChild(micDeviceBtn);

        addDrawableChild(ButtonWidget.builder(Text.literal("Rafraîchir"), b -> {
            registry.refreshIfNeeded(true);
            clearAndInit();
        }).dimensions(cx - 160, this.height - 52, 150, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("OK"), b -> {
            applyAndClose();
        }).dimensions(cx + 10, this.height - 52, 150, 20).build());
    }

    private Text micDeviceLabel() {
        AudioSource mic = registry.resolveMicDevice();
        if (mic == null) {
            return Text.literal("Micro: (aucun micro détecté)");
        }
        return Text.literal("Choisir micro: " + mic.label() + "  [clic = suivant]");
    }

    private void applyAndClose() {
        config.capturePc = pcToggle.isChecked();
        config.captureMic = micToggle.isChecked();
        config.captureSpotify = false;
        AudioSource pc = registry.resolvePcDevice();
        if (pc != null) {
            config.selectedPcDeviceId = pc.id();
        }
        config.save();
        if (client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 12, 0xFFFFFF);

        int cx = this.width / 2;
        boolean ok = WasapiLoopbackLocator.available(config);

        context.drawTextWithShadow(this.textRenderer,
                Text.literal("Piste 1 — Son PC (Discord, jeu, Spotify…)"),
                cx - 160, 36, 0x55FF55);

        context.drawTextWithShadow(this.textRenderer,
                Text.literal(ok
                        ? "Device PC: WASAPI Windows ✓"
                        : "Device PC: manquant — place WasapiLoopback.exe dans replaymodsound/bin"),
                cx - 160, 68, ok ? 0xAAAAAA : 0xFF5555);

        context.drawTextWithShadow(this.textRenderer,
                Text.literal("Piste 2 — Micro"),
                cx - 160, 100, 0x55AAFF);

        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("F7 = config · F6 = volumes dans le replay"),
                this.width / 2, this.height - 28, 0xA0A0A0);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }
}
