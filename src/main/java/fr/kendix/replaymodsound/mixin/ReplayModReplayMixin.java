package fr.kendix.replaymodsound.mixin;

import com.replaymod.replay.ReplayHandler;
import com.replaymod.replay.ReplayModReplay;
import com.replaymod.replaystudio.replay.ReplayFile;
import fr.kendix.replaymodsound.ReplayModSound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;
import java.io.IOException;

@Mixin(value = ReplayModReplay.class, remap = false)
public abstract class ReplayModReplayMixin {
    @Inject(method = "startReplay(Ljava/io/File;)V", at = @At("HEAD"))
    private void replaymodsound$rememberFile(File file, CallbackInfo ci) {
        if (file != null && ReplayModSound.get() != null) {
            ReplayModSound.get().getSoundtrackPlayer().rememberMcpr(file.toPath());
            System.out.println("[replaymodsound] opening replay file " + file);
        }
    }

    @Inject(
            method = "startReplay(Lcom/replaymod/replaystudio/replay/ReplayFile;ZZ)Lcom/replaymod/replay/ReplayHandler;",
            at = @At("RETURN")
    )
    private void replaymodsound$onStartReplay(
            ReplayFile replayFile,
            boolean checkModCompat,
            boolean loadCameraPath,
            CallbackInfoReturnable<ReplayHandler> cir
    ) throws IOException {
        ReplayHandler handler = cir.getReturnValue();
        if (handler != null && ReplayModSound.get() != null) {
            System.out.println("[replaymodsound] startReplay returned handler, loading soundtracks");
            ReplayModSound.get().getSoundtrackPlayer().onReplayOpened(handler.getReplayFile());
        }
    }
}
