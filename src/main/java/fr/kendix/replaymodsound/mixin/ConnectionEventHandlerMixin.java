package fr.kendix.replaymodsound.mixin;

import com.replaymod.recording.handler.ConnectionEventHandler;
import com.replaymod.recording.packet.PacketListener;
import fr.kendix.replaymodsound.ReplayModSound;
import net.minecraft.network.ClientConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ConnectionEventHandler.class)
public abstract class ConnectionEventHandlerMixin {
    @Shadow(remap = false)
    public abstract PacketListener getPacketListener();

    @Inject(method = "onConnectedToServerEvent", at = @At("RETURN"), remap = false)
    private void replaymodsound$onConnected(ClientConnection connection, CallbackInfo ci) {
        PacketListener listener = getPacketListener();
        if (listener != null && ReplayModSound.get() != null) {
            ReplayModSound.get().getRecordingLifecycle().onRecordingStarted(listener);
        }
    }

    @Inject(method = "reset", at = @At("HEAD"), remap = false)
    private void replaymodsound$onReset(CallbackInfo ci) {
        PacketListener listener = getPacketListener();
        if (ReplayModSound.get() != null) {
            ReplayModSound.get().getRecordingLifecycle().onRecordingStopping(listener);
        }
    }
}
