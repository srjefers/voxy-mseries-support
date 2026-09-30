package me.cortex.voxy.client.core.rendering;

import java.util.Map;
import java.util.Set;
import java.lang.reflect.Method;
import net.caffeinemc.mods.sodium.client.gl.device.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionFlags;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.minecraft.core.SectionPos;
import org.lwjgl.system.MemoryUtil;
import static org.mockito.Mockito.*;

/** Real Sodium command buffers and lifecycle membership, independent of camera distance or timing. */
public final class DrawCoverageRegressionTest {
    public static void main(String[] args) throws Exception {
        var tracker=SectionCoverageTracker.INSTANCE;
        var generation=tracker.beginGeneration();
        tracker.publish(generation,99L,SectionCoverageTracker.Coverage.OPAQUE);
        Class<?> type;
        try { type=Class.forName("me.cortex.voxy.client.core.rendering.SodiumDrawCoverage"); }
        catch(ClassNotFoundException old) {
            require(tracker.coverageAt(99L)==SectionCoverageTracker.Coverage.NONE,
                    "uploaded-but-culled section still claims LOD coverage");return;
        }
        Object draws=type.getConstructor().newInstance(),owner=new Object(),oldOwner=new Object();
        var begin=type.getMethod("begin",Object.class,SectionCoverageTracker.Generation.class,long.class);
        var publish=type.getMethod("publish",Object.class,SectionCoverageTracker.Generation.class,long.class,String.class,Set.class);
        var mask=type.getMethod("mask",Object.class,SectionCoverageTracker.Generation.class,long.class);
        begin.invoke(draws,owner,generation,1L);
        require(((Map<?,?>)mask.invoke(draws,owner,generation,1L)).isEmpty(),"upload alone suppresses LOD");
        publish.invoke(draws,owner,generation,1L,"solid",Set.of(99L,100L));
        require(((Map<?,?>)mask.invoke(draws,owner,generation,1L)).size()==1,"pending data masked");
        publish.invoke(draws,owner,generation,1L,"translucent",Set.of(99L));
        require(((Map<?,?>)mask.invoke(draws,owner,generation,1L)).get(99L)==SectionCoverageTracker.Coverage.OPAQUE,"pass flags overwrite solid");
        require(!(boolean)begin.invoke(draws,owner,generation,1L),"same-frame begin cleared membership");
        begin.invoke(draws,owner,generation,2L);
        require(((Map<?,?>)mask.invoke(draws,owner,generation,2L)).isEmpty(),"backward travel reused old draw membership");
        require(!(boolean)publish.invoke(draws,owner,generation,1L,"solid",Set.of(99L)),"old frame accepted");
        require(!(boolean)publish.invoke(draws,oldOwner,generation,2L,"solid",Set.of(99L)),"old renderer accepted");
        publish.invoke(draws,owner,generation,2L,"solid",Set.of(99L));
        tracker.publish(generation,99L,SectionCoverageTracker.Coverage.NONE);
        require(((Map<?,?>)mask.invoke(draws,owner,generation,2L)).isEmpty(),"unload remained drawable");
        var next=tracker.beginGeneration();
        tracker.publish(next,99L,SectionCoverageTracker.Coverage.OPAQUE);
        require(((Map<?,?>)mask.invoke(draws,owner,generation,2L)).isEmpty(),"stale manager masked replacement data");
        begin.invoke(draws,oldOwner,next,3L);
        publish.invoke(draws,oldOwner,next,3L,"cutout",Set.of(99L));
        require(((Map<?,?>)mask.invoke(draws,oldOwner,next,3L)).isEmpty(),"partial cutout became an opaque section box");
        var release=type.getMethod("release",Object.class);
        publish.invoke(draws,oldOwner,next,3L,"solid",Set.of(99L));
        release.invoke(draws,owner);
        require(!((Map<?,?>)mask.invoke(draws,oldOwner,next,3L)).isEmpty(),"obsolete shutdown cleared current renderer");
        release.invoke(draws,oldOwner);
        require(((Map<?,?>)mask.invoke(draws,oldOwner,next,3L)).isEmpty(),"shutdown retained coverage owner");
        System.out.println("PASS: upload/draw separation, backward travel, duplicates, unload and stale generations");
        commandMembership();
    }
    private static void commandMembership() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        var region=mock(RenderRegion.class);
        var list=new ChunkRenderList(region);
        var first=new RenderSection(region,0,0,0);var second=new RenderSection(region,1,0,0);
        var info=mock(BuiltSectionInfo.class);
        var flags=BuiltSectionInfo.class.getDeclaredField("flags");flags.setAccessible(true);flags.set(info,RenderSectionFlags.MASK_HAS_BLOCK_GEOMETRY);
        first.setInfo(info);second.setInfo(info);
        when(region.getSection(0)).thenReturn(first);when(region.getSection(1)).thenReturn(second);
        list.add(0,RenderSectionFlags.MASK_HAS_BLOCK_GEOMETRY);list.add(1,RenderSectionFlags.MASK_HAS_BLOCK_GEOMETRY);
        var collect=Class.forName("me.cortex.voxy.client.core.rendering.SodiumDrawableSections")
                .getMethod("collect",ChunkRenderList.class,SectionRenderDataStorage.class,MultiDrawBatch.class,boolean.class);
        var storage=new SectionRenderDataStorage(false);var batch=new MultiDrawBatch(16);
        try {
            long a=storage.getDataPointer(0),b=storage.getDataPointer(1);
            SectionRenderDataUnsafe.setBaseVertex(a,100);SectionRenderDataUnsafe.setVertexCount(a,0,8);
            SectionRenderDataUnsafe.setBaseVertex(b,200);SectionRenderDataUnsafe.setVertexCount(b,0,4);
            batch.isFilled=true;batch.size=2;
            MemoryUtil.memPutInt(batch.pBaseVertex,104);MemoryUtil.memPutInt(batch.pElementCount,6);
            MemoryUtil.memPutInt(batch.pBaseVertex+4,200);MemoryUtil.memPutInt(batch.pElementCount+4,0);
            require(collect.invoke(null,list,storage,batch,false).equals(Set.of(SectionPos.asLong(0,0,0))),"empty/rejected commands claimed coverage");
            batch.size=0;
            require(((Set<?>)collect.invoke(null,list,storage,batch,false)).isEmpty(),"empty batch masked terrain");
            batch.size=1;SectionRenderDataUnsafe.setBaseVertex(a,0xfffffff0L);
            MemoryUtil.memPutInt(batch.pBaseVertex,0xfffffff4);
            require(((Set<?>)collect.invoke(null,list,storage,batch,false)).size()==1,"unsigned vertex base mismatch");
            first.delete();
            require(((Set<?>)collect.invoke(null,list,storage,batch,false)).isEmpty(),"disposed geometry remained drawable");
            System.out.println("PASS: actual prepared Sodium commands: culled faces, zero counts, rejected batches, unsigned ranges, disposal");
        } finally {batch.delete();storage.delete();}
    }
    private static void require(boolean okay,String message) { if(!okay) throw new AssertionError(message); }
}
