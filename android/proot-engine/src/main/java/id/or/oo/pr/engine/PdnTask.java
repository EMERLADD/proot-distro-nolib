package id.or.oo.pr.engine;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public final class PdnTask {
    private static final Executor WORKERS = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(128), runnable -> daemon(runnable, "pdn-operation"), new ThreadPoolExecutor.AbortPolicy());
    private static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(1, runnable -> daemon(runnable, "pdn-timeout"));
    static { TIMER.setRemoveOnCancelPolicy(true); }
    private final PdnOperations operations;
    private final ProcessBuilder builder;
    private final PdnListener listener;
    private final Executor callbacks;
    private final CountDownLatch completed = new CountDownLatch(1);
    private Thread worker;
    private boolean finishing;
    private PdnHostException stopped;
    private ScheduledFuture<?> timeout;
    private PdnResult result;
    private Exception failure;

    PdnTask(PdnOperations operations, ProcessBuilder builder, PdnListener listener, long timeoutMillis, Executor callbacks, Executor workers) {
        this.operations = operations;
        this.builder = builder;
        this.listener = Objects.requireNonNull(listener);
        this.callbacks = callbacks;
        if (timeoutMillis < 0) throw new IllegalArgumentException("Timeout cannot be negative");
        if (timeoutMillis > 0) timeout = TIMER.schedule(() -> stop(true), timeoutMillis, TimeUnit.MILLISECONDS);
        try { workers.execute(this::execute); }
        catch (RuntimeException rejected) { finishRejected(rejected); }
    }

    static Executor workers() { return WORKERS; }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    public boolean cancel() { return stop(false); }

    private boolean stop(boolean timedOut) {
        synchronized (this) {
            if (finishing || stopped != null) return false;
            stopped = new PdnHostException(timedOut ? "host_operation_timeout" : "host_operation_cancelled",
                    timedOut ? "Operation timed out" : "Operation cancelled",
                    timedOut ? "Increase the timeout or retry the operation" : "Start a new operation to retry", null);
            if (worker != null) worker.interrupt();
        }
        return true;
    }

    private void execute() {
        boolean cancelled;
        synchronized (this) {
            if (finishing) return;
            cancelled = stopped != null;
            if (!cancelled) worker = Thread.currentThread();
        }
        if (cancelled) { finish(null, null); return; }
        PdnResult value = null;
        Exception error = null;
        try {
            value = operations.run(builder, new PdnListener() {
                public void onEvent(PdnEvent event) { dispatch(() -> listener.onEvent(event)); }
                public void onStdout(byte[] data) { dispatch(() -> listener.onStdout(data)); }
                public void onStderr(byte[] data) { dispatch(() -> listener.onStderr(data)); }
            });
        } catch (CallbackRejectedException caught) { error = (Exception) caught.getCause(); }
        catch (Exception caught) { error = caught; }
        catch (Error caught) { error = new RuntimeException("Operation failed", caught); }
        finally {
            Thread.interrupted();
            finish(value, error);
        }
    }

    private void dispatch(Runnable callback) { dispatch(callback, false); }

    private void dispatch(Runnable callback, boolean terminal) {
        if (!terminal) {
            synchronized (this) {
                if (stopped != null || Thread.currentThread().isInterrupted()) {
                    throw new RuntimeException("Callback dispatch interrupted", new InterruptedException("Operation stopped"));
                }
            }
        }
        if (callbacks == null) { callback.run(); return; }
        AtomicInteger invocation = new AtomicInteger();
        FutureTask<Void> delivered = new FutureTask<>(() -> {
            synchronized (this) {
                if (!terminal && stopped != null) throw new RuntimeException("Callback dispatch interrupted", new InterruptedException("Operation stopped"));
                if (!invocation.compareAndSet(0, 1)) return;
            }
            callback.run();
        }, null);
        try { callbacks.execute(delivered); }
        catch (RuntimeException rejected) { throw new CallbackRejectedException(rejected); }
        boolean interrupted = false;
        try {
            while (true) {
                try { delivered.get(); break; }
                catch (InterruptedException caught) {
                    interrupted = true;
                    if (invocation.compareAndSet(0, 2)) {
                        delivered.cancel(false);
                        throw new RuntimeException("Callback dispatch interrupted", caught);
                    }
                }
                catch (ExecutionException caught) {
                    Throwable cause = caught.getCause();
                    if (cause instanceof Error) throw (Error) cause;
                    if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                    throw new RuntimeException(cause);
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void finishRejected(Exception error) {
        synchronized (this) {
            finishing = true;
            if (timeout != null) timeout.cancel(false);
            if (stopped != null) {
                stopped.addSuppressed(error);
                error = stopped;
            }
            failure = error;
        }
        AtomicInteger invoked = new AtomicInteger();
        Runnable completion = () -> {
            if (!invoked.compareAndSet(0, 1)) return;
            try { listener.onFailure(failure); }
            catch (RuntimeException | Error callbackFailure) {
                if (failure != callbackFailure) failure.addSuppressed(callbackFailure);
            } finally { completed.countDown(); }
        };
        if (callbacks == null) completion.run();
        else {
            try { callbacks.execute(completion); }
            catch (RuntimeException rejected) {
                if (failure != rejected) failure.addSuppressed(rejected);
                completion.run();
            }
        }
    }

    private void finish(PdnResult value, Exception error) {
        synchronized (this) {
            if (finishing) return;
            finishing = true;
            if (timeout != null) timeout.cancel(false);
            if (stopped != null) {
                if (error != null) stopped.addSuppressed(error);
                error = stopped;
            }
            worker = null;
            result = value;
            failure = error;
        }
        try {
            if (error == null) dispatch(() -> listener.onComplete(value), true);
            else {
                Exception delivered = error;
                dispatch(() -> listener.onFailure(delivered), true);
            }
        } catch (CallbackRejectedException rejected) {
            synchronized (this) {
                if (failure == null) failure = (Exception) rejected.getCause();
                else if (failure != rejected.getCause()) failure.addSuppressed(rejected.getCause());
            }
            try { listener.onFailure(failure); }
            catch (RuntimeException | Error callbackFailure) { if (failure != callbackFailure) failure.addSuppressed(callbackFailure); }
        } catch (RuntimeException | Error callbackFailure) {
            synchronized (this) {
                if (failure == null) failure = new RuntimeException("Completion callback failed", callbackFailure);
                else if (failure != callbackFailure) failure.addSuppressed(callbackFailure);
            }
        } finally {
            completed.countDown();
        }
    }

    private static final class CallbackRejectedException extends RuntimeException {
        CallbackRejectedException(RuntimeException cause) { super(cause); }
    }

    public boolean isDone() { return completed.getCount() == 0; }
    public synchronized boolean isCancelled() { return stopped != null; }

    public PdnResult await() throws InterruptedException, ExecutionException {
        completed.await();
        return outcome();
    }

    public PdnResult await(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        if (!completed.await(timeout, Objects.requireNonNull(unit))) throw new TimeoutException("Operation is still running");
        return outcome();
    }

    private synchronized PdnResult outcome() throws ExecutionException {
        if (failure != null) throw new ExecutionException(failure);
        return result;
    }
}
