package com.gmail.bobason01;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ThreadRegistry {
    private final Set<Thread> threads = Collections.newSetFromMap(new ConcurrentHashMap<>());
    public Thread newThread(Runnable r, String name){
        Thread t = new Thread(() -> { try{ r.run(); } finally{ threads.remove(Thread.currentThread()); } }, name);
        threads.add(t);
        return t;
    }
    public void register(Thread t){ if (t!=null) threads.add(t); }
    public void unregister(Thread t){ if (t!=null) threads.remove(t); }
    public boolean stopAll(long timeoutMs){
        for (Thread t : threads) try{ t.interrupt(); }catch(Throwable ignored){}
        long end = System.currentTimeMillis()+timeoutMs;
        for (Thread t : threads){
            long rem = Math.max(0, end - System.currentTimeMillis());
            try{ t.join(rem); }catch(InterruptedException ignored){}
        }
        boolean ok = true;
        for (Thread t : threads) if (t.isAlive()) ok = false;
        threads.clear();
        return ok;
    }
}
