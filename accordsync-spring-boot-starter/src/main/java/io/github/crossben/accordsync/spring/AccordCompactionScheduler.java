package io.github.crossben.accordsync.spring;

import io.github.crossben.accordsync.server.AccordServer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.context.SmartLifecycle;

/**
 * Runs {@link AccordServer#compact()} with a fixed delay of the definition's
 * {@code compaction().intervalMs()} (nothing when it is 0 or less), on one daemon thread of its
 * own. Every instance of a scaled-out app may run it: compaction is safe to run concurrently.
 */
public class AccordCompactionScheduler implements SmartLifecycle {
    private static final System.Logger LOG = System.getLogger(AccordCompactionScheduler.class.getName());

    private final AccordServer server;
    private ScheduledExecutorService executor;

    /** @param server the server to compact */
    public AccordCompactionScheduler(AccordServer server) {
        this.server = server;
    }

    /** @return the delay between two runs, in ms (0: never) */
    public long intervalMs() {
        return Math.max(0, server.definition().compaction().intervalMs());
    }

    @Override
    public synchronized void start() {
        long every = intervalMs();
        if (executor != null || every <= 0) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "accord-compaction");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::runOnce, every, every, TimeUnit.MILLISECONDS);
    }

    void runOnce() {
        try {
            LOG.log(System.Logger.Level.DEBUG, "accord: compacted {0}", server.compact());
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "accord: compaction failed", e);
        }
    }

    @Override
    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return executor != null;
    }
}
