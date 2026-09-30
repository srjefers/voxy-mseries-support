package me.cortex.voxy.client.core;

import org.joml.Matrix4fc;
import org.joml.Vector4f;

/** Diagnostic screen bounds for a 16-block section, using camera-relative coordinates. */
public final class SectionScreenProjection {
    private SectionScreenProjection() {}

    public static boolean touchesCenter(Matrix4fc mvp, double cameraX, double cameraY, double cameraZ,
                                        int sectionX, int sectionY, int sectionZ,
                                        int width, int height, int margin) {
        float minX=Float.POSITIVE_INFINITY,minY=Float.POSITIVE_INFINITY;
        float maxX=Float.NEGATIVE_INFINITY,maxY=Float.NEGATIVE_INFINITY;
        boolean inFront=false;
        for (int corner=0;corner<8;corner++) {
            var point=new Vector4f(
                    (float)(((long)sectionX<<4)+((corner&1)!=0?16:0)-cameraX),
                    (float)(((long)sectionY<<4)+((corner&2)!=0?16:0)-cameraY),
                    (float)(((long)sectionZ<<4)+((corner&4)!=0?16:0)-cameraZ),1);
            mvp.transform(point);
            if (!Float.isFinite(point.w) || point.w<=0.001f) continue;
            inFront=true;
            float x=(point.x/point.w*.5f+.5f)*width;
            float y=(.5f-point.y/point.w*.5f)*height;
            minX=Math.min(minX,x);maxX=Math.max(maxX,x);
            minY=Math.min(minY,y);maxY=Math.max(maxY,y);
        }
        return inFront && minX<=width/2f+margin && maxX>=width/2f-margin
                && minY<=height/2f+margin && maxY>=height/2f-margin;
    }
}
