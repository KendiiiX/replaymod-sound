package fr.kendix.replaymodsound.mixin;

import com.replaymod.recording.packet.PacketListener;
import fr.kendix.replaymodsound.ReplayModSound;
import io.netty.channel.ChannelHandlerContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PacketListener.class, remap = false)
public abstract class PacketListenerMixin {
    @Inject(method = "channelInactive", at = @At("HEAD"))
    private void replaymodsound$channelInactive(ChannelHandlerContext ctx, CallbackInfo ci) {
        if (ReplayModSound.get() != null) {
            ReplayModSound.get().getRecordingLifecycle().onRecordingStopping((PacketListener) (Object) this);
        }
    }
}
