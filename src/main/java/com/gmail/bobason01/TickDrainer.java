package com.gmail.bobason01;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.Queue;

public final class TickDrainer {
    private final Plugin plugin;
    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private int perTick = 200;
    private boolean running;
    public TickDrainer(Plugin plugin){ this.plugin = plugin; }
    public void setPerTick(int perTick){ this.perTick = Math.max(1, perTick); }
    public void add(Runnable r){ tasks.add(r); }
    public void start(){
        if (running) return;
        running = true;
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (tasks.isEmpty()){ running = false; return; }
            int n = 0;
            while(n < perTick && !tasks.isEmpty()){
                Runnable r = tasks.poll();
                try{ r.run(); }catch(Throwable ignored){}
                n++;
            }
        }, 1L, 1L);
    }
}
