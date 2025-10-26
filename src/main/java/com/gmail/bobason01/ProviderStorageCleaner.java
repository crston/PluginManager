package com.gmail.bobason01;

import org.bukkit.Bukkit;

import java.lang.reflect.Field;
import java.util.*;

public final class ProviderStorageCleaner {
    private ProviderStorageCleaner() {}

    public static void clean(String pluginName, String pluginVersion) {
        // identifier 형태: "플러그인 v 버전"
        String identifierFull = pluginName + " v" + pluginVersion;
        // 두 가지 케이스 모두 청소
        cleanInternal(identifierFull);
        cleanInternal(pluginName);
    }

    private static void cleanInternal(String identifier) {
        try {
            Object root = Bukkit.getPluginManager();
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            ArrayDeque<Object> q = new ArrayDeque<>();
            q.add(root);
            int removed = 0;

            while (!q.isEmpty()) {
                Object o = q.pollFirst();
                if (o == null) continue;
                if (!seen.add(o)) continue;

                if (o instanceof Map) {
                    removed += pruneMap((Map<?, ?>) o, identifier);
                } else if (o instanceof Collection) {
                    removed += pruneCollection((Collection<?>) o, identifier);
                }

                // io.papermc.* 내부 객체까지 재귀 탐색
                for (Field f : getAllFields(o.getClass())) {
                    if ((f.getModifiers() & java.lang.reflect.Modifier.STATIC) != 0) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(o);
                        if (v == null) continue;
                        Package p = v.getClass().getPackage();
                        String pn = (p == null) ? "" : p.getName();
                        if (v instanceof Map || v instanceof Collection || pn.startsWith("io.papermc")) {
                            q.addLast(v);
                        }
                    } catch (Throwable ignored) {}
                }
            }

            if (removed > 0) {
                Bukkit.getLogger().info("[PluginManagerX] ProviderStorageCleaner removed " + removed + " entries for " + identifier);
            }
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[PluginManagerX] ProviderStorageCleaner failed: " + t.getMessage());
        }
    }

    private static int pruneMap(Map<?, ?> m, String id) {
        int rm = 0;
        try {
            Iterator<? extends Map.Entry<?, ?>> it = m.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<?, ?> e = it.next();
                if (eq(e.getKey(), id) || eq(e.getValue(), id)) {
                    it.remove();
                    rm++;
                }
            }
        } catch (Throwable ignored) {}
        return rm;
    }

    private static int pruneCollection(Collection<?> c, String id) {
        int rm = 0;
        try {
            Iterator<?> it = c.iterator();
            while (it.hasNext()) {
                Object v = it.next();
                if (eq(v, id)) {
                    it.remove();
                    rm++;
                }
            }
        } catch (Throwable ignored) {}
        return rm;
    }

    private static boolean eq(Object o, String id) {
        if (o == null) return false;
        try {
            String s = String.valueOf(o);
            // 정확히 "이름 v 버전" 혹은 이름만 매칭
            return s.equalsIgnoreCase(id)
                    || s.equalsIgnoreCase(id.split(" ")[0]);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static List<Field> getAllFields(Class<?> c) {
        List<Field> out = new ArrayList<>();
        Class<?> k = c;
        while (k != null && k != Object.class) {
            try {
                Collections.addAll(out, k.getDeclaredFields());
            } catch (Throwable ignored) {}
            k = k.getSuperclass();
        }
        return out;
    }
}
