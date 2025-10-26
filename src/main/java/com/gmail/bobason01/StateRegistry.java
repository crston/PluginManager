package com.gmail.bobason01;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class StateRegistry {
    private final Map<String,Object> resources = new ConcurrentHashMap<>();
    private final Set<String> busy = ConcurrentHashMap.newKeySet();
    public void register(String key, Object resource){ resources.put(key, resource); }
    public Object get(String key){ return resources.get(key); }
    public void remove(String key){ resources.remove(key); }
    public void markBusy(String key){ busy.add(key); }
    public void markFree(String key){ busy.remove(key); }
    public boolean isBusy(String key){ return busy.contains(key); }
    public Map<String,Object> snapshot(){ return new ConcurrentHashMap<>(resources); }
    public Set<String> keys(){ return resources.keySet(); }
}
