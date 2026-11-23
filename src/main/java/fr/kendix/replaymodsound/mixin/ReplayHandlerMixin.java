package fr.kendix.replaymodsound.mixin;

import com.replaymod.replay.ReplayHandler;
import fr.kendix.replaymodsound.ReplayModSound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;

@Mixin(value = ReplayHandler.class, remap = false)
public abstract class ReplayHandlerMixin {
    @Inject(method = "endReplay", at = @At("HEAD"))
    private void replaymodsound$onEndReplay(CallbackInfo ci) throws IOException {
        if (ReplayModSound.get() != null) {
            ReplayModSound.get().getSoundtrackPlayer().unload();
        }
    }
}
