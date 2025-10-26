package com.gmail.bobason01;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public final class PluginManager extends JavaPlugin {
    private static PluginManager instance;

    private final Map<String, PendingAction> pending = new ConcurrentHashMap<>();

    private double minTps;
    private int deferPlayers;
    private int confirmWindowMs;
    private boolean allowLoadFromFile;

    // 추가된 필드들
    private AdapterRegistry adapterRegistry;
    private StateRegistry stateRegistry;
    private AsyncPool asyncPool;
    private SafePointManagerExtreme safePoint;

    public static PluginManager getInstance() { return instance; }
    public SafePointManagerExtreme getSafePoint() { return safePoint; }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        reloadAll();

        // 레지스트리 생성 및 WG 어댑터 등록
        adapterRegistry = new AdapterRegistry();
        adapterRegistry.register(new WorldGuardAdapterExtreme());

        // 상태 / 스레드 풀 생성
        stateRegistry = new StateRegistry();
        asyncPool = new AsyncPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));

        // SafePoint 매니저 생성
        safePoint = new SafePointManagerExtreme(
                this, adapterRegistry, stateRegistry, asyncPool,
                minTps, deferPlayers, confirmWindowMs,
                SafePointManagerExtreme.UnloadMode.EXPERIMENTAL
        );

        if (getCommand("pluginmanager") != null) {
            PluginManagerCommand cmd = new PluginManagerCommand(this);
            getCommand("pluginmanager").setExecutor(cmd);
            getCommand("pluginmanager").setTabCompleter(cmd);
        }

        Bukkit.getLogger().info("[PluginManager] Enabled");
    }

    @Override
    public void onDisable() {
        if (asyncPool != null) asyncPool.close();
        Bukkit.getLogger().info("[PluginManager] Disabled");
    }

    public void reloadAll() {
        reloadConfig();
        FileConfiguration c = getConfig();
        minTps = Math.max(5.0, c.getDouble("tps.min", 18.5));
        deferPlayers = Math.max(0, c.getInt("defer.players", 200));
        confirmWindowMs = Math.max(5000, c.getInt("confirm.window-ms", 15000));
        allowLoadFromFile = c.getBoolean("load.enable", true);
    }

    public boolean tpsOk() {
        try {
            double[] tps = (double[]) Bukkit.getServer().getClass().getMethod("getTPS").invoke(Bukkit.getServer());
            return tps[0] >= minTps;
        } catch (Throwable t) {
            return true;
        }
    }

    public boolean shouldDefer() {
        return Bukkit.getOnlinePlayers().size() >= deferPlayers;
    }

    public Plugin findPlugin(String name) {
        if (name == null) return null;
        Plugin p = Bukkit.getPluginManager().getPlugin(name);
        if (p != null) return p;
        String q = name.toLowerCase(Locale.ROOT);
        for (Plugin x : Bukkit.getPluginManager().getPlugins()) if (x.getName().equalsIgnoreCase(name)) return x;
        for (Plugin x : Bukkit.getPluginManager().getPlugins()) if (x.getName().toLowerCase(Locale.ROOT).contains(q)) return x;
        return null;
    }

    public void addPending(String action, Plugin plugin) {
        pending.put(plugin.getName().toLowerCase(Locale.ROOT),
                new PendingAction(action, plugin.getName(), System.currentTimeMillis() + confirmWindowMs));
    }

    public PendingAction getPending(String pluginName) {
        PendingAction pa = pending.get(pluginName.toLowerCase(Locale.ROOT));
        if (pa != null && pa.expireAt > System.currentTimeMillis()) return pa;
        return null;
    }

    public void clearPending(String pluginName) {
        pending.remove(pluginName.toLowerCase(Locale.ROOT));
    }

    public boolean isLoadAllowed() { return allowLoadFromFile; }
    public File pluginsDir() { return new File("plugins"); }

    public static final class PendingAction {
        public final String action;
        public final String pluginName;
        public final long expireAt;
        public PendingAction(String action, String pluginName, long expireAt) {
            this.action = action; this.pluginName = pluginName; this.expireAt = expireAt;
        }
    }
}
