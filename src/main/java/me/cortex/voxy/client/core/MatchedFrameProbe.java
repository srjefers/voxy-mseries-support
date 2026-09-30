package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;
import java.util.Arrays;
import static org.lwjgl.opengl.GL33C.*;

/** Tiny pixel observations at real pass boundaries. Every entry point checks the same sampled frame. */
public final class MatchedFrameProbe {
    private MatchedFrameProbe() {}
    private static boolean claim(String stage) {
        var client=Minecraft.getInstance();
        return client!=null && client.level!=null && !IrisUtil.shadowsBeingRendered()
                && SectionProbe.captureStage(client.level.dimension().identifier().toString(),stage);
    }
    private static String identity(String stage) {
        return "[Metal-Frame-Probe] build="+buildLabel()+" frame="+SectionProbe.sampleFrame()+" stage="+stage+" ";
    }
    private static String buildLabel() { return BuildLabel.VALUE; }
    private static final class BuildLabel {
        static final String VALUE=load();
        static String load() {
            try(var in=MatchedFrameProbe.class.getResourceAsStream("/voxy-build.json")) {
                return com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in,java.nio.charset.StandardCharsets.UTF_8))
                        .getAsJsonObject().get("label").getAsString();
            } catch(Exception failure) { return "unlabelled-development"; }
        }
    }
    public static void waterProgram(int width,int height) {
        if(!claim("water-unblended")) return;
        try {
            var pixel=me.cortex.voxy.client.core.interop.WaterProgramProbe.capturePixel(width,height,
                    ()->glDrawArrays(GL_TRIANGLE_STRIP,0,4));
            Logger.info(identity("water-unblended")+"rgba="+Arrays.toString(pixel));
        } catch(Throwable failure) { Logger.warn(identity("water-unblended")+"readback failed: "+failure); }
    }
    public static void stage(String stage) {
        if(!claim(stage)) return;
        int[] viewport=new int[4];glGetIntegerv(GL_VIEWPORT,viewport);
        try { Logger.info(identity(stage)+readCurrent(viewport[2],viewport[3])); }
        catch(Throwable failure) { Logger.warn(identity(stage)+"readback failed: "+failure); }
    }
    public static void finalWorld() {
        if(!claim("world-output")) return;
        var target=Minecraft.getInstance().getMainRenderTarget();
        int read=glGetInteger(GL_READ_FRAMEBUFFER_BINDING),draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            if(target.getColorTexture() instanceof com.mojang.blaze3d.opengl.GlTexture color
                    && com.mojang.blaze3d.systems.RenderSystem.getDevice() instanceof com.mojang.blaze3d.opengl.GlDevice device) {
                int fbo=color.getFbo(device.directStateAccess(),target.getDepthTexture());
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER,fbo);
                Logger.info(identity("world-output")+readCurrent(target.width,target.height));
            }
        } catch(Throwable failure) { Logger.warn(identity("world-output")+"readback failed: "+failure); }
        finally { glBindFramebuffer(GL_READ_FRAMEBUFFER,read);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw); }
    }
    public static void material(String stage,IrisVoxyRenderPipelineData data,int[] targets,
                                int tint,int misc,int width,int height) {
        if(!claim(stage)) return;
        try {
            StringBuilder textures=new StringBuilder();
            int active=glGetInteger(GL_ACTIVE_TEXTURE);
            try {
                var names=data.getImageSet()==null?java.util.List.<String>of():data.getImageSet().orderedNames();
                for(int index=0;index<names.size();index++) {
                    String name=names.get(index);
                    if(name.equals("colortex19") || name.startsWith("vxDepth") || name.startsWith("depthtex")) {
                        glActiveTexture(GL_TEXTURE0+6+index);
                        textures.append(name).append('=').append(glGetInteger(GL_TEXTURE_BINDING_2D)).append(';');
                    }
                }
            } finally { glActiveTexture(active); }
            var packed=rectangle(misc,width,height);
            int red=Math.round(packed[0]*255),green=Math.round(packed[1]*255);
            int material=Math.round(packed[2]*255)|(Math.round(packed[3]*255)<<8);
            Logger.info(identity(stage)+readCurrent(width,height)+" targets="+Arrays.toString(targets)
                    +" materialId="+material+" face="+((red>>1)&7)+" light="+(red>>4)+","+(green>>4)
                    +" tint="+Arrays.toString(rectangle(tint,width,height))+" textures={"+textures+"}");
        } catch(Throwable failure) { Logger.warn(identity(stage)+"readback failed: "+failure); }
    }
    /** Changes only read-FBO, its read buffer, and pack-buffer binding; all are restored on failure. */
    static String readCurrent(int width,int height) {
        int read=glGetInteger(GL_READ_FRAMEBUFFER_BINDING),draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int pack=glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING),previousReadBuffer=0;
        boolean bound=false;
        try(var stack=MemoryStack.stackPush()) {
            glBindFramebuffer(GL_READ_FRAMEBUFFER,draw);bound=true;
            previousReadBuffer=glGetInteger(GL_READ_BUFFER);
            glBindBuffer(GL_PIXEL_PACK_BUFFER,0);
            int attachment=draw==0?GL_BACK:GL_COLOR_ATTACHMENT0;
            glReadBuffer(attachment);
            var color=stack.mallocFloat(4); glReadPixels(width/2,height/2,1,1,GL_RGBA,GL_FLOAT,color);
            int texture=draw==0?0:glGetFramebufferAttachmentParameteri(GL_READ_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            boolean hasDepth=draw==0?glGetFramebufferAttachmentParameteri(GL_READ_FRAMEBUFFER,GL_DEPTH,GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE)>0:
                    glGetFramebufferAttachmentParameteri(GL_READ_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)!=GL_NONE;
            float depth=Float.NaN;
            if(hasDepth) { var pixel=stack.mallocFloat(1);glReadPixels(width/2,height/2,1,1,GL_DEPTH_COMPONENT,GL_FLOAT,pixel);depth=pixel.get(0); }
            return "fbo="+draw+" texture="+texture+" viewport="+width+"x"+height+" rgba="+
                    Arrays.toString(new float[]{color.get(0),color.get(1),color.get(2),color.get(3)})+" depth="+depth;
        } finally {
            if(bound) glReadBuffer(previousReadBuffer);
            glBindFramebuffer(GL_READ_FRAMEBUFFER,read);glBindBuffer(GL_PIXEL_PACK_BUFFER,pack);
        }
    }
    private static float[] rectangle(int texture,int width,int height) {
        int read=glGetInteger(GL_READ_FRAMEBUFFER_BINDING),draw=glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int fbo=glGenFramebuffers();
        try {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER,fbo);
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_RECTANGLE,texture,0);
            int pack=glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
            try(var stack=MemoryStack.stackPush()) {
                glBindFramebuffer(GL_READ_FRAMEBUFFER,fbo);glReadBuffer(GL_COLOR_ATTACHMENT0);
                glBindBuffer(GL_PIXEL_PACK_BUFFER,0);
                var pixel=stack.mallocFloat(4);glReadPixels(width/2,height/2,1,1,GL_RGBA,GL_FLOAT,pixel);
                return new float[]{pixel.get(0),pixel.get(1),pixel.get(2),pixel.get(3)};
            } finally { glBindBuffer(GL_PIXEL_PACK_BUFFER,pack); }
        } finally {
            glBindFramebuffer(GL_READ_FRAMEBUFFER,read);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,draw);glDeleteFramebuffers(fbo);
        }
    }
}
