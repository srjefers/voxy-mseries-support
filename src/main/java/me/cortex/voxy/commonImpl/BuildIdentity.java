package me.cortex.voxy.commonImpl;

import com.google.gson.JsonParser;
import me.cortex.voxy.common.Logger;
import net.fabricmc.loader.api.ModContainer;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Logs the actual loaded artifact, independently of its filename. */
final class BuildIdentity {
    private BuildIdentity() {}

    static void log(ModContainer mod) {
        try (var stream = BuildIdentity.class.getResourceAsStream("/voxy-build.json")) {
            if (stream == null) throw new IllegalStateException("Missing voxy-build.json");
            var identity = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            Logger.info("[Voxy-Build] label=" + identity.get("label").getAsString()
                    + " source=" + identity.get("sourceDigest").getAsString()
                    + " upstreamTarget=" + identity.get("upstreamTarget").getAsString());
            for (var origin : mod.getOrigin().getPaths()) {
                String artifactDigest = "development-directory";
                if (Files.isRegularFile(origin)) {
                    var digest = MessageDigest.getInstance("SHA-256");
                    try (var input = new DigestInputStream(Files.newInputStream(origin), digest)) {
                        input.transferTo(java.io.OutputStream.nullOutputStream());
                    }
                    artifactDigest = HexFormat.of().formatHex(digest.digest());
                }
                Logger.info("[Voxy-Build] origin=" + origin + " jarSHA256=" + artifactDigest);
            }
        } catch (Exception error) {
            Logger.error("[Voxy-Build] Cannot verify loaded build identity", error);
        }
    }
}
