package me.cortex.voxy.client.config;

import me.cortex.voxy.client.RenderStatistics;
import me.cortex.voxy.client.config.SodiumConfigBuilder.*;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.ClientSessionEvents;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.option.Range;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class VoxyConfigMenu implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(ConfigBuilder B) {
        var CFG = VoxyConfig.CONFIG;

        var cc = B.registerModOptions("voxy", "Voxy", VoxyCommon.MOD_VERSION)
                .setIcon(Identifier.parse("voxy:icon.png"));

        SodiumConfigBuilder.buildToSodium(B, cc, CFG::save, postOp->{
                    postOp.register("voxy:update_threads", ()->{
                        var instance = VoxyCommon.getInstance();
                        if (instance != null) {
                            instance.updateDedicatedThreads();
                        }
                    }, "voxy:enabled");
                },
                new Page(Component.translatable("voxy.config.general"),
                        new Group(
                                new BoolOption(
                                        "voxy:enabled",
                                        Component.translatable("voxy.config.general.enabled"),
                                        ()->CFG.enabled, v->CFG.enabled=v)
                                        .setPostChangeRunner(c->{
                                            if (c) {
                                                if (ClientSessionEvents.inSession) {
                                                    VoxyCommon.createInstance();
                                                    var vrsh = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
                                                    if (vrsh != null && CFG.enableRendering) {
                                                        vrsh.createRenderer();
                                                    }
                                                }
                                            } else {
                                                var vrsh = (IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer;
                                                if (vrsh != null) {
                                                    vrsh.shutdownRenderer();
                                                }
                                                VoxyCommon.shutdownInstance();
                                            }
                                        }).setEnabler(null)
                        ), new Group(
                                new IntOption(
                                        "voxy:thread_count",
                                        Component.translatable("voxy.config.general.serviceThreads"),
                                        ()->CFG.serviceThreads, v->CFG.serviceThreads=v,
                                        new Range(1, CpuLayout.getCoreCount(), 1))
                                        .setPostChangeFlags("voxy:update_threads"),
                                new BoolOption(
                                        "voxy:use_sodium_threads",
                                        Component.translatable("voxy.config.general.useSodiumBuilder"),
                                        ()->!CFG.dontUseSodiumBuilderThreads, v->CFG.dontUseSodiumBuilderThreads=!v)
                                        .setPostChangeFlags("voxy:update_threads", OptionFlag.REQUIRES_RENDERER_RELOAD.getId().toString())
                        ), new Group(
                                new BoolOption(
                                        "voxy:ingest_enabled",
                                        Component.translatable("voxy.config.general.ingest"),
                                        ()->CFG.ingestEnabled, v->CFG.ingestEnabled=v)
                        )
                ).setEnabler("voxy:enabled"),
                new Page(Component.translatable("voxy.config.rendering"),
                        new Group(
                                new BoolOption(
                                        "voxy:rendering",
                                        Component.translatable("voxy.config.general.rendering"),
                                        ()->CFG.enableRendering, v->CFG.enableRendering=v)
                                        .setPostChangeRunner(c->{
                                            var vrsh = (IGetVoxyRenderSystem)Minecraft.getInstance().levelRenderer;
                                            if (vrsh != null) {
                                                if (c) {
                                                    vrsh.createRenderer();
                                                } else {
                                                    vrsh.shutdownRenderer();
                                                }
                                            }
                                        },"voxy:enabled", "voxy:renderer_reload")
                                        .setEnabler("voxy:enabled")
                        ), new Group(
                                new IntOption(
                                        "voxy:subdivsize",
                                        Component.translatable("voxy.config.general.subDivisionSize"),
                                        ()->subDiv2ln(CFG.subDivisionSize), v->CFG.subDivisionSize=ln2subDiv(v),
                                        new Range(0, SUBDIV_IN_MAX, 1))
                                        .setFormatter(v->Component.literal(Integer.toString(Math.round(ln2subDiv(v))))),
                                new IntOption(
                                        "voxy:render_distance",
                                        Component.translatable("voxy.config.general.renderDistance"),
                                        ()->CFG.sectionRenderDistance, v->CFG.sectionRenderDistance=v,
                                        new Range(2, 64, 1))
                                        .setFormatter(v->Component.literal(Integer.toString(v*32)))//Top level rd == 32 chunks
                                        .setPostChangeRunner(c->{
                                            var vrsh = (IGetVoxyRenderSystem)Minecraft.getInstance().levelRenderer;
                                            if (vrsh != null) {
                                                var vrs = vrsh.getVoxyRenderSystem();
                                                if (vrs != null) {
                                                    vrs.setRenderDistance(c);
                                                }
                                            }
                                        }, "voxy:rendering", "voxy:renderer_reload")
                        ), new Group(
                                new BoolOption(
                                        "voxy:eviromental_fog",
                                        Component.translatable("voxy.config.general.environmental_fog"),
                                        ()->CFG.useEnvironmentalFog, v->CFG.useEnvironmentalFog=v)
                                        .setPostChangeFlags(OptionFlag.REQUIRES_RENDERER_RELOAD.getId().toString())
                        ), new Group(
                                new BoolOption(
                                        "voxy:render_debug",
                                        Component.translatable("voxy.config.general.render_statistics"),
                                        ()-> RenderStatistics.enabled, v->RenderStatistics.enabled=v)
                                        .setPostChangeFlags(OptionFlag.REQUIRES_RENDERER_RELOAD.getId().toString()))
                ).setEnablerAND("voxy:enabled", "voxy:rendering"));

    }


    private static final int SUBDIV_IN_MAX = 100;
    private static final double SUBDIV_MIN = 28;
    private static final double SUBDIV_MAX = 256;
    private static final double SUBDIV_CONST = Math.log(SUBDIV_MAX/SUBDIV_MIN)/Math.log(2);

    //In range is 0->200
    //Out range is 28->256
    private static float ln2subDiv(int in) {
        return (float) (SUBDIV_MIN*Math.pow(2, SUBDIV_CONST*((double)in/SUBDIV_IN_MAX)));
    }

    //In range is ... any?
    //Out range is 0->200
    private static int subDiv2ln(float in) {
        return (int) (((Math.log(((double)in)/SUBDIV_MIN)/Math.log(2))/SUBDIV_CONST)*SUBDIV_IN_MAX);
    }
}
