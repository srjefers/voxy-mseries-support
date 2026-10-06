package me.cortex.voxy.client.core;

import me.cortex.voxy.common.world.WorldEngine;
import java.lang.reflect.Method;
import java.util.Set;

/** Bounded, matched-frame diagnostics must not retain stale draw lists or read outside their session. */
public final class SectionProbeRegressionTest {
    public static void main(String[] args) throws Exception {
        Object world=new Object(),old=new Object();
        SectionProbe.start(world,"overworld",-1,70,33,0);
        long position=WorldEngine.getWorldSectionId(0,-1,2,1);
        SectionProbe.record(old,position,"build","stale",1);
        SectionProbe.record(world,position,"build","12 quads",2);
        var first=SectionProbe.snapshot(world,"overworld",3);
        require(first!=null && first.toString().contains("12 quads") && !first.toString().contains("stale"),"coordinate/owner");
        require(SectionProbe.snapshot(world,"overworld",499_999_999)==null,"rate limit");
        require(SectionProbe.snapshot(world,"overworld",500_000_003)!=null,"second sample");
        require(SectionProbe.snapshot(world,"overworld",60_000_000_000L)==null,"expiry");
        Method begin=SectionProbe.class.getMethod("beginFrame",Object.class,String.class,long.class,long.class);
        Method stage=SectionProbe.class.getMethod("captureStage",String.class,String.class);
        Method frame=SectionProbe.class.getMethod("sampleFrame");
        Method status=SectionProbe.class.getMethod("status",long.class);
        SectionProbe.startView(world,"overworld",0);
        begin.invoke(null,world,"overworld",10L,1L);
        require((long)frame.invoke(null)==10L,"frame identity");
        require((boolean)stage.invoke(null,"overworld","opaque"),"opaque stage missing");
        require(!(boolean)stage.invoke(null,"overworld","opaque"),"duplicate stage readback");
        require((boolean)stage.invoke(null,"overworld","translucent"),"layers sampled different frames");
        require(!(boolean)stage.invoke(null,"nether","final"),"dimension leak");
        SectionProbe.recordDraws(world,"solid",Set.of(22L),2);
        require(SectionProbe.snapshot(world,"overworld",3).draws().get("solid").equals(Set.of(22L)),"draw membership");
        require(SectionProbe.snapshot(world,"overworld",4)==null,"duplicate Metal readback");
        begin.invoke(null,world,"overworld",11L,100_000_000L);
        require(!(boolean)stage.invoke(null,"overworld","translucent"),"new frame sampled too early");
        begin.invoke(null,world,"overworld",12L,500_000_001L);
        require(SectionProbe.snapshot(world,"overworld",500_000_002L).draws().isEmpty(),"previous frame membership retained");
        require(status.invoke(null,500_000_003L).toString().contains("samples=2"),"sample count");
        SectionProbe.stop(old);
        require((boolean)stage.invoke(null,"overworld","final"),"obsolete renderer stopped active probe");
        begin.invoke(null,world,"overworld",13L,60_000_000_000L);
        require(!(boolean)stage.invoke(null,"overworld","final"),"post-expiry readback");
        require(status.invoke(null,60_000_000_001L).toString().contains("expired"),"missing expiry reason");
        SectionProbe.startView(world,"overworld",0);
        begin.invoke(null,world,"nether",14L,1L);
        require(status.invoke(null,2L).toString().contains("dimension changed"),"dimension stop reason");
        SectionProbe.startView(world,"overworld",0); SectionProbe.stop(world);
        require(status.invoke(null,1L).toString().contains("renderer stopped"),"owner release");
        SectionProbe.startView(world,"overworld",0); SectionProbe.off();
        require(!(boolean)stage.invoke(null,"overworld","opaque"),"off still reads");
        var mvp=new org.joml.Matrix4f().perspective((float)Math.toRadians(70),16f/9f,.1f,1000f);
        require(SectionScreenProjection.touchesCenter(mvp,8,8,0,0,0,-2,1280,720,16)
                && !SectionScreenProjection.touchesCenter(mvp,8,8,0,10,0,-2,1280,720,16),"screen section projection");
        System.out.println("PASS: coordinate lifecycle, matched frames/layers, duplicate stages, stale draws, expiry/status and owner release");
    }
    private static void require(boolean okay,String message) { if(!okay) throw new AssertionError(message); }
}
