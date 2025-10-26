package com.gmail.bobason01;

import org.bukkit.plugin.Plugin;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class AdapterRegistry {
    private final List<PluginAdapter> adapters = new ArrayList<>();
    private final Map<String, PluginAdapter> cache = new ConcurrentHashMap<>();
    public void register(PluginAdapter adapter){ if (adapter != null) adapters.add(adapter); }
    public PluginAdapter find(Plugin plugin){
        if (plugin == null) return null;
        String key = plugin.getName().toLowerCase(Locale.ROOT);
        PluginAdapter c = cache.get(key);
        if (c != null) { try { if (c.supports(plugin)) return c; } catch (Throwable ignored) {} }
        for (PluginAdapter a : adapters){
            try { if (a.supports(plugin)) { cache.put(key, a); return a; } } catch (Throwable ignored) {}
        }
        return null;
    }
    public List<PluginAdapter> getAllAdapters(){ return Collections.unmodifiableList(adapters); }
    public void clearCache(){ cache.clear(); }
}
