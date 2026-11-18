package kr.gcb.ragdoll.compat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import net.minecraft.server.level.ServerPlayer;

/** Reads the skin a player logged in with out of their profile (the signed "textures" property). */
public final class Skins {
    private Skins() {}

    /** A skin texture URL and whether it is drawn on the slim (three pixel arm) model. */
    public record Skin(String url, boolean slim) {}

    /** The player's skin, or null if their profile carries none (offline mode, or a default skin). */
    public static Skin of(ServerPlayer who) {
        try {
            for (var property : who.getGameProfile().properties().get("textures")) {
                JsonObject root = JsonParser.parseString(new String(Base64.getDecoder().decode(property.value()), StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject skin = root.getAsJsonObject("textures").getAsJsonObject("SKIN");
                if (skin == null || !skin.has("url")) continue;
                boolean slim = skin.has("metadata") && skin.getAsJsonObject("metadata").has("model")
                        && "slim".equals(skin.getAsJsonObject("metadata").get("model").getAsString());
                return new Skin(skin.get("url").getAsString(), slim);
            }
        } catch (RuntimeException unreadable) {
            // A profile that cannot be read is a player without a skin as far as a corpse is concerned.
        }
        return null;
    }
}
