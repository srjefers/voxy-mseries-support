package me.cortex.voxy.client.core;

/** Contract ownership and animation tests established before their production helpers. */
public final class WaterFrameRegressionTest {
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private interface Case { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        int failures=0;
        for(Case test:new Case[]{WaterFrameRegressionTest::policy,WaterFrameRegressionTest::frames,WaterFrameRegressionTest::animation,WaterFrameRegressionTest::spriteClock}) {
            try{test.run();}catch(Throwable error){failures++;System.out.println("FAIL: "+error);}
        }
        if(failures>0)throw new AssertionError(failures+" water frame checks failed");
    }
    private static void policy() throws Exception {
        Class<?> policy=Class.forName("me.cortex.voxy.client.core.MetalMaterialPolicy");
        var select=policy.getMethod("select",boolean.class,boolean.class);
        Object generic=select.invoke(null,false,false),bsl=select.invoke(null,true,false);
        require((boolean)policy.getMethod("opaque").invoke(generic),"generic contract fell into prelit mode");
        require(!(boolean)policy.getMethod("legacyWater").invoke(generic),"BSL masking leaked into generic material path");
        require((boolean)policy.getMethod("legacyWater").invoke(bsl),"explicit BSL mode lost");
        var usesTransBound=policy.getMethod("translucentBoundMask");
        require(!(boolean)usesTransBound.invoke(generic),"coarse Sodium AABB still discards generic water at the boundary");
        require((boolean)usesTransBound.invoke(bsl),"legacy BSL translucent masking changed");
        Object shadersOff=java.lang.Enum.valueOf((Class)policy,"SHADERS_OFF");
        require((boolean)usesTransBound.invoke(shadersOff),"shaders-off water masking changed");
        System.out.println("PASS: fixed generic/BSL rendering policy");
    }
    private static void frames() throws Exception {
        Class<?> state=Class.forName("me.cortex.voxy.client.core.MaterialFrameState");
        Object generation=new Object(),other=new Object(),frames=state.getConstructor(Object.class).newInstance(generation);
        var begin=state.getMethod("begin",Object.class,int.class,boolean.class);
        var publish=state.getMethod("publish",int.class);
        var consume=state.getMethod("consume",Object.class,int.class);
        require(!(boolean)begin.invoke(frames,generation,1,true),"shadow pass rendered material frame");
        require((boolean)begin.invoke(frames,generation,1,false),"normal frame rejected");
        require(!(boolean)consume.invoke(frames,generation,1),"incomplete frame resolved");
        publish.invoke(frames,1);
        require(!(boolean)consume.invoke(frames,other,1),"obsolete Iris generation resolved");
        require((boolean)consume.invoke(frames,generation,1),"published frame unavailable");
        require(!(boolean)consume.invoke(frames,generation,1),"frame resolved twice");
        require(!(boolean)begin.invoke(frames,generation,1,false),"frame rendered twice");
        require((boolean)begin.invoke(frames,generation,2,false),"next frame rejected");
        require(!(boolean)consume.invoke(frames,generation,2),"previous water leaked into incomplete frame");
        System.out.println("PASS: shadow, generation, incomplete, duplicate and next-frame ownership");
    }
    private static void animation() throws Exception {
        Class<?> animation=Class.forName("me.cortex.voxy.client.core.model.WaterAnimationFrames");
        var blend=animation.getMethod("blend",int.class,int.class,float.class,float.class);
        int halfway=(int)blend.invoke(null,0xff0000ff,0xffff0000,0.5f,180f/255f);
        require((halfway>>>24)==180,"animated sprite replaced baked water opacity");
        require((halfway&255)==127 && ((halfway>>>16)&255)==127,"animation interpolation missing");
        int transparent=(int)blend.invoke(null,0,0,0.5f,1f);require(transparent==0,"animation invented alpha");
        System.out.println("PASS: sprite interpolation preserves baked opacity and transparent pixels");
    }
    private static void spriteClock() throws Exception {
        Class<?> stateType=Class.forName("net.minecraft.client.renderer.texture.SpriteContents$AnimationState");
        Object state=org.mockito.Mockito.mock(stateType);
        Object animation=org.mockito.Mockito.mock(net.minecraft.client.renderer.texture.SpriteContents.AnimatedTexture.class);
        var list=net.minecraft.client.renderer.texture.SpriteContents.AnimatedTexture.class.getDeclaredField("frames");list.setAccessible(true);
        list.set(animation,java.util.List.of(new net.minecraft.client.renderer.texture.SpriteContents.FrameInfo(5,4),new net.minecraft.client.renderer.texture.SpriteContents.FrameInfo(1,2)));
        var info=stateType.getDeclaredField("animationInfo");info.setAccessible(true);info.set(state,animation);
        var frame=stateType.getDeclaredField("frame");frame.setAccessible(true);frame.setInt(state,1);
        var sub=stateType.getDeclaredField("subFrame");sub.setAccessible(true);sub.setInt(state,1);
        Class<?> helper=Class.forName("me.cortex.voxy.client.core.model.WaterAnimationFrames");
        Object position=helper.getMethod("position",stateType).invoke(null,state);
        require((int)position.getClass().getMethod("frame").invoke(position)==1,"world time replaced sprite frame");
        require((int)position.getClass().getMethod("next").invoke(position)==0,"last animation frame did not wrap");
        require((float)position.getClass().getMethod("fraction").invoke(position)==0.5f,"variable frame timing ignored");
        System.out.println("PASS: actual Minecraft animation state, variable times and frame wrap");
    }

}
