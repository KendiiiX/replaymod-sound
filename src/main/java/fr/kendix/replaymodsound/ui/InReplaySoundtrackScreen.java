package fr.kendix.replaymodsound.ui;

import fr.kendix.replaymodsound.ReplayModSound;
import fr.kendix.replaymodsound.playback.MultiTrackSoundtrackPlayer;
import fr.kendix.replaymodsound.source.SourceRegistry;
import fr.kendix.replaymodsound.storage.SoundtrackEntry;
import fr.kendix.replaymodsound.storage.SoundtrackManifest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class InReplaySoundtrackScreen extends Screen {
    private static final float VOL_MAX = 2.0f; // 200%

    private final Screen parent;
    private final MultiTrackSoundtrackPlayer player;
    private final List<Row> rows = new ArrayList<>();
    private String status = "";

    public InReplaySoundtrackScreen(Screen parent, MultiTrackSoundtrackPlayer player) {
        super(Text.translatable("replaymodsound.tracks.title"));
        this.parent = parent;
        this.player = player;
    }

    /** Used to unwrap stacked panels when reopening / closing. */
    public Screen parentAccessible() {
        return parent;
    }

    @Override
    protected void init() {
        rows.clear();
        SoundtrackManifest manifest = player.getManifest();
        List<SoundtrackEntry> sources = visibleSources(manifest);
        if (sources.isEmpty()) {
            addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
                    .dimensions(this.width / 2 - 50, this.height - 28, 100, 20).build());
            return;
        }

        int y = 40;
        for (SoundtrackEntry entry : sources) {
            Row row = new Row(entry);
            // Layout: NAME | Mute | Render | Vol | offset ms
            int x = 20;
            row.mute = CheckboxWidget.builder(Text.literal("Mute"), this.textRenderer)
                    .pos(x + 130, y)
                    .checked(entry.muted)
                    .build();
            row.renderInclude = CheckboxWidget.builder(Text.literal("Render"), this.textRenderer)
                    .pos(x + 200, y)
                    .checked(entry.includeInRender)
                    .build();
            row.volume = new VolumeSlider(x + 280, y, 140, 20, entry);
            row.offset = new TextFieldWidget(this.textRenderer, x + 430, y, 70, 20, Text.literal("offset"));
            row.offset.setText(String.valueOf(entry.offsetMs));
            row.offset.setMaxLength(8);

            addDrawableChild(row.mute);
            addDrawableChild(row.renderInclude);
            addDrawableChild(row.volume);
            addDrawableChild(row.offset);
            rows.add(row);
            y += 32;
            if (y > this.height - 80) {
                break;
            }
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("Save"), b -> {
            applyRows();
            player.persistManifestEdits();
            status = "Saved";
        }).dimensions(this.width / 2 - 160, this.height - 28, 90, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Export mix"), b -> {
            applyRows();
            try {
                Path out = FabricLoader.getInstance().getGameDir()
                        .resolve("replaymodsound")
                        .resolve("export")
                        .resolve("mix-" + System.currentTimeMillis() + ".ogg");
                java.nio.file.Files.createDirectories(out.getParent());
                player.exportMix(out);
                status = "Exported: " + out.getFileName();
            } catch (Exception e) {
                status = "Export failed: " + e.getMessage();
                ReplayModSound.LOGGER.error("Export mix failed", e);
            }
        }).dimensions(this.width / 2 - 60, this.height - 28, 100, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> {
            applyRows();
            player.persistManifestEdits();
            close();
        }).dimensions(this.width / 2 + 50, this.height - 28, 90, 20).build());
    }

    private static List<SoundtrackEntry> visibleSources(SoundtrackManifest manifest) {
        List<SoundtrackEntry> out = new ArrayList<>();
        if (manifest == null || manifest.sources == null) {
            return out;
        }
        for (SoundtrackEntry entry : manifest.sources) {
            if (entry == null || entry.id == null) {
                continue;
            }
            // Spotify plus en piste séparée
            if (SourceRegistry.TRACK_SPOTIFY_ID.equalsIgnoreCase(entry.id)
                    || "spotify".equalsIgnoreCase(entry.id)) {
                continue;
            }
            out.add(entry);
        }
        return out;
    }

    private void applyRows() {
        for (Row row : rows) {
            row.entry.muted = row.mute.isChecked();
            row.entry.includeInRender = row.renderInclude.isChecked();
            try {
                row.entry.offsetMs = Long.parseLong(row.offset.getText().trim());
            } catch (NumberFormatException ignored) {
            }
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 12, 0xFFFFFF);

        if (rows.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("No soundtrack for replay."), this.width / 2, 40, 0xFF5555);
        } else {
            int y = 40;
            for (Row row : rows) {
                String label = row.entry.label != null && !row.entry.label.isBlank()
                        ? row.entry.label
                        : row.entry.id;
                // Nom à gauche, net, après les widgets pour rester lisible
                context.drawTextWithShadow(this.textRenderer, Text.literal(label), 20, y + 6, 0xFFFFFF);
                context.drawTextWithShadow(this.textRenderer, Text.literal("ms"), 504, y + 6, 0xAAAAAA);
                y += 32;
            }
        }
        if (!status.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(status), this.width / 2, this.height - 48, 0x55FF55);
        }
    }

    @Override
    public void close() {
        if (client != null) {
            // Ne jamais réouvrir une ancienne instance de ce panneau
            Screen back = parent;
            while (back instanceof InReplaySoundtrackScreen nested) {
                back = nested.parent;
            }
            client.setScreen(back);
        }
    }

    private static final class Row {
        final SoundtrackEntry entry;
        CheckboxWidget mute;
        CheckboxWidget renderInclude;
        VolumeSlider volume;
        TextFieldWidget offset;

        Row(SoundtrackEntry entry) {
            this.entry = entry;
        }
    }

    private static final class VolumeSlider extends SliderWidget {
        private final SoundtrackEntry entry;

        VolumeSlider(int x, int y, int width, int height, SoundtrackEntry entry) {
            super(x, y, width, height, Text.literal("Vol"),
                    Math.max(0, Math.min(1, entry.volume / VOL_MAX)));
            this.entry = entry;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(String.format("Vol %.0f%%", value * VOL_MAX * 100)));
        }

        @Override
        protected void applyValue() {
            entry.volume = (float) (value * VOL_MAX);
        }
    }
}
