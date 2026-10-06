package me.cortex.voxy.client.core;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Renderer-generation camera history. Reads never advance it or expose its mutable matrices. */
public final class FrameTransformHistory {
    private Object generation;
    private long frame = Long.MIN_VALUE;
    private int width, height;
    private Transform current = new Transform(new Matrix4f(),new Matrix4f());
    private Transform previous = this.current;

    private static final class Transform {
        final Matrix4f projection, modelView, viewProjection;
        final Matrix4f projectionInverse, modelViewInverse, viewProjectionInverse;
        Transform(Matrix4fc projection, Matrix4fc modelView) {
            this.projection=new Matrix4f(projection);
            this.modelView=new Matrix4f(modelView);
            this.viewProjection=this.projection.mul(this.modelView,new Matrix4f());
            this.projectionInverse=new Matrix4f(this.projection).invert();
            this.modelViewInverse=new Matrix4f(this.modelView).invert();
            this.viewProjectionInverse=new Matrix4f(this.viewProjection).invert();
        }
    }

    public boolean advance(Object generation, long frame, int width, int height, boolean shadow,
                           Matrix4fc projection, Matrix4fc modelView) {
        if(shadow || width<=0 || height<=0) return false;
        boolean reset=this.generation!=generation || this.width!=width || this.height!=height;
        if(!reset && this.frame==frame) return false;
        // Construct before publishing, so a bad input cannot leave half a snapshot.
        var next=new Transform(projection,modelView);
        this.previous=reset || this.frame==Long.MIN_VALUE ? next : this.current;
        this.current=next;
        this.generation=generation; this.frame=frame; this.width=width; this.height=height;
        return true;
    }
    public long frame() { return this.frame; }
    public int width() { return this.width; }
    public int height() { return this.height; }
    public Matrix4f projection() { return new Matrix4f(this.current.projection); }
    public Matrix4f modelView() { return new Matrix4f(this.current.modelView); }
    public Matrix4f viewProjection() { return new Matrix4f(this.current.viewProjection); }
    public Matrix4f projectionInverse() { return new Matrix4f(this.current.projectionInverse); }
    public Matrix4f modelViewInverse() { return new Matrix4f(this.current.modelViewInverse); }
    public Matrix4f viewProjectionInverse() { return new Matrix4f(this.current.viewProjectionInverse); }
    public Matrix4f previousProjection() { return new Matrix4f(this.previous.projection); }
    public Matrix4f previousModelView() { return new Matrix4f(this.previous.modelView); }
    public Matrix4f previousViewProjection() { return new Matrix4f(this.previous.viewProjection); }
}
