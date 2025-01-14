package me.cortex.vulkanite.mixin;

import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkBuildBuffers.class)
public interface VertexFormatAccessor {
    @Accessor
    ChunkVertexType getVertexType();
}
