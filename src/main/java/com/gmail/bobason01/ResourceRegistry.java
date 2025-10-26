package com.gmail.bobason01;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ResourceRegistry {
    private final List<AutoCloseable> resources = Collections.synchronizedList(new ArrayList<>());
    public <T extends AutoCloseable> T register(T r){ if (r!=null) resources.add(r); return r; }
    public void closeAll(){
        List<AutoCloseable> copy;
        synchronized (resources){ copy = new ArrayList<>(resources); resources.clear(); }
        for (int i = copy.size()-1; i>=0; i--) try{ copy.get(i).close(); }catch(Throwable ignored){}
    }
}
