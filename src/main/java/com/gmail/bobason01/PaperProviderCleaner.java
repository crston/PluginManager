package com.gmail.bobason01;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.Map;

public final class PaperProviderCleaner {
    private PaperProviderCleaner(){}

    public static void forceClear(Plugin plugin){
        try{
            String name = plugin.getDescription().getName();
            String version = plugin.getDescription().getVersion();
            String identifier = name+" v"+version;
            Object paperManager = Bukkit.getPluginManager();
            Field instanceMgrField = findField(paperManager.getClass(), "instanceManager");
            if (instanceMgrField==null) return;
            instanceMgrField.setAccessible(true);
            Object instanceMgr = instanceMgrField.get(paperManager);
            if (instanceMgr==null) return;
            Field handlerField = findField(instanceMgr.getClass(), "runtimeEntrypointHandler");
            if (handlerField==null) return;
            handlerField.setAccessible(true);
            Object handler = handlerField.get(instanceMgr);
            if (handler==null) return;
            Field storageField = findField(handler.getClass(), "providerStorage");
            if (storageField==null) return;
            storageField.setAccessible(true);
            Object singular = storageField.get(handler);
            if (singular==null) return;
            Field delegate1 = findField(singular.getClass(), "delegate");
            if (delegate1==null) return;
            delegate1.setAccessible(true);
            Object simple = delegate1.get(singular);
            if (simple==null) return;
            Field loadedProvidersField = findField(simple.getClass(), "loadedProviders");
            Field delegate2 = findField(simple.getClass(), "delegate");
            if (delegate2==null) return;
            delegate2.setAccessible(true);
            Object server = delegate2.get(simple);
            if (server==null) return;
            Field providersByKeyField = findField(server.getClass(), "providersByKey");
            Field contextsField = findField(server.getClass(), "contexts");
            if (providersByKeyField==null || contextsField==null) return;
            providersByKeyField.setAccessible(true);
            contextsField.setAccessible(true);
            Object providersByKey = providersByKeyField.get(server);
            Object contexts = contextsField.get(server);
            if (providersByKey instanceof Map){
                ((Map<?,?>)providersByKey).entrySet().removeIf(e -> e.getKey()!=null && e.getKey().toString().equalsIgnoreCase(identifier));
            }
            if (loadedProvidersField!=null){
                loadedProvidersField.setAccessible(true);
                Object loadedProviders = loadedProvidersField.get(simple);
                if (loadedProviders instanceof Map){
                    Iterator<?> it = ((Map<?,?>)loadedProviders).keySet().iterator();
                    while(it.hasNext()){
                        Object k = it.next();
                        if (k!=null && k.toString().equalsIgnoreCase(identifier)) it.remove();
                    }
                }
            }
            if (contexts instanceof Map){
                ((Map<?,?>)contexts).keySet().removeIf(k -> k!=null && k.toString().equalsIgnoreCase(identifier));
            }
        }catch(Throwable ignored){}
    }

    private static Field findField(Class<?> c, String name){
        Class<?> k = c;
        while(k!=null){
            try{ return k.getDeclaredField(name); }catch(NoSuchFieldException ignored){}
            k = k.getSuperclass();
        }
        return null;
    }
}
