package net.openhft.chronicle.threads;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.util.Collections.singleton;

class EventLoopConcurrencyStressTest extends ThreadsTestCommon {

    private static final int NUM_EVENT_ADDERS = 3;
    private static final int TIME_TO_WAIT_BEFORE_STARTING_STOPPING_MS = 5;
    private static int originalMonitorDelay = -1;

    @BeforeAll
    static void beforeAll() {
        originalMonitorDelay = MonitorEventLoop.MONITOR_INITIAL_DELAY_MS;
        MonitorEventLoop.MONITOR_INITIAL_DELAY_MS = 0;
    }

    @AfterAll
    static void afterAll() {
        MonitorEventLoop.MONITOR_INITIAL_DELAY_MS = originalMonitorDelay;
    }

    @BeforeEach
    void setUp() {
        ignoreException("Only one high handler supported was");
    }

    @TestFactory
    public Stream<DynamicTest> concurrentTestsForEachEventLoop() {
        List<EventLoopTestParameters<?>> eventLoopSuppliers = new ArrayList<>();
        eventLoopSuppliers.add(new EventLoopTestParameters<>(BlockingEventLoop.class,
                () -> new BlockingEventLoop("blockingEventLoop")));
        eventLoopSuppliers.add(new EventLoopTestParameters<>(MediumEventLoop.class,
                () -> new MediumEventLoop(null, "mediumEventLoop", Pauser.balanced(), true, null), MediumEventLoop.ALLOWED_PRIORITIES));
        eventLoopSuppliers.add(new EventLoopTestParameters<>(VanillaEventLoop.class,
                () -> new VanillaEventLoop(null, "vanillaEventLoop", Pauser.balanced(), 1, true, null, VanillaEventLoop.ALLOWED_PRIORITIES), VanillaEventLoop.ALLOWED_PRIORITIES));
        eventLoopSuppliers.add(new EventLoopTestParameters<>(MonitorEventLoop.class,
                () -> new MonitorEventLoop(null, "monitorEventLoop", Pauser.balanced())));
        return eventLoopSuppliers.stream().flatMap(params -> {
            List<DynamicTest> allTests = new ArrayList<>();
            for (HandlerPriority priority : params.priorities) {
                allTests.add(DynamicTest.dynamicTest("canConcurrentlyAddHandlersAndStartEventLoop: " + params.className() + "/" + priority,
                        () -> canConcurrentlyAddHandlersAndStartEventLoop(params::create, priority)));
                allTests.add(DynamicTest.dynamicTest("canConcurrentlyAddHandlersAndStopEventLoop: " + params.className() + "/" + priority,
                        () -> canConcurrentlyAddHandlersAndStopEventLoop(params::create, priority)));
                allTests.add(DynamicTest.dynamicTest("canConcurrentlyAddTerminatingHandlersAndStartEventLoop: " + params.className() + "/" + priority,
                        () -> canConcurrentlyAddTerminatingHandlersAndStartEventLoop(params::create, priority)));
                allTests.add(DynamicTest.dynamicTest("canConcurrentlyAddTerminatingHandlersAndStopEventLoop: " + params.className() + "/" + priority,
                        () -> canConcurrentlyAddTerminatingHandlersAndStartEventLoop(params::create, priority)));
            }
            return allTests.stream();
        });
    }

    static final class EventLoopTestParameters<T extends AbstractLifecycleEventLoop> {
        final Class<T> eventLoopClass;
        final Supplier<T> eventLoopSupplier;
        final Set<HandlerPriority> priorities;

        public EventLoopTestParameters(Class<T> eventLoopClass, Supplier<T> eventLoopSupplier) {
            this(eventLoopClass, eventLoopSupplier, singleton(HandlerPriority.MEDIUM));
        }

        public EventLoopTestParameters(Class<T> eventLoopClass, Supplier<T> eventLoopSupplier, Set<HandlerPriority> priorities) {
            this.eventLoopSupplier = eventLoopSupplier;
            this.eventLoopClass = eventLoopClass;
            this.priorities = priorities;
        }

        public T create() {
            return eventLoopSupplier.get();
        }

        public String className() {
            return eventLoopClass.getSimpleName();
        }
    }

    public void canConcurrentlyAddHandlersAndStartEventLoop(Supplier<AbstractLifecycleEventLoop> eventLoopSupplier, HandlerPriority priority) throws TimeoutException {
        ExecutorService executorService = Executors.newCachedThreadPool();
        try (AbstractLifecycleEventLoop eventLoop = eventLoopSupplier.get()) {
            List<HandlerAdder> handlerAdders = new ArrayList<>();
            CyclicBarrier cyclicBarrier = new CyclicBarrier(NUM_EVENT_ADDERS + 1);
            final EventLoopStarter eventLoopStarter = new EventLoopStarter(eventLoop, cyclicBarrier);
            executorService.submit(eventLoopStarter);
            for (int i = 0; i < NUM_EVENT_ADDERS; i++) {
                final HandlerAdder handlerAdder = new HandlerAdder(eventLoop, cyclicBarrier, () -> new ControllableHandler(priority));
                executorService.submit(handlerAdder);
                handlerAdders.add(handlerAdder);
            }
            // wait until the starter has started the event loop
            eventLoopStarter.waitUntilEventLoopStarted();
            // Stop adding new handlers
            handlerAdders.forEach(HandlerAdder::stopAddingHandlers);
            // All added handlers should eventually start
            TimingPauser pauser = Pauser.balanced();
            while (!handlerAdders.stream().allMatch(HandlerAdder::allHandlersAreStarted)) {
                pauser.pause(3, TimeUnit.SECONDS);
            }
            // Stop all handlers
            handlerAdders.forEach(HandlerAdder::stopAllHandlers);
            // All handlers should eventually stop
            pauser.reset();
            pauser = Pauser.balanced();
            while (!handlerAdders.stream().allMatch(HandlerAdder::allHandlersAreStopped)) {
                pauser.pause(3, TimeUnit.SECONDS);
            }
        } finally {
            Threads.shutdown(executorService);
        }
    }

    public void canConcurrentlyAddHandlersAndStopEventLoop(Supplier<AbstractLifecycleEventLoop> eventLoopSupplier, HandlerPriority priority) throws TimeoutException {
        ignoreException("Handler in newHandler was not accepted before");
        ExecutorService executorService = Executors.newCachedThreadPool();
        try (AbstractLifecycleEventLoop eventLoop = eventLoopSupplier.get()) {
            List<HandlerAdder> handlerAdders = new ArrayList<>();
            eventLoop.start();
            CyclicBarrier cyclicBarrier = new CyclicBarrier(NUM_EVENT_ADDERS + 1);
            final EventLoopStopper eventLoopStopper = new EventLoopStopper(eventLoop, cyclicBarrier);
            executorService.submit(eventLoopStopper);
            for (int i = 0; i < NUM_EVENT_ADDERS; i++) {
                final HandlerAdder handlerAdder = new HandlerAdder(eventLoop, cyclicBarrier, () -> new ControllableHandler(priority));
                executorService.submit(handlerAdder);
                handlerAdders.add(handlerAdder);
            }
            eventLoopStopper.waitUntilEventLoopStopped();
            handlerAdders.forEach(HandlerAdder::stopAddingHandlers);
            // All handlers should eventually stop
            TimingPauser pauser = Pauser.balanced();
            while (!handlerAdders.stream().allMatch(HandlerAdder::allHandlersAreStopped)) {
                pauser.pause(3, TimeUnit.SECONDS);
            }
        } finally {
            Threads.shutdown(executorService);
        }
    }

    public void canConcurrentlyAddTerminatingHandlersAndStartEventLoop(Supplier<AbstractLifecycleEventLoop> eventLoopSupplier, HandlerPriority priority) throws TimeoutException {
        ExecutorService executorService = Executors.newCachedThreadPool();
        try (AbstractLifecycleEventLoop eventLoop = eventLoopSupplier.get()) {
            List<HandlerAdder> handlerAdders = new ArrayList<>();
            CyclicBarrier cyclicBarrier = new CyclicBarrier(NUM_EVENT_ADDERS + 1);
            final EventLoopStarter eventLoopStarter = new EventLoopStarter(eventLoop, cyclicBarrier);
            executorService.submit(eventLoopStarter);
            for (int i = 0; i < NUM_EVENT_ADDERS; i++) {
                final HandlerAdder handlerAdder = new HandlerAdder(eventLoop, cyclicBarrier, () -> new ControllableHandler(priority, 0));
                executorService.submit(handlerAdder);
                handlerAdders.add(handlerAdder);
            }
            // wait until the starter has started the event loop
            eventLoopStarter.waitUntilEventLoopStarted();
            // Stop adding new handlers
            handlerAdders.forEach(HandlerAdder::stopAddingHandlers);
            // All handlers should eventually stop
            TimingPauser pauser = Pauser.balanced();
            while (!handlerAdders.stream().allMatch(HandlerAdder::allHandlersAreStopped)) {
                pauser.pause(3, TimeUnit.SECONDS);
            }
        } finally {
            Threads.shutdown(executorService);
        }
    }

    public void canConcurrentlyAddTerminatingHandlersAndStopEventLoop(Supplier<AbstractLifecycleEventLoop> eventLoopSupplier, HandlerPriority priority) throws TimeoutException {
        ignoreException("Handler in newHandler was not accepted before");
        ExecutorService executorService = Executors.newCachedThreadPool();
        try (AbstractLifecycleEventLoop eventLoop = eventLoopSupplier.get()) {
            List<HandlerAdder> handlerAdders = new ArrayList<>();
            eventLoop.start();
            while (!eventLoop.isStarted()) {
                Jvm.pause(1);
            }
            CyclicBarrier cyclicBarrier = new CyclicBarrier(NUM_EVENT_ADDERS + 1);
            final EventLoopStopper eventLoopStopper = new EventLoopStopper(eventLoop, cyclicBarrier);
            executorService.submit(eventLoopStopper);
            for (int i = 0; i < NUM_EVENT_ADDERS; i++) {
                final HandlerAdder handlerAdder = new HandlerAdder(eventLoop, cyclicBarrier, () -> new ControllableHandler(priority, 0));
                executorService.submit(handlerAdder);
                handlerAdders.add(handlerAdder);
            }
            eventLoopStopper.waitUntilEventLoopStopped();
            handlerAdders.forEach(HandlerAdder::stopAddingHandlers);
            // All handlers should eventually stop
            TimingPauser pauser = Pauser.balanced();
            while (!handlerAdders.stream().allMatch(HandlerAdder::allHandlersAreStopped)) {
                pauser.pause(3, TimeUnit.SECONDS);
            }
        } finally {
            Threads.shutdown(executorService);
        }
    }

    static final class EventLoopStarter implements Runnable {
        private final EventLoop eventLoop;
        private final CyclicBarrier cyclicBarrier;
        private final Semaphore hasStartedEventLoop;

        EventLoopStarter(EventLoop eventLoop, CyclicBarrier cyclicBarrier) {
            this.eventLoop = eventLoop;
            this.cyclicBarrier = cyclicBarrier;
            hasStartedEventLoop = new Semaphore(0);
        }

        public void run() {
            try {
                await(cyclicBarrier);
                Jvm.pause(TIME_TO_WAIT_BEFORE_STARTING_STOPPING_MS);
                eventLoop.start();
                hasStartedEventLoop.release();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        public void waitUntilEventLoopStarted() {
            try {
                hasStartedEventLoop.acquire();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    static final class EventLoopStopper implements Runnable {
        private final EventLoop eventLoop;
        private final CyclicBarrier cyclicBarrier;
        private final Semaphore hasStoppedEventLoop;

        EventLoopStopper(EventLoop eventLoop, CyclicBarrier cyclicBarrier) {
            this.eventLoop = eventLoop;
            this.cyclicBarrier = cyclicBarrier;
            hasStoppedEventLoop = new Semaphore(0);
        }

        public void run() {
            try {
                await(cyclicBarrier);
                Jvm.pause(TIME_TO_WAIT_BEFORE_STARTING_STOPPING_MS);
                eventLoop.stop();
                hasStoppedEventLoop.release();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        public void waitUntilEventLoopStopped() {
            try {
                hasStoppedEventLoop.acquire();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    static final class HandlerAdder implements Runnable {
        private static final int MAX_HANDLERS_TO_ADD = 30;

        private final EventLoop eventLoop;
        private final CyclicBarrier cyclicBarrier;
        private final Supplier<ControllableHandler> handlerSupplier;
        private final List<ControllableHandler> addedHandlers;
        private volatile boolean stopAddingHandlers = false;
        private final Semaphore stoppedAddingHandlers;

        HandlerAdder(EventLoop eventLoop, CyclicBarrier cyclicBarrier, Supplier<ControllableHandler> handlerSupplier) {
            this.eventLoop = eventLoop;
            this.cyclicBarrier = cyclicBarrier;
            this.handlerSupplier = handlerSupplier;
            this.addedHandlers = new CopyOnWriteArrayList<>();
            this.stoppedAddingHandlers = new Semaphore(0);
        }

        @Override
        public void run() {
            try {
                await(cyclicBarrier);
                while (!stopAddingHandlers && addedHandlers.size() < MAX_HANDLERS_TO_ADD) {
                    ControllableHandler handler = handlerSupplier.get();
                    eventLoop.addHandler(handler);
                    addedHandlers.add(handler);
                    pauseMicros(ThreadLocalRandom.current().nextInt(100, 300));
                }
                stoppedAddingHandlers.release();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        public void stopAddingHandlers() {
            stopAddingHandlers = true;
            try {
                stoppedAddingHandlers.acquire();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        public void stopAllHandlers() {
            addedHandlers.forEach(ControllableHandler::stop);
        }

        public boolean allHandlersAreStarted() {
            return addedHandlers.stream().allMatch(ControllableHandler::isRunning);
        }

        public boolean allHandlersAreStopped() {
            return addedHandlers.stream().allMatch(ControllableHandler::isComplete);
        }
    }

    static final class ControllableHandler implements AutoCloseable, EventHandler {

        final HandlerPriority priority;
        final int endOnIteration;
        int iteration = 0;
        volatile boolean loopFinished = false;
        volatile boolean loopStarted = false;
        volatile boolean closed = false;
        volatile boolean exitOnNextIteration = false;

        public ControllableHandler(HandlerPriority priority) {
            this(priority, -1);
        }

        @Override
        public void close() {
            closed = true;
        }

        public ControllableHandler(HandlerPriority priority, int endOnIteration) {
            this.priority = priority;
            this.endOnIteration = endOnIteration;
        }

        @Override
        public boolean action() throws InvalidEventHandlerException {
            if (closed) {
                throw InvalidEventHandlerException.reusable();
            } else if (exitOnNextIteration) {
                throw InvalidEventHandlerException.reusable();
            } else if (endOnIteration >= 0 && iteration >= endOnIteration) {
                throw InvalidEventHandlerException.reusable();
            }
            Jvm.pause(ThreadLocalRandom.current().nextInt(10));
            iteration++;
            return false; // handlers need to not be busy to prevent long delays adding handlers that can make the test flap
        }

        @Override
        public void loopStarted() {
            this.loopStarted = true;
        }

        @Override
        public void loopFinished() {
            this.loopFinished = true;
        }

        public void stop() {
            exitOnNextIteration = true;
        }

        public boolean isRunning() {
            return loopStarted && !loopFinished;
        }

        public boolean isComplete() {
            return !loopStarted || loopFinished;
        }

        @Override
        public @NotNull HandlerPriority priority() {
            return priority;
        }
    }

    private static void await(CyclicBarrier cyclicBarrier) {
        try {
            cyclicBarrier.await();
        } catch (InterruptedException | BrokenBarrierException e) {
            throw new RuntimeException(e);
        }
    }

    private static void pauseMicros(long timeToSleepMicros) {
        long endTimeNanos = System.nanoTime() + timeToSleepMicros * 1_000;
        while (System.nanoTime() < endTimeNanos) {
            // do nothing
        }
    }
}
