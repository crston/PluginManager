package com.gmail.bobason01;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class WorldGuardAdapterExtreme implements PluginAdapter {
    private final Map<String,Object> exportBuffer = new ConcurrentHashMap<>();
    private final Map<String,Object> importBuffer = new ConcurrentHashMap<>();
    private Map<String,String> nameMappings = Collections.emptyMap();
    public boolean supports(Plugin plugin){ return plugin!=null && plugin.getName().equalsIgnoreCase("WorldGuard"); }
    public void prepareExport(Plugin manager, Plugin plugin, File dir) throws Exception{
        Map<String,Object> root = new LinkedHashMap<>();
        ClassLoader cl = plugin.getClass().getClassLoader();
        Object wg = Class.forName("com.sk89q.worldguard.WorldGuard", false, cl).getMethod("getInstance").invoke(null);
        Object platform = wg.getClass().getMethod("getPlatform").invoke(wg);
        Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);
        Method getRegions = container.getClass().getMethod("get", Class.forName("com.sk89q.worldedit.world.World", false, cl));
        Map<String,Object> worldsNode = new LinkedHashMap<>();
        for (World bw : Bukkit.getWorlds()){
            Object weWorld = adaptWEWorld(cl, bw);
            if (weWorld == null) continue;
            Object regions = getRegions.invoke(container, weWorld);
            if (regions == null) continue;
            Method getRegionsMap = regions.getClass().getMethod("getRegions");
            Map<?,?> map = (Map<?,?>) getRegionsMap.invoke(regions);
            Map<String,Object> regionsNode = new LinkedHashMap<>();
            for (Map.Entry<?,?> e : map.entrySet()){
                String rid = String.valueOf(e.getKey());
                Object rg = e.getValue();
                Method getFlags = rg.getClass().getMethod("getFlags");
                Map<?,?> flags = (Map<?,?>) getFlags.invoke(rg);
                Map<String,String> flagMap = new LinkedHashMap<>();
                for (Map.Entry<?,?> fe : flags.entrySet()){
                    String fname = String.valueOf(fe.getKey());
                    Object fval = fe.getValue();
                    flagMap.put(fname, fval==null? "null" : String.valueOf(fval));
                }
                Map<String,Object> rnode = new LinkedHashMap<>();
                rnode.put("flags", flagMap);
                regionsNode.put(rid, rnode);
            }
            Map<String,Object> wnode = new LinkedHashMap<>();
            wnode.put("regions", regionsNode);
            worldsNode.put(bw.getName(), wnode);
        }
        root.put("worlds", worldsNode);
        exportBuffer.clear();
        exportBuffer.putAll(root);
    }
    public void commitExport(Plugin manager, Plugin plugin, File dir) throws Exception{
        File full = new File(dir, "wg-flags.yml.gz");
        YamlConfiguration newY = new YamlConfiguration();
        writeMap(newY, "", exportBuffer);
        if (full.exists()){
            YamlConfiguration oldY = SnapshotStore.loadYamlGz(full);
            Map<String,Object> delta = computeDelta(oldY, newY, "");
            if (!delta.isEmpty()) SnapshotStore.writeDelta(new File(dir, "wg-flags-delta.yml.gz"), delta);
        }
        SnapshotStore.saveYamlGz(full, newY);
    }
    public void prepareImport(Plugin manager, Plugin plugin, File dir) throws Exception{
        File full = new File(dir, "wg-flags.yml.gz");
        if (!full.exists()){ importBuffer.clear(); return; }
        YamlConfiguration y = SnapshotStore.loadYamlGz(full);
        Map<String,Object> root = new LinkedHashMap<>();
        for (String w : y.getConfigurationSection("worlds").getKeys(false)){
            Map<String,Object> wnode = new LinkedHashMap<>();
            if (y.isConfigurationSection("worlds."+w+".regions")){
                Map<String,Object> regions = new LinkedHashMap<>();
                for (String rid : y.getConfigurationSection("worlds."+w+".regions").getKeys(false)){
                    Map<String,Object> rnode = new LinkedHashMap<>();
                    Map<String,String> flags = new LinkedHashMap<>();
                    if (y.isConfigurationSection("worlds."+w+".regions."+rid+".flags")){
                        for (String fn : y.getConfigurationSection("worlds."+w+".regions."+rid+".flags").getKeys(false)){
                            flags.put(fn, y.getString("worlds."+w+".regions."+rid+".flags."+fn));
                        }
                    }
                    rnode.put("flags", flags);
                    regions.put(rid, rnode);
                }
                wnode.put("regions", regions);
            }
            root.put(w, wnode);
        }
        Map<String,Object> worlds = new LinkedHashMap<>();
        worlds.put("worlds", root);
        importBuffer.clear();
        importBuffer.putAll(worlds);
        this.nameMappings = loadFlagMappings(manager);
    }
    public void commitImport(Plugin manager, Plugin plugin, File dir) throws Exception{
        ClassLoader cl = plugin.getClass().getClassLoader();
        Object wg = Class.forName("com.sk89q.worldguard.WorldGuard", false, cl).getMethod("getInstance").invoke(null);
        Object platform = wg.getClass().getMethod("getPlatform").invoke(wg);
        Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);
        Method getRegions = container.getClass().getMethod("get", Class.forName("com.sk89q.worldedit.world.World", false, cl));
        Method getRegion = null;
        TickDrainer drainer = new TickDrainer(manager);
        drainer.setPerTick(manager.getConfig().getInt("wg.apply.per-tick", 200));
        Map<?,?> worlds = (Map<?,?>) importBuffer.get("worlds");
        if (worlds == null) return;
        for (Map.Entry<?,?> we : worlds.entrySet()){
            String wname = String.valueOf(we.getKey());
            org.bukkit.World bw = Bukkit.getWorld(wname);
            if (bw == null) continue;
            Object weWorld = adaptWEWorld(cl, bw);
            if (weWorld == null) continue;
            Object regions = getRegions.invoke(container, weWorld);
            if (regions == null) continue;
            if (getRegion == null) getRegion = regions.getClass().getMethod("getRegion", String.class);
            Map<?,?> wnode = (Map<?,?>) we.getValue();
            Map<?,?> rnodeAll = (Map<?,?>) wnode.get("regions");
            if (rnodeAll == null) continue;
            for (Map.Entry<?,?> re : rnodeAll.entrySet()){
                String rid = String.valueOf(re.getKey());
                Object rg = getRegion.invoke(regions, rid);
                if (rg == null) continue;
                Map<?,?> rnode = (Map<?,?>) re.getValue();
                Map<?,?> flags = (Map<?,?>) rnode.get("flags");
                if (flags == null) continue;
                for (Map.Entry<?,?> fe : flags.entrySet()){
                    String rawName = String.valueOf(fe.getKey());
                    String mappedName = nameMappings.getOrDefault(rawName, rawName);
                    String sval = fe.getValue()==null? "null" : String.valueOf(fe.getValue());
                    drainer.add(() -> applyFlag(cl, rg, mappedName, sval));
                }
            }
        }
        drainer.start();
    }
    private static Object adaptWEWorld(ClassLoader cl, org.bukkit.World bw){
        try{
            Class<?> BukkitAdapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter", false, cl);
            return BukkitAdapter.getMethod("adapt", org.bukkit.World.class).invoke(null, bw);
        }catch(Throwable t){ return null; }
    }
    private static void writeMap(YamlConfiguration y, String prefix, Map<String,Object> map){
        for (Map.Entry<String,Object> e : map.entrySet()){
            String k = prefix.isEmpty()? e.getKey() : prefix+"."+e.getKey();
            Object v = e.getValue();
            if (v instanceof Map) writeMap(y, k, (Map<String,Object>) v);
            else y.set(k, v);
        }
    }
    private static Map<String,Object> flatten(YamlConfiguration y, String prefix){
        Map<String,Object> out = new LinkedHashMap<>();
        for (String k : y.getKeys(true)){
            if (y.isConfigurationSection(k)) continue;
            Object v = y.get(k);
            if (!prefix.isEmpty() && !k.startsWith(prefix)) continue;
            out.put(k, v);
        }
        return out;
    }
    private static Map<String,Object> computeDelta(YamlConfiguration oldY, YamlConfiguration newY, String prefix){
        Map<String,Object> oldF = flatten(oldY, prefix);
        Map<String,Object> newF = flatten(newY, prefix);
        Map<String,Object> delta = new LinkedHashMap<>();
        for (Map.Entry<String,Object> e : newF.entrySet()){
            Object ov = oldF.get(e.getKey());
            if (!java.util.Objects.equals(ov, e.getValue())) delta.put(e.getKey(), e.getValue());
        }
        return delta;
    }
    private Map<String,String> loadFlagMappings(Plugin manager){
        Map<String,String> m = new HashMap<>();
        if (manager.getConfig().isConfigurationSection("wg.flag-mappings")){
            for (String k : manager.getConfig().getConfigurationSection("wg.flag-mappings").getKeys(false)){
                m.put(k, manager.getConfig().getString("wg.flag-mappings."+k));
            }
        }
        return m;
    }
    private void applyFlag(ClassLoader cl, Object region, String flagName, String value){
        try{
            Object flag = findFlagByName(cl, flagName);
            if (flag == null) return;
            Object parsed = parseFlagValue(cl, flag, value);
            Method setFlag = region.getClass().getMethod("setFlag", Class.forName("com.sk89q.worldguard.protection.flags.Flag", false, cl), Object.class);
            setFlag.invoke(region, flag, parsed);
        }catch(Throwable ignored){}
    }
    private static Object findFlagByName(ClassLoader cl, String name){
        try{
            Class<?> Flags = Class.forName("com.sk89q.worldguard.protection.flags.Flags", false, cl);
            for (Field f : Flags.getFields()){
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (f.getName().equalsIgnoreCase(name)) return f.get(null);
            }
        }catch(Throwable ignored){}
        return null;
    }
    private static Object parseFlagValue(ClassLoader cl, Object flag, String val){
        try{
            if (val == null || "null".equalsIgnoreCase(val)) return null;
            try{
                Class<?> LocalPlayer = Class.forName("com.sk89q.worldguard.LocalPlayer", false, cl);
                Method parse = flag.getClass().getMethod("parseInput", LocalPlayer, String.class);
                return parse.invoke(flag, null, val);
            }catch(NoSuchMethodException ignored){}
            try{
                Method parse2 = flag.getClass().getMethod("parseInput", String.class);
                return parse2.invoke(flag, val);
            }catch(NoSuchMethodException ignored){}
            try{
                Class<?> StateFlag = Class.forName("com.sk89q.worldguard.protection.flags.StateFlag", false, cl);
                if (StateFlag.isInstance(flag)){
                    Class<?> State = Class.forName("com.sk89q.worldguard.protection.flags.StateFlag$State", false, cl);
                    if ("true".equalsIgnoreCase(val) || "allow".equalsIgnoreCase(val)) return Enum.valueOf((Class<Enum>) State, "ALLOW");
                    if ("false".equalsIgnoreCase(val) || "deny".equalsIgnoreCase(val)) return Enum.valueOf((Class<Enum>) State, "DENY");
                    if ("none".equalsIgnoreCase(val)) return null;
                }
            }catch(Throwable ignored){}
            return val;
        }catch(Throwable t){ return null; }
    }
}

