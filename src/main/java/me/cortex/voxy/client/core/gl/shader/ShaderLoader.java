package me.cortex.voxy.client.core.gl.shader;


import net.minecraft.resources.Identifier;
import org.apache.commons.io.IOUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class ShaderLoader {
    public static String parse(String id) {
        var src =  "#version 460 core\n";
        src += String.join("\n", ShaderLoadingParser.parseRoot(Identifier.parse(id)));
        // Metal/Vulkan compile through glslang, which rejects printf unless
        // GL_EXT_debug_printf is on; strip it unless shader printf debugging is enabled.
        if (!me.cortex.voxy.client.core.rendering.util.PrintfDebugUtil.ENABLE_PRINTF_DEBUGGING) {
            src = src.replace("printf", "//printf");
        }
        return src;
    }


    //Use our own loader

    private static final class ShaderLoadingParser {
        private static final Pattern IMPORT_PATTERN = Pattern.compile("#import <(?<namespace>.*):(?<path>.*)>");
        public static List<String> parseRoot(Identifier id) {
            List<String> out = new ArrayList<>();
            for (var line : toLines(loadShaderAsset(id))) {
                if (line.startsWith("#version")) {
                    continue;
                } else if (line.startsWith("#import")) {
                    var match = IMPORT_PATTERN.matcher(line);
                    if (!match.matches()) throw new IllegalArgumentException("Unknown import: " + line);
                    var iid = Identifier.fromNamespaceAndPath(match.group("namespace"), match.group("path"));
                    out.addAll(parseRoot(iid));
                } else {
                    out.add(line);
                }
            }
            return out;
        }

        private static List<String> toLines(String src) {
            try {
                return new BufferedReader(new StringReader(src)).readAllLines();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        private static String loadShaderAsset(Identifier id) {
            String path = String.format("/assets/%s/shaders/%s", id.getNamespace(), id.getPath());
            try (InputStream in = ShaderLoadingParser.class.getResourceAsStream(path)) {
                if (in == null) {
                    throw new RuntimeException("Shader not found: " + path);
                } else {
                    return IOUtils.toString(in, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to read shader source for " + path, e);
            }
        }
    }

    /**
     * Parse + strip {@code printf(...)} calls so the resulting source compiles
     * through glslang/shaderc (which rejects printf without
     * GL_EXT_debug_printf). The legacy
     * {@code Shader.makeAuto(PrintfDebugUtil.PRINTF_processor)} flow applied
     * this strip automatically; M9 callers that bypass Shader.Builder and
     * feed source straight to {@code createComputePipeline}/
     * {@code createGraphicsPipeline} use this method instead.
     *
     * Same text substitution as PrintfDebugUtil.PRINTF_processor when shader
     * printf debugging is off (the default): every {@code printf} becomes
     * {@code //printf}, commenting the rest of the line.
     */
    public static String parseAndStripPrintf(String id) {
        return parse(id).replace("printf", "//printf");
    }
}
