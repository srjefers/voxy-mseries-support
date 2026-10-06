package me.cortex.voxy.client.core.rendering;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import net.caffeinemc.mods.sodium.client.gl.device.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionFlags;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.minecraft.core.SectionPos;
import org.lwjgl.system.MemoryUtil;

/** Matches actual nonempty commands to their section's vertex allocation, including culled leading faces. */
public final class SodiumDrawableSections {
    private SodiumDrawableSections() {}

    public static Set<Long> collect(ChunkRenderList list, SectionRenderDataStorage storage,
                                   MultiDrawBatch batch, boolean translucent) {
        if (!batch.isFilled || batch.size == 0) return Set.of();
        long[] vertices = new long[batch.size];
        int count = 0;
        for (int i = 0; i < batch.size; i++) {
            if (MemoryUtil.memGetInt(batch.pElementCount + i * 4L) > 0)
                vertices[count++] = Integer.toUnsignedLong(MemoryUtil.memGetInt(batch.pBaseVertex + i * 4L));
        }
        if (count == 0) return Set.of();
        Arrays.sort(vertices, 0, count);
        var positions = new HashSet<Long>();
        var sections = list.sectionsWithGeometryIterator(translucent);
        if (sections == null) return positions;
        while (sections.hasNext()) {
            int localIndex = sections.nextByteAsInt();
            var section = list.getRegion().getSection(localIndex);
            if (section == null || !section.isBuilt() || section.isDisposed()
                    || (section.getFlags() & RenderSectionFlags.MASK_HAS_BLOCK_GEOMETRY) == 0) continue;
            long pointer = storage.getDataPointer(localIndex);
            long start = SectionRenderDataUnsafe.getBaseVertex(pointer), length = 0;
            for (int face = 0; face < ModelQuadFacing.COUNT; face++)
                length += SectionRenderDataUnsafe.getVertexCount(pointer, face);
            int index = Arrays.binarySearch(vertices, 0, count, start);
            if (index < 0) index = -index - 1;
            if (index < count && vertices[index] < start + length)
                positions.add(SectionPos.asLong(section.getChunkX(), section.getChunkY(), section.getChunkZ()));
        }
        return positions;
    }
}
