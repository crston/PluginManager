package com.gmail.bobason01;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class PluginManager extends JavaPlugin {
    private static PluginManager instance;
    private final Set<String> configBlacklist = new HashSet<>();
    private final List<String> patternBlacklist = Arrays.asList(
            "cmi", "luckperms", "protocol", "citizens",
            "itemsadder", "oraxen", "modelengine", "slimefun",
            "mythic", "mmo", "chunky"
    );

    private final Map<String, PendingAction> pendingActions = new HashMap<>();

    public static PluginManager getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        loadBlacklist();

        if (getCommand("pluginmanager") != null) {
            PluginManagerCommand executor = new PluginManagerCommand();
            getCommand("pluginmanager").setExecutor(executor);
            getCommand("pluginmanager").setTabCompleter(executor);
        }
    }

    private void loadBlacklist() {
        FileConfiguration cfg = getConfig();
        configBlacklist.clear();
        configBlacklist.addAll(cfg.getStringList("reload-blacklist"));
    }

    public Plugin findPlugin(String name) {
        if (name == null) return null;
        Plugin exact = Bukkit.getPluginManager().getPlugin(name);
        if (exact != null) return exact;
        String target = name.toLowerCase(Locale.ROOT);
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (p.getName().equalsIgnoreCase(name)) return p;
            if (p.getName().toLowerCase().equals(target)) return p;
        }
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (p.getName().toLowerCase().contains(target)) return p;
        }
        return null;
    }

    public boolean canSafelyDisable(Plugin plugin, CommandFeedback feedback) {
        String n = plugin.getName().toLowerCase();

        if (configBlacklist.contains(plugin.getName())) {
            feedback.reason = "Plugin is listed in config blacklist";
            return false;
        }
        if (!plugin.getDescription().getDepend().isEmpty()) {
            feedback.reason = "Plugin has mandatory dependencies: " + plugin.getDescription().getDepend();
            return false;
        }
        if (!plugin.getDescription().getSoftDepend().isEmpty()) {
            feedback.reason = "Warning: Plugin has soft dependencies: " + plugin.getDescription().getSoftDepend();
        }
        for (String bad : patternBlacklist) {
            if (n.contains(bad)) {
                if (n.equals("placeholderapi") || n.equals("vault") || n.equals("worldedit") || n.equals("worldguard")) {
                    return true;
                }
                feedback.reason = "Core/complex plugin not safe to reload";
                return false;
            }
        }
        if (n.contains("sql") || n.contains("db")) {
            feedback.reason = "Plugin likely uses a database connection";
            return false;
        }
        return true;
    }

    public ActionResult enablePluginSafe(Plugin plugin) {
        if (plugin == null) return ActionResult.fail("Plugin not found");
        if (plugin.isEnabled()) return ActionResult.ok("Already enabled");
        long t0 = System.nanoTime();
        Bukkit.getPluginManager().enablePlugin(plugin);
        boolean ok = plugin.isEnabled();
        long dt = System.nanoTime() - t0;
        if (ok) return ActionResult.ok("Enabled successfully in " + formatNanos(dt));
        return ActionResult.fail("Enable failed in " + formatNanos(dt));
    }

    public ActionResult disablePluginSafe(Plugin plugin, CommandFeedback feedback) {
        if (plugin == null) return ActionResult.fail("Plugin not found");
        if (plugin.equals(this)) return ActionResult.fail("Cannot disable PluginManager itself");
        if (!plugin.isEnabled()) return ActionResult.ok("Already disabled");

        if (!canSafelyDisable(plugin, feedback)) {
            addPending("disable", plugin);
            return ActionResult.fail("Unsafe to disable: " + feedback.reason +
                    ". Use /pluginmanager confirm disable " + plugin.getName() + " within 15s to force.");
        }
        return doDisable(plugin);
    }

    public ActionResult reloadPluginSafe(Plugin plugin, CommandFeedback feedback) {
        if (plugin == null) return ActionResult.fail("Plugin not found");
        if (plugin.equals(this)) return ActionResult.fail("Cannot reload PluginManager itself");

        if (!canSafelyDisable(plugin, feedback)) {
            addPending("reload", plugin);
            return ActionResult.fail("Unsafe to reload: " + feedback.reason +
                    ". Use /pluginmanager confirm reload " + plugin.getName() + " within 15s to force.");
        }
        return doReload(plugin);
    }

    public ActionResult doDisable(Plugin plugin) {
        long t0 = System.nanoTime();
        Bukkit.getScheduler().cancelTasks(plugin);
        org.bukkit.event.HandlerList.unregisterAll(plugin);
        Bukkit.getPluginManager().disablePlugin(plugin);
        boolean ok = !plugin.isEnabled();
        long dt = System.nanoTime() - t0;
        if (ok) return ActionResult.ok("Disabled successfully in " + formatNanos(dt));
        return ActionResult.fail("Disable failed in " + formatNanos(dt));
    }

    public ActionResult doReload(Plugin plugin) {
        long t0 = System.nanoTime();
        Bukkit.getScheduler().cancelTasks(plugin);
        org.bukkit.event.HandlerList.unregisterAll(plugin);
        Bukkit.getPluginManager().disablePlugin(plugin);
        if (plugin.isEnabled()) return ActionResult.fail("Disable failed");
        Bukkit.getPluginManager().enablePlugin(plugin);
        boolean ok = plugin.isEnabled();
        long dt = System.nanoTime() - t0;
        if (ok) return ActionResult.ok("Reloaded successfully in " + formatNanos(dt));
        return ActionResult.fail("Reload failed in " + formatNanos(dt));
    }

    private void addPending(String action, Plugin plugin) {
        pendingActions.put(plugin.getName().toLowerCase(Locale.ROOT),
                new PendingAction(action, plugin.getName(), System.currentTimeMillis() + 15000));
    }

    public PendingAction getPending(String pluginName) {
        PendingAction pa = pendingActions.get(pluginName.toLowerCase(Locale.ROOT));
        if (pa != null && pa.expireAt > System.currentTimeMillis()) return pa;
        return null;
    }

    public void clearPending(String pluginName) {
        pendingActions.remove(pluginName.toLowerCase(Locale.ROOT));
    }

    public String formatNanos(long nanos) {
        double ms = nanos / 1_000_000.0;
        if (ms < 1) return nanos + "ns";
        if (ms < 1000) return String.format("%.2fms", ms);
        return String.format("%.2fs", ms / 1000.0);
    }

    public static class ActionResult {
        public final boolean success;
        public final String message;
        private ActionResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
        public static ActionResult ok(String m) { return new ActionResult(true, m); }
        public static ActionResult fail(String m) { return new ActionResult(false, m); }
    }

    public static class CommandFeedback {
        public String reason = "";
    }

    public static class PendingAction {
        public final String action;
        public final String pluginName;
        public final long expireAt;
        public PendingAction(String action, String pluginName, long expireAt) {
            this.action = action;
            this.pluginName = pluginName;
            this.expireAt = expireAt;
        }
    }
}
