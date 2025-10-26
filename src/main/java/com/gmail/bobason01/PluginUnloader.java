package com.gmail.bobason01;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.SimplePluginManager;

import java.io.Closeable;
import java.lang.reflect.*;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

public final class PluginUnloader {
    private static final boolean ENABLE_DEEP_PURGE = false;
    private static final long EXECUTOR_SHUTDOWN_MS = 200L;

    private PluginUnloader() {}

    public static boolean unload(Plugin plugin) {
        if (plugin == null) return false;
        final String name = safe(plugin.getName());
        final String version = safe(plugin.getDescription() == null ? null : plugin.getDescription().getVersion());
        final String identifier = name + " v" + version;

        try {
            if (!plugin.isEnabled()) {
                fastUnregisterPhase(plugin, identifier);
                CommandCleaner.clearPluginCommands(plugin);
                clearBrigadierNodes(plugin); // 추가: Brigadier 노드 정리
                closeClassLoaderAndCaches(plugin);
                ProviderStorageCleaner.clean(plugin.getName(), plugin.getDescription().getVersion());
                if (ENABLE_DEEP_PURGE) safeDeepPurge(plugin);
                log("[PluginManager] Fully unloaded (already disabled) " + identifier);
                return true;
            }

            tryCancelScheduler(plugin);
            tryUnregisterServices(plugin);
            tryUnregisterHandlers(plugin);
            CommandCleaner.clearPluginCommands(plugin);
            clearBrigadierNodes(plugin); // 추가: Brigadier 노드 정리
            tryDisable(plugin);
            tryScrubSchedulerPending(plugin);
            tryRemoveFromSimplePluginManager(plugin);
            tryClearPaperProviders(identifier);
            closeClassLoaderAndCaches(plugin);
            ProviderStorageCleaner.clean(plugin.getName(), plugin.getDescription().getVersion());
            if (ENABLE_DEEP_PURGE) safeDeepPurge(plugin);

            log("[PluginManager] Fully unloaded " + identifier);
            return true;
        } catch (Throwable t) {
            logErr("[PluginManager] Failed to unload plugin " + identifier + ": " + t.getClass().getSimpleName() + " - " + t.getMessage());
            t.printStackTrace();
            return false;
        }
    }

    private static void tryCancelScheduler(Plugin plugin) {
        try { Bukkit.getScheduler().cancelTasks(plugin); } catch (Throwable ignored) {}
    }

    private static void tryScrubSchedulerPending(Plugin plugin) {
        try {
            Object craftScheduler = Bukkit.getScheduler();
            for (Field f : craftScheduler.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(craftScheduler);
                if (val == null) continue;
                if (val instanceof Collection) scrubCollectionByPlugin((Collection<?>) val, plugin);
                else if (val instanceof Map) scrubMapByPlugin((Map<?, ?>) val, plugin);
                else if (val.getClass().isArray()) {
                    int len = Array.getLength(val);
                    for (int i = 0; i < len; i++) {
                        Object el = Array.get(val, i);
                        if (owns(el, plugin)) Array.set(val, i, null);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void scrubCollectionByPlugin(Collection<?> col, Plugin plugin) {
        try {
            Iterator<?> it = col.iterator();
            while (it.hasNext()) if (owns(it.next(), plugin)) it.remove();
        } catch (Throwable ignored) {}
    }

    private static void scrubMapByPlugin(Map<?, ?> map, Plugin plugin) {
        try {
            Iterator<? extends Map.Entry<?, ?>> it = new ArrayList<>(map.entrySet()).iterator();
            while (it.hasNext()) {
                Map.Entry<?, ?> e = it.next();
                if (owns(e.getKey(), plugin) || owns(e.getValue(), plugin)) {
                    try { map.remove(e.getKey()); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void tryUnregisterServices(Plugin plugin) {
        try { Bukkit.getServicesManager().unregisterAll(plugin); } catch (Throwable ignored) {}
    }

    private static void tryUnregisterHandlers(Plugin plugin) {
        try { HandlerList.unregisterAll(plugin); } catch (Throwable ignored) {}
    }

    private static void tryDisable(Plugin plugin) {
        try { if (plugin.isEnabled()) Bukkit.getPluginManager().disablePlugin(plugin); } catch (Throwable ignored) {}
    }

    private static void tryRemoveFromSimplePluginManager(Plugin plugin) {
        try {
            PluginManager pm = Bukkit.getPluginManager();
            if (!(pm instanceof SimplePluginManager)) return;
            @SuppressWarnings("unchecked")
            List<Plugin> plugins = (List<Plugin>) getField(pm, "plugins");
            if (plugins != null) {
                List<Plugin> newList = new ArrayList<>(plugins);
                newList.remove(plugin);
                setField(pm, "plugins", newList);
            }
            @SuppressWarnings("unchecked")
            Map<String, Plugin> lookup = (Map<String, Plugin>) getField(pm, "lookupNames");
            if (lookup != null) {
                Map<String, Plugin> newMap = new LinkedHashMap<>(lookup);
                newMap.values().removeIf(p -> p != null && p.equals(plugin));
                setField(pm, "lookupNames", newMap);
            }
        } catch (Throwable ignored) {}
    }

    private static void tryClearPaperProviders(String identifier) {
        try {
            Object pm = Bukkit.getPluginManager();
            if (pm == null) return;
            for (Field f : pm.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(pm);
                if (val == null) continue;
                if (!val.getClass().getName().toLowerCase(Locale.ROOT).contains("providerstorage")) continue;
                clearMapsByIdentifier(val, identifier);
            }
            log("[PluginManager] Cleared Paper providers for " + identifier);
        } catch (Throwable t) {
            logErr("[PluginManager] clearPaperProviders failed: " + t.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void clearMapsByIdentifier(Object storage, String identifier) {
        try {
            for (Field f : storage.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v = f.get(storage);
                if (v instanceof Map) {
                    Map<Object, Object> mod = new LinkedHashMap<>((Map<Object, Object>) v);
                    mod.keySet().removeIf(k -> identifier.equalsIgnoreCase(String.valueOf(k)));
                    mod.values().removeIf(val -> identifier.equalsIgnoreCase(String.valueOf(val)));
                    f.set(storage, mod);
                } else if (v != null && v.getClass().getName().toLowerCase(Locale.ROOT).contains("providerstorage")) {
                    clearMapsByIdentifier(v, identifier);
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void closeClassLoaderAndCaches(Plugin plugin) {
        try {
            ClassLoader cl = plugin.getClass().getClassLoader();
            if (cl instanceof URLClassLoader) try { ((URLClassLoader) cl).close(); } catch (Throwable ignored) {}
            tryClearUrlClassPathCaches(cl);
        } catch (Throwable ignored) {}
    }

    private static void tryClearUrlClassPathCaches(ClassLoader cl) {
        try {
            Field ucpField = findField(cl.getClass(), "ucp");
            if (ucpField == null) return;
            ucpField.setAccessible(true);
            Object ucp = ucpField.get(cl);
            if (ucp == null) return;

            Field loadersF = findField(ucp.getClass(), "loaders");
            if (loadersF != null) {
                loadersF.setAccessible(true);
                Object loaders = loadersF.get(ucp);
                if (loaders instanceof List) for (Object loader : new ArrayList<>((List<?>) loaders)) tryCloseJarLoader(loader);
            }
            Field lmapF = findField(ucp.getClass(), "lmap");
            if (lmapF != null) {
                lmapF.setAccessible(true);
                Object lmap = lmapF.get(ucp);
                if (lmap instanceof Map) for (Object loader : new ArrayList<>(((Map<?, ?>) lmap).values())) tryCloseJarLoader(loader);
            }
        } catch (Throwable ignored) {}
    }

    private static void tryCloseJarLoader(Object loader) {
        if (loader == null) return;
        try {
            Field jarF = findField(loader.getClass(), "jar");
            if (jarF != null) {
                jarF.setAccessible(true);
                Object jar = jarF.get(loader);
                if (jar instanceof JarFile) try { ((JarFile) jar).close(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        try { if (loader instanceof Closeable) ((Closeable) loader).close(); } catch (Throwable ignored) {}
    }

    private static void safeDeepPurge(Object root) {
        try {
            Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
            Deque<Object> stack = new ArrayDeque<>();
            stack.push(root);
            while (!stack.isEmpty()) {
                Object obj = stack.pop();
                if (obj == null || !visited.add(obj)) continue;
                if (obj instanceof Plugin) continue;
                if (isCoreType(obj.getClass())) continue;
                tryStop(obj);
                Class<?> c = obj.getClass();
                while (c != null && c != Object.class) {
                    Field[] fields;
                    try { fields = c.getDeclaredFields(); } catch (Throwable ignored) { break; }
                    for (Field f : fields) {
                        try {
                            int mod = f.getModifiers();
                            if (Modifier.isStatic(mod) && Modifier.isFinal(mod)) continue;
                            f.setAccessible(true);
                            Object val = f.get(obj);
                            if (val != null && !isCoreType(val.getClass()) && !(val instanceof Plugin)) stack.push(val);
                            if (!Modifier.isStatic(mod) && !f.getType().isPrimitive()) {
                                Object cur = f.get(obj);
                                if (!(cur instanceof Plugin) && !isCoreType(f.getType())) f.set(obj, null);
                            }
                        } catch (Throwable ignored) {}
                    }
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void tryStop(Object obj) {
        try {
            if (obj instanceof ExecutorService) {
                ExecutorService ex = (ExecutorService) obj;
                ex.shutdownNow();
                ex.awaitTermination(EXECUTOR_SHUTDOWN_MS, TimeUnit.MILLISECONDS);
            }
        } catch (Throwable ignored) {}
        try { if (obj instanceof Closeable) ((Closeable) obj).close(); } catch (Throwable ignored) {}
        try { if (obj instanceof Connection) ((Connection) obj).close(); } catch (Throwable ignored) {}
    }

    private static void fastUnregisterPhase(Plugin plugin, String identifier) {
        tryCancelScheduler(plugin);
        tryScrubSchedulerPending(plugin);
        tryUnregisterServices(plugin);
        tryUnregisterHandlers(plugin);
        CommandCleaner.clearPluginCommands(plugin);
        clearBrigadierNodes(plugin); // 빠른 해제에도 포함
        tryRemoveFromSimplePluginManager(plugin);
        tryClearPaperProviders(identifier);
    }

    private static boolean owns(Object obj, Plugin plugin) {
        if (obj == null) return false;
        try {
            Method m = obj.getClass().getMethod("getPlugin");
            if (m != null) {
                Object p = m.invoke(obj);
                return (p instanceof Plugin) && p.equals(plugin);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean isCoreType(Class<?> c) {
        if (c == null) return true;
        String n = c.getName();
        return n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("sun.") || n.startsWith("com.sun.")
                || n.startsWith("org.bukkit.") || n.startsWith("net.minecraft.") || n.startsWith("io.papermc.") || n.startsWith("com.mojang.");
    }

    private static Object getField(Object obj, String name) {
        if (obj == null) return null;
        Field f = findField(obj.getClass(), name);
        if (f == null) return null;
        try { f.setAccessible(true); return f.get(obj); } catch (Throwable ignored) {}
        return null;
    }

    private static void setField(Object obj, String name, Object value) {
        if (obj == null) return;
        Field f = findField(obj.getClass(), name);
        if (f == null) return;
        try { f.setAccessible(true); f.set(obj, value); } catch (Throwable ignored) {}
    }

    private static Field findField(Class<?> c, String name) {
        Class<?> cur = c;
        while (cur != null && cur != Object.class) {
            try { return cur.getDeclaredField(name); } catch (NoSuchFieldException ignored) {}
            cur = cur.getSuperclass();
        }
        return null;
    }

    private static String safe(String s) { return s == null ? "unknown" : s; }
    private static void log(String s) { Bukkit.getLogger().info(s); }
    private static void logErr(String s) { Bukkit.getLogger().severe(s); }

    // 여기 추가: Brigadier 노드 정리
    private static void clearBrigadierNodes(Plugin plugin) {
        try {
            Object server = Bukkit.getServer();
            Method m = server.getClass().getMethod("getCommandDispatcher");
            Object dispatcher = m.invoke(server);

            Field f = dispatcher.getClass().getDeclaredField("dispatcher");
            f.setAccessible(true);
            Object brig = f.get(dispatcher);

            Method getRoot = brig.getClass().getMethod("getRoot");
            Object root = getRoot.invoke(brig);

            @SuppressWarnings("unchecked")
            Collection<Object> children =
                    (Collection<Object>) root.getClass().getMethod("getChildren").invoke(root);

            CommandMap commandMap = null;
            if (Bukkit.getPluginManager() instanceof SimplePluginManager) {
                Field cmapField = SimplePluginManager.class.getDeclaredField("commandMap");
                cmapField.setAccessible(true);
                commandMap = (CommandMap) cmapField.get(Bukkit.getPluginManager());
            }

            final CommandMap cmFinal = commandMap;

            children.removeIf(node -> {
                try {
                    String name = (String) node.getClass().getMethod("getName").invoke(node);
                    if (cmFinal != null) {
                        Command cmd = cmFinal.getCommand(name);
                        if (cmd instanceof PluginIdentifiableCommand) {
                            return ((PluginIdentifiableCommand) cmd).getPlugin().equals(plugin);
                        }
                    }
                } catch (Throwable ignored) {}
                return false;
            });

            Bukkit.getLogger().info("[CommandCleaner] Brigadier nodes cleared for " + plugin.getName());
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[CommandCleaner] Failed to clear brigadier nodes: " + t.getMessage());
        }
    }
}
