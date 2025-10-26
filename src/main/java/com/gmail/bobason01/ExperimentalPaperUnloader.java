package com.gmail.bobason01;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.util.*;

public final class ExperimentalPaperUnloader {
    private static final int MAX_DEPTH = 96;
    private ExperimentalPaperUnloader(){}
    public static Graceful.Result deepUnload(Plugin plugin, ThreadRegistry threads, ResourceRegistry resources, int timeoutMs){
        try{
            Graceful.Result base = Graceful.unload(plugin, threads, resources, timeoutMs);
            if (!base.success) return base;
            ClassLoader cl = plugin.getClass().getClassLoader();
            if (plugin instanceof Cleanable){
                try{ ((Cleanable) plugin).gracefulShutdown(new ShutdownContext(timeoutMs)); }catch(Throwable ignored){}
            }
            if (resources != null) resources.closeAll();
            if (threads != null) threads.stopAll(timeoutMs);
            Set<Class<?>> classes = ClasspathScanner.scan(cl);
            StaticCleaner.clearStatics(cl, classes);
            purgeReferences(cl);
            if (cl instanceof URLClassLoader){ try{ ((URLClassLoader) cl).close(); }catch(Throwable ignored){} }
            try{ PaperProviderCleaner.forceClear(plugin); }catch(Throwable ignored){}
            return Graceful.Result.ok("experimental-unloaded:"+plugin.getName());
        }catch(Throwable t){
            return Graceful.Result.fail("experimental-ex:"+t.getClass().getSimpleName());
        }
    }
    private static void purgeReferences(ClassLoader targetCl){
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        Object root = Bukkit.getPluginManager();
        deepPurge(root, targetCl, seen, 0);
    }
    private static void deepPurge(Object obj, ClassLoader targetCl, IdentityHashMap<Object, Boolean> seen, int depth){
        if (obj == null) return;
        if (depth > MAX_DEPTH) return;
        if (seen.containsKey(obj)) return;
        seen.put(obj, Boolean.TRUE);
        Class<?> type = obj.getClass();
        if (skipType(type)) return;
        if (type.getClassLoader() == targetCl){ nullify(obj); return; }
        if (type.isArray()){
            int len = Array.getLength(obj);
            for (int i=0;i<len;i++) deepPurge(Array.get(obj,i), targetCl, seen, depth+1);
            return;
        }
        if (obj instanceof Map){
            Map<?,?> m = (Map<?,?>) obj;
            for (Object k : new ArrayList<>(m.keySet())){
                deepPurge(k, targetCl, seen, depth+1);
                deepPurge(m.get(k), targetCl, seen, depth+1);
            }
            return;
        }
        if (obj instanceof Collection){
            for (Object e : new ArrayList<>((Collection<?>) obj)) deepPurge(e, targetCl, seen, depth+1);
            return;
        }
        for (Field f : getAllFields(type)){
            try{
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object v = f.get(obj);
                if (v == null) continue;
                if (v.getClass().getClassLoader() == targetCl) f.set(obj, null);
                else deepPurge(v, targetCl, seen, depth+1);
            }catch(Throwable ignored){}
        }
    }
    private static List<Field> getAllFields(Class<?> t){
        List<Field> out = new ArrayList<>();
        Class<?> c = t;
        while(c!=null && c!=Object.class){
            try{ Collections.addAll(out, c.getDeclaredFields()); }catch(Throwable ignored){}
            c = c.getSuperclass();
        }
        return out;
    }
    private static boolean skipType(Class<?> type){
        if (type == null) return true;
        Package p = type.getPackage();
        String n = p==null? "": p.getName();
        if (n.startsWith("java.")||n.startsWith("javax.")||n.startsWith("sun.")||n.startsWith("com.sun.")) return true;
        if (n.startsWith("org.bukkit.")||n.startsWith("net.minecraft.")||n.startsWith("io.papermc.")||n.startsWith("com.mojang.")) return true;
        return false;
    }
    private static void nullify(Object obj){
        for (Field f : getAllFields(obj.getClass())){
            try{
                if (Modifier.isStatic(f.getModifiers()) && Modifier.isFinal(f.getModifiers())) continue;
                f.setAccessible(true);
                f.set(obj, null);
            }catch(Throwable ignored){}
        }
    }
}
