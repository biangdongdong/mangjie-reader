package com.mangjie.reader.tools;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 后台任务的统一入口。
 * 原先到处 new Thread(...).start()，线程数不可控、也不好排查；
 * 这里收成一个共享线程池：按需创建、空闲回收，线程名统一成 worker-N，
 * 出问题时在日志里能看出是池里的线程而不是野线程。
 */
public final class Workers {

    private static final AtomicInteger SEQ = new AtomicInteger(0);

    private static final ExecutorService POOL = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "worker-" + SEQ.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    private Workers() {}

    public static void run(Runnable task) {
        if (task != null) POOL.execute(task);
    }
}
