package com.gmail.bobason01;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

final class ClasspathScanner {
    static Set<Class<?>> scan(ClassLoader cl){
        if (!(cl instanceof URLClassLoader)) return Collections.emptySet();
        Set<Class<?>> out = new HashSet<>();
        URL[] urls = ((URLClassLoader) cl).getURLs();
        for (URL u : urls){
            File f = new File(u.getPath());
            if (!f.exists() || !f.getName().endsWith(".jar")) continue;
            try(JarFile jf = new JarFile(f)){
                Enumeration<JarEntry> en = jf.entries();
                while(en.hasMoreElements()){
                    JarEntry e = en.nextElement();
                    if (e.isDirectory()) continue;
                    String n = e.getName();
                    if (!n.endsWith(".class")) continue;
                    if (n.indexOf('$')>=0) continue;
                    String cn = n.substring(0, n.length()-6).replace('/', '.');
                    try{ Class<?> c = Class.forName(cn, false, cl); out.add(c); }catch(Throwable ignored){}
                }
            }catch(IOException ignored){}
        }
        return out;
    }
}
