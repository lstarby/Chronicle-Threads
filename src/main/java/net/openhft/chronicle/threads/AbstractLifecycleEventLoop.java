package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.io.AbstractCloseable;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A parent class that:
 * <ul>
 *     <li>Enforces the life-cycle of an EventLoop</li>
 *     <li>Implements idempotency for {@link #start()}, {@link #stop()}</li>
 *     <li>Ensures {@link #stop()} only returns when the EventLoop is stopped</li>
 * </ul>
 * See {@link EventLoopLifecycle} for details of the life-cycle
 */
public abstract class AbstractLifecycleEventLoop extends AbstractCloseable implements EventLoop {

    /**
     * After this time, awaitTermination will log an error and return, this is really only so
     * tests don't block forever. This time should be kept as "effectively forever".
     */
    private static final long AWAIT_TERMINATION_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(5);
    private final AtomicReference<EventLoopLifecycle> lifecycle = new AtomicReference<>(EventLoopLifecycle.NEW);
    protected final String name;

    protected AbstractLifecycleEventLoop(@NotNull String name) {
        this.name = name;
        disableThreadSafetyCheck(true);
    }

    @Override
    public final void start() {
        throwExceptionIfClosed();

        if (lifecycle.compareAndSet(EventLoopLifecycle.NEW, EventLoopLifecycle.STARTED)) {
            performStart();
        }
    }

    @Override
    public final String name() {
        return name;
    }

    /**
     * Implement whatever this event loop needs to start, will only
     * ever be called once
     */
    protected abstract void performStart();

    @Override
    public final void stop() {
        if (lifecycle.compareAndSet(EventLoopLifecycle.NEW, EventLoopLifecycle.STOPPING)) {
            performStopFromNew();
            lifecycle.set(EventLoopLifecycle.STOPPED);
        } else if (lifecycle.compareAndSet(EventLoopLifecycle.STARTED, EventLoopLifecycle.STOPPING)) {
            performStopFromStarted();
            lifecycle.set(EventLoopLifecycle.STOPPED);
        } else {
            awaitTermination();
        }
    }

    /**
     * Implement a stop from {@link EventLoopLifecycle#NEW} state, should block until all
     * handlers have had {@link EventHandler#loopFinished()} called.
     */
    protected abstract void performStopFromNew();

    /**
     * Implement a stop from {@link EventLoopLifecycle#STARTED} state, should block until all
     * handlers have completed their final iteration and had
     * {@link EventHandler#loopFinished()} called.
     */
    protected abstract void performStopFromStarted();

    @Override
    public final void awaitTermination() {
        long endTime = System.currentTimeMillis() + AWAIT_TERMINATION_TIMEOUT_MS;
        while (!Thread.currentThread().isInterrupted()) {
            if (lifecycle.get() == EventLoopLifecycle.STOPPED)
                return;
            if (System.currentTimeMillis() > endTime) {
                Jvm.error().on(getClass(), "awaitTermination() timed out, continuing. This probably represents a bug.");
            }
            Jvm.pause(1);
        }
        if (lifecycle.get() != EventLoopLifecycle.STOPPED) {
            Jvm.warn().on(getClass(), "awaitTermination() interrupted, returning in state " + lifecycle.get());
        }
    }

    @Override
    protected void performClose() {
        stop();
    }

    protected boolean isStarted() {
        return lifecycle.get() == EventLoopLifecycle.STARTED;
    }

    @Override
    public boolean isStopped() {
        return lifecycle.get() == EventLoopLifecycle.STOPPED;
    }
}
