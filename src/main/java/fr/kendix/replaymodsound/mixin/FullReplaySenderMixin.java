package fr.kendix.replaymodsound.mixin;

import com.replaymod.replay.FullReplaySender;
import fr.kendix.replaymodsound.ReplayModSound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Instant soundtrack pause when ReplayMod sets speed to 0 (play/pause button).
 */
@Mixin(value = FullReplaySender.class, remap = false)
public abstract class FullReplaySenderMixin {
    @Inject(method = "setReplaySpeed", at = @At("HEAD"))
    private void replaymodsound$onSpeed(double speed, CallbackInfo ci) {
        try {
            if (ReplayModSound.get() != null) {
                ReplayModSound.get().getSoundtrackPlayer().onReplaySpeedChanged(speed);
            }
        } catch (Throwable ignored) {
        }
    }
}
