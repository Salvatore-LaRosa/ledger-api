package dev.salvatore.ledger.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Runs tasks truly in parallel to maximise contention. No sleeps: every worker signals that it
 * is ready, then waits on a start latch that is released once for all of them.
 */
public final class ConcurrentRunner {

    /** What happened to one task: {@code failure} is null when it completed normally. */
    public record Outcome(Throwable failure) {

        public boolean succeeded() {
            return failure == null;
        }
    }

    private ConcurrentRunner() {
    }

    /**
     * Runs each task on its own thread, all released at the same instant, and waits for all of
     * them. A task that throws does not stop the others: its exception is returned in its
     * {@link Outcome}. A hang (for example a deadlock) fails with a timeout instead of freezing
     * the build.
     *
     * <p>Keep the number of tasks at or below the connection pool size (10 by default),
     * otherwise the pool, not the database locks, becomes the bottleneck being measured.
     */
    public static List<Outcome> runAtOnce(List<? extends Runnable> tasks) throws Exception {
        int size = tasks.size();
        ExecutorService executor = Executors.newFixedThreadPool(size);
        try {
            CountDownLatch ready = new CountDownLatch(size);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Runnable task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        task.run();
                        return new Outcome(null);
                    } catch (Throwable failure) {
                        return new Outcome(failure);
                    }
                }));
            }
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("workers did not start in time");
            }
            start.countDown();

            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }
}