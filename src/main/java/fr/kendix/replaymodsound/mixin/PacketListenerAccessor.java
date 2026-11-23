package fr.kendix.replaymodsound.mixin;

import com.replaymod.recording.packet.PacketListener;
import com.replaymod.replaystudio.replay.ReplayFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.nio.file.Path;

@Mixin(value = PacketListener.class, remap = false)
public interface PacketListenerAccessor {
    @Accessor("outputPath")
    Path replaymodsound$getOutputPath();

    @Accessor("replayFile")
    ReplayFile replaymodsound$getReplayFile();
}
