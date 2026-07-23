package com.adbustr.sdk.core;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The SDK's two execution contexts: a small IO pool for ad requests, creative
 * downloads and tracking pixels, and the main-thread handler every public
 * callback is delivered on.
 */
public final class Threads {

    private static final int IO_POOL_SIZE = 3;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final Executor IO = Executors.newFixedThreadPool(IO_POOL_SIZE, new ThreadFactory() {
        private final AtomicInteger counter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "adbustr-io-" + counter.getAndIncrement());
            thread.setDaemon(true);
            // Ad work must never compete with the game's render thread.
            thread.setPriority(Thread.MIN_PRIORITY + 1);
            return thread;
        }
    });

    private Threads() {
    }

    public static void io(Runnable task) {
        IO.execute(task);
    }

    /** Runs now if already on the main thread, otherwise posts. */
    public static void main(Runnable task) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            MAIN.post(task);
        }
    }

    /** Always posts, even from the main thread — use to break re-entrancy. */
    public static void postMain(Runnable task) {
        MAIN.post(task);
    }

    public static void mainDelayed(Runnable task, long delayMillis) {
        MAIN.postDelayed(task, delayMillis);
    }

    public static void cancelMain(Runnable task) {
        MAIN.removeCallbacks(task);
    }
}
