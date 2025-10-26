package com.gmail.bobason01;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.SimplePluginManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

public final class CommandCleaner {

    private CommandCleaner() {}

    public static void clearPluginCommands(Plugin plugin) {
        try {
            PluginManager pm = Bukkit.getPluginManager();
            if (!(pm instanceof SimplePluginManager)) return;

            SimpleCommandMap commandMap = (SimpleCommandMap) getField(pm, "commandMap");
            if (commandMap == null) return;

            @SuppressWarnings("unchecked")
            Map<String, Command> known = (Map<String, Command>) getField(commandMap, "knownCommands");
            if (known == null) return;

            // 제거할 key 수집 (별칭 포함)
            Set<String> removeKeys = new HashSet<>();

            for (Map.Entry<String, Command> entry : known.entrySet()) {
                Command cmd = entry.getValue();
                if (cmd == null) continue;

                Plugin owner = null;
                if (cmd instanceof PluginIdentifiableCommand) {
                    owner = ((PluginIdentifiableCommand) cmd).getPlugin();
                }

                if (owner != null && owner.equals(plugin)) {
                    removeKeys.add(entry.getKey().toLowerCase(Locale.ROOT));
                    removeKeys.add(cmd.getName().toLowerCase(Locale.ROOT));
                    for (String alias : cmd.getAliases()) {
                        removeKeys.add(alias.toLowerCase(Locale.ROOT));
                    }
                }
            }

            // 수집된 key 제거
            for (String key : removeKeys) {
                known.remove(key);
            }

            // Brigadier dispatcher 동기화
            syncCommandsSafe();

            Bukkit.getLogger().info("[CommandCleaner] Fully cleared commands for plugin " + plugin.getName());
        } catch (Throwable t) {
            Bukkit.getLogger().severe("[CommandCleaner] Failed to clear commands for " + plugin.getName() + ": " + t.getMessage());
        }
    }

    private static void syncCommandsSafe() {
        try {
            Object server = Bukkit.getServer();
            Method m = server.getClass().getMethod("syncCommands");
            m.invoke(server);
        } catch (NoSuchMethodException e) {
            // Spigot 계열은 syncCommands 없음 → 무시
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[CommandCleaner] syncCommands failed: " + t.getMessage());
        }
    }

    private static Object getField(Object obj, String name) {
        if (obj == null) return null;
        Field f = findField(obj.getClass(), name);
        if (f == null) return null;
        try {
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable ignored) {}
        return null;
    }

    private static Field findField(Class<?> c, String name) {
        Class<?> cur = c;
        while (cur != null && cur != Object.class) {
            try {
                return cur.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {}
            cur = cur.getSuperclass();
        }
        return null;
    }
}
