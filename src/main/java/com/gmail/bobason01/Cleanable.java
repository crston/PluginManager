package com.gmail.bobason01;

public interface Cleanable {
    void gracefulShutdown(ShutdownContext context) throws Exception;
}
