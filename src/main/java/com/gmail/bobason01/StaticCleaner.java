package com.gmail.bobason01;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;

public final class StaticCleaner {
    public static void clearStatics(ClassLoader cl, Set<Class<?>> classes){
        if (cl == null || classes == null) return;
        for (Class<?> c : classes){
            try{
                Field[] fs = c.getDeclaredFields();
                for (Field f : fs){
                    int m = f.getModifiers();
                    if (!Modifier.isStatic(m)) continue;
                    if (Modifier.isFinal(m)) continue;
                    if (f.getType().isPrimitive()) continue;
                    f.setAccessible(true);
                    try{ f.set(null, null); }catch(Throwable ignored){}
                }
            }catch(Throwable ignored){}
        }
    }
}
