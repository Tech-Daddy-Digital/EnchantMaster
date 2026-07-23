package dev.enchantmaster.web;

import dev.enchantmaster.EnchantMaster;

import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Best-effort resolution of item textures from the game/mod resource classpath.
 * Falls back to null when models are complex or missing.
 */
public final class ItemTextureResolver {
    private static final Map<String, byte[]> CACHE = new ConcurrentHashMap<>();

    private ItemTextureResolver() {
    }

    @Nullable
    public static byte[] resolvePng(String namespace, String path) {
        String key = namespace + ":" + path;
        if (CACHE.containsKey(key)) {
            return CACHE.get(key);
        }
        byte[] data = load(namespace, path);
        if (data != null) {
            CACHE.put(key, data);
        }
        return data;
    }

    @Nullable
    private static byte[] load(String namespace, String path) {
        ClassLoader cl = ItemTextureResolver.class.getClassLoader();
        // Common vanilla/mod item texture locations
        String[] candidates = new String[]{
                "assets/" + namespace + "/textures/item/" + path + ".png",
                "assets/" + namespace + "/textures/items/" + path + ".png",
                "assets/" + namespace + "/textures/item/" + path.replace(".", "/") + ".png",
        };
        for (String candidate : candidates) {
            try (InputStream in = cl.getResourceAsStream(candidate)) {
                if (in != null) {
                    return in.readAllBytes();
                }
            } catch (Exception e) {
                EnchantMaster.LOGGER.debug("Failed reading texture {}: {}", candidate, e.toString());
            }
        }
        return null;
    }
}
