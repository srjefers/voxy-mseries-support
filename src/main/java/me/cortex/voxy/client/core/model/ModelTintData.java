package me.cortex.voxy.client.core.model;

/** Six face-major 16/8/4/2 tint-weight mips, padded to a 2048-byte model slot. */
public final class ModelTintData {
    public static final int BYTES_PER_MODEL = 2048;
    public static final int BYTES_PER_FACE = 340;
    private ModelTintData() {}

    public static byte[] pack(ColourDepthTextureData[] faces) {
        if (faces.length != 6) throw new IllegalArgumentException("six faces required");
        byte[] result = new byte[BYTES_PER_MODEL];
        for (int face=0; face<6; face++) {
            var pixels = faces[face];
            if (pixels.width()!=16 || pixels.height()!=16) throw new IllegalArgumentException("16px faces required");
            int[] alpha = new int[256], tinted = new int[256];
            for (int i=0; i<256; i++) {
                alpha[i] = pixels.colour()[i] >>> 24;
                tinted[i] = (pixels.depth()[i] & 128) == 0 ? 0 : alpha[i];
            }
            int cursor = face * BYTES_PER_FACE;
            for (int size=16; size>=2; size>>=1) {
                for (int i=0; i<size*size; i++) result[cursor++] = (byte)(alpha[i]==0 ? 0 : (tinted[i]*255L + alpha[i]/2)/alpha[i]);
                if (size==2) break;
                int next=size/2;
                int[] a=new int[next*next], t=new int[next*next];
                for(int y=0;y<next;y++) for(int x=0;x<next;x++) {
                    int start=y*2*size+x*2, dst=y*next+x;
                    for(int dy=0;dy<2;dy++) for(int dx=0;dx<2;dx++) {
                        a[dst]+=alpha[start+dy*size+dx]; t[dst]+=tinted[start+dy*size+dx];
                    }
                }
                alpha=a; tinted=t;
            }
        }
        return result;
    }
}
