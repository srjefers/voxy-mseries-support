package me.cortex.voxy.client.core;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Consumer;

/** Dimension-owned observations. GPU stages share a single bounded main-world sample frame. */
public final class SectionProbe {
    public record Snapshot(String dimension,int x,int y,int z,boolean view,
                           Map<Long,Map<String,String>> stages,Map<String,Set<Long>> draws) {}
    private static final long DURATION=60_000_000_000L, INTERVAL=500_000_000L;
    private static volatile Session current;
    private static String lastResult="No probe has run.";
    private static final class Session {
        final Object owner;
        final String dimension;
        final int x,y,z;
        final boolean view;
        final long start;
        final Map<Long,Map<String,String>> stages=new LinkedHashMap<>();
        final Map<String,Set<Long>> draws=new LinkedHashMap<>();
        final Set<String> capturedStages=new HashSet<>();
        long lastSample=Long.MIN_VALUE, frame=Long.MIN_VALUE;
        boolean managed,selected;
        int samples;
        Consumer<String> completion=ignored->{};
        Session(Object owner,String dimension,int x,int y,int z,boolean view,long now) {
            this.owner=owner;this.dimension=dimension;this.x=x;this.y=y;this.z=z;this.view=view;this.start=now;
            if(!view) for(int level=0;level<=4;level++)
                this.stages.put(WorldEngine.getWorldSectionId(level,x>>(5+level),y>>(5+level),z>>(5+level)),new LinkedHashMap<>());
        }
    }
    private SectionProbe() {}
    private static void finish(String reason) {
        if(current==null) return;
        var finished=current; current=null;
        lastResult="Voxy probe completed: samples="+finished.samples+", reason="+reason+".";
        Logger.info("[Metal-Probe] "+lastResult);
        finished.completion.accept(lastResult);
    }
    public static synchronized void onCompletion(Consumer<String> listener) {
        if(current!=null) current.completion=listener;
    }
    public static synchronized void start(Object owner,String dimension,int x,int y,int z,long now) {
        finish("restarted");current=new Session(owner,dimension,x,y,z,false,now);
    }
    public static synchronized void startView(Object owner,String dimension,long now) {
        finish("restarted");current=new Session(owner,dimension,0,0,0,true,now);
    }
    public static synchronized void off() { finish("stopped by command"); }
    public static synchronized void stop(Object owner) { if(current!=null && current.owner==owner) finish("renderer stopped"); }
    public static synchronized String status(long now) {
        if(current!=null && now-current.start>=DURATION) finish("expired after 60 seconds");
        return current==null ? lastResult : "Voxy probe active: samples="+current.samples+", remaining="+
                Math.max(0,(DURATION-(now-current.start)+999_999_999L)/1_000_000_000L)+"s.";
    }
    public static boolean enabled(Object owner) {
        var session=current;
        return session!=null && session.owner==owner && System.nanoTime()-session.start<DURATION;
    }
    /** Called once before main-world uniforms; shadow passes never call this boundary. */
    public static synchronized void beginFrame(Object owner,String dimension,long frame,long now) {
        var session=current;
        if(session==null || session.owner!=owner) return;
        if(!session.dimension.equals(dimension)) { finish("dimension changed");return; }
        if(now-session.start>=DURATION) { finish("expired after 60 seconds");return; }
        if(session.managed && session.frame==frame) return;
        session.managed=true;session.frame=frame;session.selected=false;
        session.draws.clear();session.capturedStages.clear();
        if(session.lastSample!=Long.MIN_VALUE && now-session.lastSample<INTERVAL) return;
        session.lastSample=now;session.selected=true;session.samples++;
    }
    public static synchronized long sampleFrame() { return current!=null && current.selected ? current.frame : -1; }
    public static synchronized boolean captureStage(String dimension,String stage) {
        var session=current;
        return session!=null && session.view && session.selected && session.dimension.equals(dimension)
                && session.capturedStages.add(stage);
    }
    public static synchronized boolean shouldCaptureDraws(Object owner,String pass,long now) {
        var session=current;
        return session!=null && session.owner==owner && session.selected && session.capturedStages.add("draw:"+pass);
    }
    public static synchronized boolean shouldCaptureViewMaterial(String dimension,String layer,long now) {
        return captureStage(dimension,"material:"+layer);
    }
    public static synchronized void recordDraws(Object owner,String pass,Set<Long> positions,long now) {
        var session=current;
        if(session!=null && session.owner==owner && session.selected) session.draws.put(pass,Set.copyOf(positions));
    }
    public static void record(Object owner,long position,String stage,String detail) {
        if(enabled(owner)) record(owner,position,stage,detail,System.nanoTime());
    }
    public static synchronized void record(Object owner,long position,String stage,String detail,long now) {
        var session=current;
        if(session==null || session.owner!=owner || now-session.start>=DURATION) return;
        var stages=session.stages.get(position);
        if(stages!=null && (stages.containsKey(stage) || stages.size()<8))
            stages.put(stage,detail.length()>512?detail.substring(0,512):detail);
    }
    public static synchronized Snapshot snapshot(Object owner,String dimension,long now) {
        var session=current;
        if(session==null || session.owner!=owner) return null;
        if(!session.dimension.equals(dimension)) { finish("dimension changed");return null; }
        if(now-session.start>=DURATION) { finish("expired after 60 seconds");return null; }
        if(session.managed) {
            if(!session.selected || !session.capturedStages.add("metal")) return null;
        } else {
            // Coordinate snapshots can also be read without rendering in CPU diagnostics/tests.
            if(session.lastSample!=Long.MIN_VALUE && now-session.lastSample<INTERVAL) return null;
            session.lastSample=now;session.samples++;
        }
        var result=new LinkedHashMap<Long,Map<String,String>>();
        session.stages.forEach((position,stages)->result.put(position,Map.copyOf(stages)));
        return new Snapshot(dimension,session.x,session.y,session.z,session.view,Map.copyOf(result),Map.copyOf(session.draws));
    }
}
