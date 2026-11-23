package fr.kendix.replaymodsound.mixin;

import com.replaymod.lib.de.johni0702.minecraft.gui.element.GuiButton;
import com.replaymod.replay.ReplayHandler;
import com.replaymod.replay.gui.overlay.GuiReplayOverlay;
import fr.kendix.replaymodsound.ReplayModSound;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GuiReplayOverlay.class, remap = false)
public abstract class GuiReplayOverlayMixin {
    @Shadow
    public com.replaymod.lib.de.johni0702.minecraft.gui.container.GuiPanel topPanel;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void replaymodsound$addAudioButton(ReplayHandler handler, CallbackInfo ci) {
        try {
            GuiButton audioBtn = new GuiButton()
                    .setSize(56, 20)
                    .setLabel("Audio");
            audioBtn.onClick(() -> {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client != null && ReplayModSound.get() != null) {
                    client.execute(() -> ReplayModSound.get().openTracksScreen(client));
                }
            });
            topPanel.addElements(null, audioBtn);
        } catch (Throwable t) {
            ReplayModSound.LOGGER.warn("Could not add Audio button to replay overlay", t);
        }
    }
}
