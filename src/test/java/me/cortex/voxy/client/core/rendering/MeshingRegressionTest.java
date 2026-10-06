package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.client.core.rendering.building.RenderDataFactory;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.util.Arrays;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public final class MeshingRegressionTest {
    private static int failures;
    private interface Case { void run() throws Exception; }
    private static void check(String name, Case test) {
        try { test.run(); System.out.println("PASS: " + name); }
        catch (Throwable error) { failures++; System.err.println("FAIL: " + name + ": " + error); }
    }
    private static WorldSection section(int x, int y, int z) {
        var result = WorldSection._createRawUntrackedUnsafeSection(0, x, y, z);
        Arrays.fill(result._unsafeGetRawDataArray(), 0);
        return result;
    }
    private static ModelFactory models(long firstMetadata, long secondMetadata) {
        var models = mock(ModelFactory.class);
        when(models._unsafeRawAccess()).thenReturn(new int[]{0, 10, 11});
        when(models.getModelId(1)).thenReturn(10); when(models.getModelId(2)).thenReturn(11);
        when(models.getModelMetadataFromClientId(10)).thenReturn(firstMetadata);
        when(models.getModelMetadataFromClientId(11)).thenReturn(secondMetadata);
        return models;
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); Logger.SHUTUP = true;
        check("model emission bits do not classify emissive blocks as plants", () -> {
            long emission = 15L << 55;
            if (me.cortex.voxy.client.core.model.ModelQueries.isPlant(emission)) throw new AssertionError("emission overlaps plant flag");
            if (!me.cortex.voxy.client.core.model.ModelQueries.isPlant(emission | (1L << 59))) throw new AssertionError("plant flag lost");
        });
        for (int neighborLight : new int[]{0x52, 0xf7}) {
            check("emissive cube retains higher neighbor light and sky nibble="+neighborLight, () -> {
                var factory=new RenderDataFactory(null,models((64L<<48)|(12L<<55),0),false);
                var section=section(0,0,0);
                Arrays.fill(section._unsafeGetRawDataArray(), (long)neighborLight<<56);
                section._unsafeGetRawDataArray()[WorldSection.getIndex(8,8,8)]=1L<<27;
                try {
                    var mesh=factory.generateMesh(section);
                    try {
                        if(mesh.geometryBuffer.size!=48) throw new AssertionError("expected six cube faces");
                        for(int i=0;i<6;i++) {
                            long quad=org.lwjgl.system.MemoryUtil.memGetLong(mesh.geometryBuffer.address+i*8L);
                            if(((quad>>>59)&15)!=Math.max(12,neighborLight>>>4) || ((quad>>>55)&15)!=(neighborLight&15)) throw new AssertionError("emission or sky nibble was lost: "+Long.toHexString(quad));
                        }
                    } finally { mesh.free(); }
                } finally { factory.free(); }
            });
        }
        check("single-plane geometry has non-negative packed bounds", () -> {
            long metadata = 0xffffffffffffL;
            metadata = (metadata & ~(0xffL << 8)) | (8L << 8); // only +Y face, uses self lighting
            var factory = new RenderDataFactory(null, models(metadata, 0), false);
            var section = section(0, 0, 0);
            section._unsafeGetRawDataArray()[WorldSection.getIndex(8, 8, 8)] = 1L << 27;
            try {
                var mesh = factory.generateMesh(section);
                try {
                    if (mesh.geometryBuffer.size != 8) throw new AssertionError("fixture must emit one plane");
                    if (((mesh.aabb >>> 15) & 31) != 0 || ((mesh.aabb >>> 20) & 31) != 0 || ((mesh.aabb >>> 25) & 31) != 0)
                        throw new AssertionError("flat bounds overflowed into other dimensions: " + Integer.toHexString(mesh.aabb));
                } finally { mesh.free(); }
            } finally { factory.free(); }
        });
        check("same model with open opposite face still emits visible face", () -> {
            var method = RenderDataFactory.class.getDeclaredMethod("shouldMeshNonOpaqueBlockFace", int.class, long.class, long.class, long.class, long.class);
            method.setAccessible(true);
            for (int face = 0; face < 6; face++) {
                long metadata = 1L << (face * 8); // this face occludes; its opposite does not
                long quad = 10L << 26;
                if (!(boolean)method.invoke(null, face, quad, metadata, quad, metadata))
                    throw new AssertionError("culled visible face " + face + " using self occlusion");
            }
        });
        for (int side = 0; side < 2; side++) {
            final int direction = side;
            check("X-border uses neighbor's opposite face, side=" + side, () -> {
                var world = mock(WorldEngine.class);
                when(world.acquire(anyInt(), anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> {
                    var neighbor = section(invocation.getArgument(1), invocation.getArgument(2), invocation.getArgument(3));
                    neighbor._unsafeGetRawDataArray()[WorldSection.getIndex(direction == 0 ? 31 : 0, 8, 8)] = 2L << 27;
                    neighbor.acquire(); return neighbor;
                });
                int oppositeFace = 4 | (1 - direction);
                var factory = new RenderDataFactory(world, models(64L << 48, 1L << (oppositeFace * 8)), false);
                var section = section(0, 0, 0);
                section._unsafeGetRawDataArray()[WorldSection.getIndex(direction == 0 ? 0 : 31, 8, 8)] = 1L << 27;
                try {
                    var mesh = factory.generateMesh(section);
                    try { if (mesh.geometryBuffer.size != 5 * 8) throw new AssertionError("expected five visible cube faces, got " + mesh.geometryBuffer.size / 8); }
                    finally { mesh.free(); }
                } finally { factory.free(); }
            });
        }
        for (int face=0;face<6;face++) {
            final int direction=face;
            check("all six borders use the neighbor opposite face, direction="+face,()->{
                int axis=direction/2, side=direction%2;
                int[] self={8,8,8}, neighbor={8,8,8};
                int coordinate=axis==0?1:axis==1?2:0;
                self[coordinate]=side==0?0:31; neighbor[coordinate]=side==0?31:0;
                var world=mock(WorldEngine.class);
                when(world.acquire(anyInt(),anyInt(),anyInt(),anyInt())).thenAnswer(call->{
                    var next=section(call.getArgument(1),call.getArgument(2),call.getArgument(3));
                    next._unsafeGetRawDataArray()[WorldSection.getIndex(neighbor[0],neighbor[1],neighbor[2])]=2L<<27;
                    next.acquire();return next;
                });
                var factory=new RenderDataFactory(world,models(64L<<48,1L<<((direction^1)*8)),false);
                var current=section(0,0,0);current._unsafeGetRawDataArray()[WorldSection.getIndex(self[0],self[1],self[2])]=1L<<27;
                try {var mesh=factory.generateMesh(current);try{if(mesh.geometryBuffer.size!=40)throw new AssertionError("missing/extra border face: "+mesh.geometryBuffer.size/8);}finally{mesh.free();}}finally{factory.free();}
            });
        }
        check("cutout plants cannot erase solid faces behind them",()->{
            long plant=(4L<<48)|(1L<<59)|0xffffL; // double sided, absent down/up, open side faces
            var factory=new RenderDataFactory(null,models(64L<<48,plant),false);
            var current=section(0,0,0);
            current._unsafeGetRawDataArray()[WorldSection.getIndex(8,8,8)]=1L<<27;
            current._unsafeGetRawDataArray()[WorldSection.getIndex(8,9,8)]=2L<<27;
            try {var mesh=factory.generateMesh(current);try{
                if(mesh.geometryBuffer.size!=80)throw new AssertionError("solid cube plus four plant faces expected: "+mesh.geometryBuffer.size/8);
                if(mesh.offsets[2]-mesh.offsets[1]!=4)throw new AssertionError("plants lost double-sided batch");
            }finally{mesh.free();}}finally{factory.free();}
        });
        check("reused mesher does not inherit coverage from a previous section",()->{
            var factory=new RenderDataFactory(null,models(64L<<48,0),false);
            var full=section(0,0,0); var empty=section(1,0,0);
            full._unsafeGetRawDataArray()[WorldSection.getIndex(8,8,8)]=1L<<27;
            try {var first=factory.generateMesh(full);first.free();var second=factory.generateMesh(empty);try{if(!second.isEmpty())throw new AssertionError("stale geometry in empty section");}finally{second.free();}}finally{factory.free();}
        });
        if (failures > 0) throw new AssertionError(failures + " meshing regressions failed");
    }
}
