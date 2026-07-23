package dev.enchantmaster.web;

import dev.enchantmaster.EnchantMaster;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Best-effort resolution of item textures from the game/mod resource classpath.
 */
public final class ItemTextureResolver {
    private static final Map<String, byte[]> CACHE = new ConcurrentHashMap<String, byte[]>();

    private ItemTextureResolver() {
    }

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

    private static byte[] load(String namespace, String path) {
        ClassLoader cl = ItemTextureResolver.class.getClassLoader();
        String[] candidates = new String[]{
                "assets/" + namespace + "/textures/item/" + path + ".png",
                "assets/" + namespace + "/textures/items/" + path + ".png",
                "assets/" + namespace + "/textures/item/" + path.replace(".", "/") + ".png",
        };
        for (String candidate : candidates) {
            try {
                InputStream in = cl.getResourceAsStream(candidate);
                if (in != null) {
                    try {
                        return readAll(in);
                    } finally {
                        in.close();
                    }
                }
            } catch (Exception e) {
                EnchantMaster.LOGGER.debug("Failed reading texture {}: {}", candidate, e.toString());
            }
        }
        return null;
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) >= 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
