package me.cortex.voxy.client.core.model;

import it.unimi.dsi.fastutil.bytes.ByteArrayFIFOQueue;
import me.cortex.voxy.common.util.MemoryBuffer;
import net.caffeinemc.mods.sodium.client.util.color.ColorSRGB;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

import static me.cortex.voxy.client.core.model.ModelFactory.LAYERS;
import static me.cortex.voxy.client.core.model.ModelFactory.MODEL_TEXTURE_SIZE;

public class MipGen {
    static {
        if (MODEL_TEXTURE_SIZE>16) throw new IllegalStateException("TODO: THIS MUST BE UPDATED, IT CURRENTLY ASSUMES 16 OR SMALLER SIZE");
    }
    private static final short[] SCRATCH = new short[MODEL_TEXTURE_SIZE*MODEL_TEXTURE_SIZE];
    private static final ByteArrayFIFOQueue QUEUE = new ByteArrayFIFOQueue(MODEL_TEXTURE_SIZE*MODEL_TEXTURE_SIZE);

    private static long getOffset(int bx, int by, int i) {
        bx += i&(MODEL_TEXTURE_SIZE-1);
        by += i/MODEL_TEXTURE_SIZE;
        return bx+by*MODEL_TEXTURE_SIZE*3;
    }

    private static final int[] CELL = new int[MODEL_TEXTURE_SIZE * MODEL_TEXTURE_SIZE];

    /** Render-thread RGB dilation shared by static bakes and animated sprite frames. */
    public static void dilateRgb(int[] cell) {
        if (cell.length != SCRATCH.length) throw new IllegalArgumentException("Wrong bake cell size");
        Arrays.fill(SCRATCH, (short) -1);
        for (int pos = 0; pos < cell.length; pos++) {
            if ((cell[pos] >>> 24) != 0) {
                SCRATCH[pos] = (short) pos;
                QUEUE.enqueue((byte) pos);
            }
        }
        if (QUEUE.isEmpty()) return;
        while (!QUEUE.isEmpty()) {
            int pos = Byte.toUnsignedInt(QUEUE.dequeueByte());
            int x = pos % MODEL_TEXTURE_SIZE, y = pos / MODEL_TEXTURE_SIZE;
            short next = (short) (SCRATCH[pos] + 0x0100);
            for (int direction = 3; direction >= 0; direction--) {
                int delta = 2 * (direction & 1) - 1;
                int nx = x + ((direction & 2) == 2 ? delta : 0);
                int ny = y + ((direction & 2) == 0 ? delta : 0);
                if (nx < 0 || nx >= MODEL_TEXTURE_SIZE || ny < 0 || ny >= MODEL_TEXTURE_SIZE) continue;
                int neighbor = nx + ny * MODEL_TEXTURE_SIZE;
                if ((next & 0xff00) < (SCRATCH[neighbor] & 0xff00)) {
                    SCRATCH[neighbor] = next;
                    QUEUE.enqueue((byte) neighbor);
                }
            }
        }
        for (int pos = 0; pos < cell.length; pos++) {
            int source = Short.toUnsignedInt(SCRATCH[pos]);
            if ((source & 0xff00) != 0) cell[pos] = cell[source & 0xff] & 0x00ffffff;
        }
    }

    private static void solidify(long baseAddr, byte mask) {
        for (int face = 0; face < 6; face++) {
            if (((mask >> face) & 1) == 0) continue;
            int x = (face >> 1) * MODEL_TEXTURE_SIZE, y = (face & 1) * MODEL_TEXTURE_SIZE;
            for (int i = 0; i < CELL.length; i++) CELL[i] = MemoryUtil.memGetInt(baseAddr + getOffset(x, y, i) * 4);
            dilateRgb(CELL);
            for (int i = 0; i < CELL.length; i++) MemoryUtil.memPutInt(baseAddr + getOffset(x, y, i) * 4, CELL[i]);
        }
    }

    public static void putTextures(boolean darkened, ColourDepthTextureData[] textures, MemoryBuffer into) {
        //if (MODEL_TEXTURE_SIZE != 16) {throw new IllegalStateException("THIS METHOD MUST BE REDONE IF THIS CONST CHANGES");}

        //TODO: need to use a write mask to see what pixels must be used to contribute to mipping
        // as in, using the depth/stencil info, check if pixel was written to, if so, use that pixel when blending, else dont

        final long addr = into.address;
        final int LENGTH_B = MODEL_TEXTURE_SIZE*3;
        byte solidMsk = 0;
        for (int i = 0; i < 6; i++) {
            int x = (i>>1)*MODEL_TEXTURE_SIZE;
            int y = (i&1)*MODEL_TEXTURE_SIZE;
            int j = 0;
            boolean anyTransparent = false;
            for (int t : textures[i].colour()) {
                int o = ((y+(j>>LAYERS))*LENGTH_B + ((j&(MODEL_TEXTURE_SIZE-1))+x))*4; j++;//LAYERS here is just cause faster
                //t = ((t&0xFF000000)==0)?0x00_FF_00_FF:t;//great for testing
                MemoryUtil.memPutInt(addr+o, t);
                anyTransparent |= ((t&0xFF000000)==0);
            }
            solidMsk |= (anyTransparent?1:0)<<i;
        }

        if (!darkened) {
            solidify(addr, solidMsk);
        }


        //Mip the scratch
        long dAddr = addr;
        for (int i = 0; i < LAYERS-1; i++) {
            long sAddr = dAddr;
            dAddr += (MODEL_TEXTURE_SIZE*MODEL_TEXTURE_SIZE*3*2*4)>>(i<<1);//is.. i*2 because shrink both MODEL_TEXTURE_SIZE by >>i so is 2*i total shift
            int width = (MODEL_TEXTURE_SIZE*3)>>(i+1);
            int sWidth = (MODEL_TEXTURE_SIZE*3)>>i;
            int height = (MODEL_TEXTURE_SIZE*2)>>(i+1);
            //TODO: OPTIMZIE THIS
            for (int px = 0; px < width; px++) {
                for (int py = 0; py < height; py++) {
                    long bp = sAddr + (px*2 + py*2*sWidth)*4;
                    int C00 = MemoryUtil.memGetInt(bp);
                    int C01 = MemoryUtil.memGetInt(bp+sWidth*4);
                    int C10 = MemoryUtil.memGetInt(bp+4);
                    int C11 = MemoryUtil.memGetInt(bp+sWidth*4+4);
                    MemoryUtil.memPutInt(dAddr + (px+py*width) * 4L, TextureUtils.mipColours(darkened, C00, C01, C10, C11));
                }
            }
        }

        /*
         */
    }

    public static void generateMipmaps(long[] textures, int size) {

    }
}
