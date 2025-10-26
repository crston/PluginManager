package com.gmail.bobason01;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class ShutdownContext {
    private final long deadlineMs;
    public ShutdownContext(long timeoutMs){ this.deadlineMs = System.currentTimeMillis()+Math.max(0, timeoutMs); }
    public long remainingMs(){ long r = deadlineMs - System.currentTimeMillis(); return Math.max(0, r); }
    public boolean await(CountDownLatch latch) throws InterruptedException {
        long r = remainingMs();
        if (r <= 0) return false;
        return latch.await(r, TimeUnit.MILLISECONDS);
    }
    public boolean beforeDeadline(){ return System.currentTimeMillis() < deadlineMs; }
}
