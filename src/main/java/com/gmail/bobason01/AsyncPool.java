package com.gmail.bobason01;

import java.util.concurrent.*;

public final class AsyncPool implements AutoCloseable {
    private final ThreadPoolExecutor pool;
    public AsyncPool(int threads){
        int n = Math.max(1, threads);
        this.pool = new ThreadPoolExecutor(n, n, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "PM-Async");
            t.setDaemon(true);
            return t;
        });
        this.pool.allowCoreThreadTimeOut(true);
        this.pool.prestartAllCoreThreads();
    }
    public <T> Future<T> submit(Callable<T> c){ return pool.submit(c); }
    public Future<?> submit(Runnable r){ return pool.submit(r); }
    public void close(){ pool.shutdown(); }
    public void await(long ms){
        pool.shutdown();
        try { pool.awaitTermination(ms, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        pool.shutdownNow();
    }
}
