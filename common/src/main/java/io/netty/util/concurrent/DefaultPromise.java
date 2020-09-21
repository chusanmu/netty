/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util.concurrent;

import io.netty.util.internal.InternalThreadLocalMap;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.ThrowableUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static io.netty.util.internal.ObjectUtil.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class DefaultPromise<V> extends AbstractFuture<V> implements Promise<V> {
    private static final InternalLogger logger = InternalLoggerFactory.getInstance(DefaultPromise.class);
    private static final InternalLogger rejectedExecutionLogger =
            InternalLoggerFactory.getInstance(DefaultPromise.class.getName() + ".rejectedExecution");
    private static final int MAX_LISTENER_STACK_DEPTH = Math.min(8,
            SystemPropertyUtil.getInt("io.netty.defaultPromise.maxListenerStackDepth", 8));
    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<DefaultPromise, Object> RESULT_UPDATER =
            AtomicReferenceFieldUpdater.newUpdater(DefaultPromise.class, Object.class, "result");
    private static final Object SUCCESS = new Object();
    private static final Object UNCANCELLABLE = new Object();
    private static final CauseHolder CANCELLATION_CAUSE_HOLDER = new CauseHolder(ThrowableUtil.unknownStackTrace(
            new CancellationException(), DefaultPromise.class, "cancel(...)"));
    private static final StackTraceElement[] CANCELLATION_STACK = CANCELLATION_CAUSE_HOLDER.cause.getStackTrace();

    /**
     * TODO: 存储着future 执行结果
     */
    private volatile Object result;
    private final EventExecutor executor;
    /**
     * TODO: listeners对象，一个或者是多个
     *
     * One or more listeners. Can be a {@link GenericFutureListener} or a {@link DefaultFutureListeners}.
     * If {@code null}, it means either 1) no listeners were added yet or 2) all listeners were notified.
     *
     * Threading - synchronized(this). We must support adding listeners when there is no EventExecutor.
     */
    private Object listeners;
    /**
     * Threading - synchronized(this). We are required to hold the monitor to use Java's underlying wait()/notifyAll().
     */
    private short waiters;

    /**
     * TODO: 是否正在回调listener标记
     * Threading - synchronized(this). We must prevent concurrent notification and FIFO listener notification if the
     * executor changes.
     */
    private boolean notifyingListeners;

    /**
     * Creates a new instance.
     *
     * It is preferable to use {@link EventExecutor#newPromise()} to create a new promise
     *
     * @param executor
     *        the {@link EventExecutor} which is used to notify the promise once it is complete.
     *        It is assumed this executor will protect against {@link StackOverflowError} exceptions.
     *        The executor may be used to avoid {@link StackOverflowError} by executing a {@link Runnable} if the stack
     *        depth exceeds a threshold.
     *
     */
    public DefaultPromise(EventExecutor executor) {
        this.executor = checkNotNull(executor, "executor");
    }

    /**
     * See {@link #executor()} for expectations of the executor.
     */
    protected DefaultPromise() {
        // only for subclasses
        executor = null;
    }

    /**
     * TODO:  设置成功结果
     * @param result
     * @return
     */
    @Override
    public Promise<V> setSuccess(V result) {
        // TODO: 去设置成功
        if (setSuccess0(result)) {
            return this;
        }
        throw new IllegalStateException("complete already: " + this);
    }

    @Override
    public boolean trySuccess(V result) {
        return setSuccess0(result);
    }

    @Override
    public Promise<V> setFailure(Throwable cause) {
        // TODO: 设置失败，注意结果为cause
        if (setFailure0(cause)) {
            return this;
        }
        throw new IllegalStateException("complete already: " + this, cause);
    }

    @Override
    public boolean tryFailure(Throwable cause) {
        return setFailure0(cause);
    }


    /**
     * TODO: 设置不可取消
     * @return
     */
    @Override
    public boolean setUncancellable() {
        // TODO: 把 UNCANCELLABLE 赋值给 result，然后直接返回true就可以了
        if (RESULT_UPDATER.compareAndSet(this, null, UNCANCELLABLE)) {
            return true;
        }
        // TODO: 否则把result结果拿到
        Object result = this.result;
        // TODO: 判断是否已经完成，或者已经取消，如果已经完成或者已经取消了，那最终就返回false
        return !isDone0(result) || !isCancelled0(result);
    }

    /**
     * TODO: 判断是否成功
     * @return
     */
    @Override
    public boolean isSuccess() {
        // TODO: 只要结果不为空，并且结果不是UNCANCELLABLE 也不是 异常CauseHolder， 那就返回true
        Object result = this.result;
        return result != null && result != UNCANCELLABLE && !(result instanceof CauseHolder);
    }

    /**
     * TODO: 判断是否可以取消，只要 result 为空，那就是可以取消的
     * @return
     */
    @Override
    public boolean isCancellable() {
        return result == null;
    }

    private static final class LeanCancellationException extends CancellationException {
        private static final long serialVersionUID = 2794674970981187807L;

        @Override
        public Throwable fillInStackTrace() {
            setStackTrace(CANCELLATION_STACK);
            return this;
        }

        @Override
        public String toString() {
            return CancellationException.class.getName();
        }
    }

    @Override
    public Throwable cause() {
        return cause0(result);
    }

    private Throwable cause0(Object result) {
        if (!(result instanceof CauseHolder)) {
            return null;
        }
        // TODO: 如果取消的话，会把结果设置成 CANCELLATION_CAUSE_HOLDER
        if (result == CANCELLATION_CAUSE_HOLDER) {
            CancellationException ce = new LeanCancellationException();
            // TODO: 把结果设置成 CauseHolder
            if (RESULT_UPDATER.compareAndSet(this, CANCELLATION_CAUSE_HOLDER, new CauseHolder(ce))) {
                return ce;
            }
            result = this.result;
        }
        return ((CauseHolder) result).cause;
    }

    /**
     * TODO: 往future上面添加 Listener 监听
     * @param listener
     * @return
     */
    @Override
    public Promise<V> addListener(GenericFutureListener<? extends Future<? super V>> listener) {
        checkNotNull(listener, "listener");
        // TODO: 上锁，添加监听
        synchronized (this) {
            addListener0(listener);
        }
        // TODO: 如果当前future已经是完成状态了，那好，直接唤醒listeners，会包括刚才添加进去的
        if (isDone()) {
            notifyListeners();
        }

        return this;
    }

    /**
     * TODO: 这个方法是添加多个listener, 注意如果有一个为null, 它后面的就不会再添加了
     * @param listeners
     * @return
     */
    @Override
    public Promise<V> addListeners(GenericFutureListener<? extends Future<? super V>>... listeners) {
        checkNotNull(listeners, "listeners");

        synchronized (this) {
            for (GenericFutureListener<? extends Future<? super V>> listener : listeners) {
                // TODO: 如果当前listener为空，那它后面的就不会再添加了
                if (listener == null) {
                    break;
                }
                addListener0(listener);
            }
        }

        // TODO: 如果完成，去唤醒所有的listeners
        if (isDone()) {
            notifyListeners();
        }

        return this;
    }

    /**
     * TODO: 移除某个listener
     *
     * @param listener
     * @return
     */
    @Override
    public Promise<V> removeListener(final GenericFutureListener<? extends Future<? super V>> listener) {
        checkNotNull(listener, "listener");

        synchronized (this) {
            removeListener0(listener);
        }

        return this;
    }

    @Override
    public Promise<V> removeListeners(final GenericFutureListener<? extends Future<? super V>>... listeners) {
        checkNotNull(listeners, "listeners");

        // TODO: 移除多个Listener
        synchronized (this) {
            for (GenericFutureListener<? extends Future<? super V>> listener : listeners) {
                // TODO: 如果有一个为null, 就不会再移除了
                if (listener == null) {
                    break;
                }
                removeListener0(listener);
            }
        }

        return this;
    }

    /**
     * TODO: await() 等待
     * @return
     * @throws InterruptedException
     */
    @Override
    public Promise<V> await() throws InterruptedException {
        // TODO: 如果当前已经完成，则直接返回它自己就可以了
        if (isDone()) {
            return this;
        }

        // TODO: 如果当前线程被中断了
        if (Thread.interrupted()) {
            throw new InterruptedException(toString());
        }

        checkDeadLock();

        synchronized (this) {
            // TODO: 如果这个future没有完成
            while (!isDone()) {
                // TODO: waiters个数+1
                incWaiters();
                try {
                    // TODO: 当前线程直接wait住了
                    wait();
                } finally {
                    // TODO: 最后被唤醒 waiters个数减一
                    decWaiters();
                }
            }
        }
        return this;
    }

    /**
     * TODO: 不可中断的await, 如果上面那个wait被中断了，会立马抛个异常，而这个方法不会，它已经catch住了
     * @return
     */
    @Override
    public Promise<V> awaitUninterruptibly() {
        if (isDone()) {
            return this;
        }

        checkDeadLock();

        boolean interrupted = false;
        synchronized (this) {
            while (!isDone()) {
                // TODO: waiters个数+1
                incWaiters();
                try {
                    // TODO: 进行wait,注意把中断异常进行了catch
                    wait();
                } catch (InterruptedException e) {
                    // Interrupted while waiting.
                    interrupted = true;
                } finally {
                    decWaiters();
                }
            }
        }
        // TODO: 最后，重新标记中断异常标志
        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        return this;
    }

    @Override
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        return await0(unit.toNanos(timeout), true);
    }

    /**
     * TODO: 可超时的等待，并且可被中断
     *
     * @param timeoutMillis
     * @return
     * @throws InterruptedException
     */
    @Override
    public boolean await(long timeoutMillis) throws InterruptedException {
        return await0(MILLISECONDS.toNanos(timeoutMillis), true);
    }

    @Override
    public boolean awaitUninterruptibly(long timeout, TimeUnit unit) {
        try {
            return await0(unit.toNanos(timeout), false);
        } catch (InterruptedException e) {
            // Should not be raised at all.
            throw new InternalError();
        }
    }

    @Override
    public boolean awaitUninterruptibly(long timeoutMillis) {
        try {
            return await0(MILLISECONDS.toNanos(timeoutMillis), false);
        } catch (InterruptedException e) {
            // Should not be raised at all.
            throw new InternalError();
        }
    }

    /**
     * TODO: 获取结果, 注意这个方法是非阻塞的，所以拿到的结果可能是null.
     *
     * @return
     */
    @SuppressWarnings("unchecked")
    @Override
    public V getNow() {
        // TODO: 把结果拿到，如果结果是 CauseHolder SUCCESS UNCANCELLABLE， 那就直接返回null, 否则把结果返回回去
        Object result = this.result;
        if (result instanceof CauseHolder || result == SUCCESS || result == UNCANCELLABLE) {
            return null;
        }
        return (V) result;
    }

    /**
     * TODO: 把result结果放到result中，
     *
     * @return
     * @throws InterruptedException
     * @throws ExecutionException
     */
    @SuppressWarnings("unchecked")
    @Override
    public V get() throws InterruptedException, ExecutionException {
        Object result = this.result;
        // TODO: 如果当前future还未完成，那就进行await
        if (!isDone0(result)) {
            // TODO: 当前线程await住，等待结果完成后会notify
            await();
            result = this.result;
        }
        // TODO: 如果当前结果是SUCCESS 或者 UNCANCELLABLE 那就返回null吧
        if (result == SUCCESS || result == UNCANCELLABLE) {
            return null;
        }
        // TODO: 获取异常结果
        Throwable cause = cause0(result);
        // TODO: 如果为空，就把result返回回去吧
        if (cause == null) {
            return (V) result;
        }
        // TODO: 最后开始处理 抛出异常
        if (cause instanceof CancellationException) {
            throw (CancellationException) cause;
        }
        throw new ExecutionException(cause);
    }

    /**
     * TODO: 此方法包含等待时间，阻塞的时候 会有超时
     * @param timeout
     * @param unit
     * @return
     * @throws InterruptedException
     * @throws ExecutionException
     * @throws TimeoutException
     */
    @SuppressWarnings("unchecked")
    @Override
    public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        Object result = this.result;
        if (!isDone0(result)) {
            if (!await(timeout, unit)) {
                throw new TimeoutException();
            }
            result = this.result;
        }
        if (result == SUCCESS || result == UNCANCELLABLE) {
            return null;
        }
        Throwable cause = cause0(result);
        if (cause == null) {
            return (V) result;
        }
        if (cause instanceof CancellationException) {
            throw (CancellationException) cause;
        }
        throw new ExecutionException(cause);
    }

    /**
     * {@inheritDoc}
     *
     * @param mayInterruptIfRunning this value has no effect in this implementation.
     */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        // TODO: 取消，把当前结果 设置为 CANCELLATION_CAUSE_HOLDER
        if (RESULT_UPDATER.compareAndSet(this, null, CANCELLATION_CAUSE_HOLDER)) {
            // TODO: 去检查waiters，如果存在 waiters个数 大于0，则进行notifyAll 唤醒所有的等待线程
            if (checkNotifyWaiters()) {
                // TODO: 然后进行通知所有的listeners
                notifyListeners();
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean isCancelled() {
        return isCancelled0(result);
    }

    /**
     * 判断是否已经完成
     * @return
     */
    @Override
    public boolean isDone() {
        return isDone0(result);
    }

    /**
     * TODO: 进行同步等待future执行完毕
     * @return
     * @throws InterruptedException
     */
    @Override
    public Promise<V> sync() throws InterruptedException {
        // TODO: 让当前线程去wait,
        await();
        // TODO: 判断是否有异常产生
        rethrowIfFailed();
        return this;
    }

    /**
     * TODO: 不可中断的进行同步wait住
     *
     * @return
     */
    @Override
    public Promise<V> syncUninterruptibly() {
        // TODO: 调用不可中断的wait
        awaitUninterruptibly();
        // TODO: 检查如果有异常的话，进行抛出
        rethrowIfFailed();
        return this;
    }

    @Override
    public String toString() {
        return toStringBuilder().toString();
    }

    protected StringBuilder toStringBuilder() {
        StringBuilder buf = new StringBuilder(64)
                .append(StringUtil.simpleClassName(this))
                .append('@')
                .append(Integer.toHexString(hashCode()));

        Object result = this.result;
        if (result == SUCCESS) {
            buf.append("(success)");
        } else if (result == UNCANCELLABLE) {
            buf.append("(uncancellable)");
        } else if (result instanceof CauseHolder) {
            buf.append("(failure: ")
                    .append(((CauseHolder) result).cause)
                    .append(')');
        } else if (result != null) {
            buf.append("(success: ")
                    .append(result)
                    .append(')');
        } else {
            buf.append("(incomplete)");
        }

        return buf;
    }

    /**
     * Get the executor used to notify listeners when this promise is complete.
     * <p>
     * It is assumed this executor will protect against {@link StackOverflowError} exceptions.
     * The executor may be used to avoid {@link StackOverflowError} by executing a {@link Runnable} if the stack
     * depth exceeds a threshold.
     * @return The executor used to notify listeners when this promise is complete.
     */
    protected EventExecutor executor() {
        return executor;
    }

    protected void checkDeadLock() {
        EventExecutor e = executor();
        // TODO: 如果executor是事件循环线程，则抛出异常
        if (e != null && e.inEventLoop()) {
            throw new BlockingOperationException(toString());
        }
    }

    /**
     * Notify a listener that a future has completed.
     * <p>
     * This method has a fixed depth of {@link #MAX_LISTENER_STACK_DEPTH} that will limit recursion to prevent
     * {@link StackOverflowError} and will stop notifying listeners added after this threshold is exceeded.
     * @param eventExecutor the executor to use to notify the listener {@code listener}.
     * @param future the future that is complete.
     * @param listener the listener to notify.
     */
    protected static void notifyListener(
            EventExecutor eventExecutor, final Future<?> future, final GenericFutureListener<?> listener) {
        notifyListenerWithStackOverFlowProtection(
                checkNotNull(eventExecutor, "eventExecutor"),
                checkNotNull(future, "future"),
                checkNotNull(listener, "listener"));
    }

    /**
     * TODO: 唤醒所有的listeners
     */
    private void notifyListeners() {
        EventExecutor executor = executor();
        if (executor.inEventLoop()) {
            final InternalThreadLocalMap threadLocals = InternalThreadLocalMap.get();
            final int stackDepth = threadLocals.futureListenerStackDepth();
            // TODO: 这里去检查 最大 listener的 栈深度,默认不设置的话 是8，这里的意思应该是listener套listener，最多套8层
            if (stackDepth < MAX_LISTENER_STACK_DEPTH) {
                threadLocals.setFutureListenerStackDepth(stackDepth + 1);
                try {
                    // TODO: 这里去唤醒所有的listeners
                    notifyListenersNow();
                } finally {
                    // TODO: 将stack深度 还原
                    threadLocals.setFutureListenerStackDepth(stackDepth);
                }
                return;
            }
        }
        // TODO: 这里直接向executor里提交了一个任务 去进行唤醒所有的listeners
        safeExecute(executor, new Runnable() {
            @Override
            public void run() {
                notifyListenersNow();
            }
        });
    }

    /**
     * The logic in this method should be identical to {@link #notifyListeners()} but
     * cannot share code because the listener(s) cannot be cached for an instance of {@link DefaultPromise} since the
     * listener(s) may be changed and is protected by a synchronized operation.
     */
    private static void notifyListenerWithStackOverFlowProtection(final EventExecutor executor,
                                                                  final Future<?> future,
                                                                  final GenericFutureListener<?> listener) {
        if (executor.inEventLoop()) {
            final InternalThreadLocalMap threadLocals = InternalThreadLocalMap.get();
            final int stackDepth = threadLocals.futureListenerStackDepth();
            if (stackDepth < MAX_LISTENER_STACK_DEPTH) {
                threadLocals.setFutureListenerStackDepth(stackDepth + 1);
                try {
                    notifyListener0(future, listener);
                } finally {
                    threadLocals.setFutureListenerStackDepth(stackDepth);
                }
                return;
            }
        }

        safeExecute(executor, new Runnable() {
            @Override
            public void run() {
                notifyListener0(future, listener);
            }
        });
    }

    private void notifyListenersNow() {
        Object listeners;
        // TODO: 因为操作的都是实例变量，多线程操作，需要锁住实例
        synchronized (this) {
            // Only proceed if there are listeners to notify and we are not already notifying listeners.
            // TODO: 如果正在唤醒listeners 或者 listeners 为null, 那就直接返回吧
            if (notifyingListeners || this.listeners == null) {
                return;
            }
            // TODO: 将正在唤醒的Listeners标注为true
            notifyingListeners = true;
            // TODO: 把当前listeners清空，放到局部变量里面
            listeners = this.listeners;
            this.listeners = null;
        }
        // TODO: 注意下面是个死循环
        for (;;) {
            // TODO: 分不同情况去处理
            if (listeners instanceof DefaultFutureListeners) {
                notifyListeners0((DefaultFutureListeners) listeners);
            } else {
                notifyListener0(this, (GenericFutureListener<?>) listeners);
            }
            // TODO: 再次加锁
            synchronized (this) {
                // TODO: 再次判断 listeners是否为空，因为唤醒的过程中，可能会有新的listener被添加上
                if (this.listeners == null) {
                    // Nothing can throw from within this method, so setting notifyingListeners back to false does not
                    // need to be in a finally block.
                    // TODO: 唤醒完了，把唤醒Listeners标注为false
                    notifyingListeners = false;
                    // TODO: 然后return 返回掉 就可以了
                    return;
                }
                // TODO: 再次对listeners赋值，然后清空listeners
                listeners = this.listeners;
                this.listeners = null;
            }
        }
    }

    private void notifyListeners0(DefaultFutureListeners listeners) {
        // TODO: 把所有的listeners拿到，然后进行回调
        GenericFutureListener<?>[] a = listeners.listeners();
        int size = listeners.size();
        for (int i = 0; i < size; i ++) {
            // TODO: 按顺序的 挨个回调 listener
            notifyListener0(this, a[i]);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void notifyListener0(Future future, GenericFutureListener l) {
        try {
            // TODO: 这里进行回调listener的 operationComplete 方法，然后把当前 future 传进去
            l.operationComplete(future);
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("An exception was thrown by " + l.getClass().getName() + ".operationComplete()", t);
            }
        }
    }

    /**
     * TODO: 向future上面添加listener监听
     *
     * @param listener
     */
    private void addListener0(GenericFutureListener<? extends Future<? super V>> listener) {
        // TODO: 如果listeners为空，那就直接把listener赋值给它
        if (listeners == null) {
            listeners = listener;
            // TODO: 如果listeners是DefaultFutureListeners类型的，那就把listener添加到 DefaultFutureListeners中
        } else if (listeners instanceof DefaultFutureListeners) {
            ((DefaultFutureListeners) listeners).add(listener);
        } else {
            // TODO: 否则，就把当前存在的listeners和listener放到 DefaultFutureListeners中组合在一块，把两个listeners组合在一起
            listeners = new DefaultFutureListeners((GenericFutureListener<?>) listeners, listener);
        }
    }

    /**
     * TODO: 移除listener
     *
     * @param listener
     */
    private void removeListener0(GenericFutureListener<? extends Future<? super V>> listener) {
        // TODO: 如果是DefaultFutureListeners 则进行remove
        if (listeners instanceof DefaultFutureListeners) {
            ((DefaultFutureListeners) listeners).remove(listener);
        } else if (listeners == listener) {
            // TODO: 否则设置为null
            listeners = null;
        }
    }

    private boolean setSuccess0(V result) {
        // TODO: 这里设置成功结果，如果result == null 就放一个固定的Object对象
        return setValue0(result == null ? SUCCESS : result);
    }

    private boolean setFailure0(Throwable cause) {
        // TODO: 把失败结果放进value里面去
        return setValue0(new CauseHolder(checkNotNull(cause, "cause")));
    }

    /**
     * TODO: 设置执行结果
     *
     * @param objResult
     * @return
     */
    private boolean setValue0(Object objResult) {
        // TODO: 这里是一个原子性的更新，保证只执行一次，期望是一个null,或者是UNCANCELLABLE, 然后把结果设置进去
        if (RESULT_UPDATER.compareAndSet(this, null, objResult) ||
            RESULT_UPDATER.compareAndSet(this, UNCANCELLABLE, objResult)) {
            // TODO: 设置成功之后，唤醒listeners, 也会唤醒所有的 wait 线程
            if (checkNotifyWaiters()) {
                // TODO: 唤醒所有的listeners
                notifyListeners();
            }
            return true;
        }
        return false;
    }

    /**
     * Check if there are any waiters and if so notify these.
     * @return {@code true} if there are any listeners attached to the promise, {@code false} otherwise.
     */
    private synchronized boolean checkNotifyWaiters() {
        // TODO: 如果waiters大于0 则唤醒所有的
        if (waiters > 0) {
            notifyAll();
        }
        return listeners != null;
    }

    private void incWaiters() {
        if (waiters == Short.MAX_VALUE) {
            throw new IllegalStateException("too many waiters: " + this);
        }
        ++waiters;
    }

    private void decWaiters() {
        --waiters;
    }

    private void rethrowIfFailed() {
        // TODO: 如果没有异常产生，直接返回就可以了
        Throwable cause = cause();
        if (cause == null) {
            return;
        }
        // TODO: 有异常，最后抛出异常
        PlatformDependent.throwException(cause);
    }

    /**
     * TODO: 最终的await方法
     *
     * @param timeoutNanos
     * @param interruptable
     * @return
     * @throws InterruptedException
     */
    private boolean await0(long timeoutNanos, boolean interruptable) throws InterruptedException {
        // TODO: 如果当前future已完成，那就没必要await了，直接返回true
        if (isDone()) {
            return true;
        }
        // TODO: 如果timeoutNanos小于等于0，直接把isDone结果返回就行了
        if (timeoutNanos <= 0) {
            return isDone();
        }
        // TODO: 如果当前是支持可中断的，并且线程已经被中断了，那就返回个中断异常
        if (interruptable && Thread.interrupted()) {
            throw new InterruptedException(toString());
        }
        // TODO: 校验死锁
        checkDeadLock();
        // TODO: 记录时间
        long startTime = System.nanoTime();
        long waitTime = timeoutNanos;
        boolean interrupted = false;
        try {
            for (;;) {
                synchronized (this) {
                    // TODO: 如果当前future已经完成，直接返回true
                    if (isDone()) {
                        return true;
                    }
                    // TODO: 把waiter个数加一
                    incWaiters();
                    try {
                        wait(waitTime / 1000000, (int) (waitTime % 1000000));
                    } catch (InterruptedException e) {
                        // TODO: 如果遇到中断，采用两种方式，直接抛出或者最后标记
                        if (interruptable) {
                            throw e;
                        } else {
                            interrupted = true;
                        }
                    } finally {
                        // TODO: 减少waiter
                        decWaiters();
                    }
                }
                // TODO: 如果当前future已经完成了，那就返回true, 等待超时 或者被唤醒后 会走到这
                if (isDone()) {
                    return true;
                } else {
                    // TODO: 重新计算waitTime
                    waitTime = timeoutNanos - (System.nanoTime() - startTime);
                    if (waitTime <= 0) {
                        return isDone();
                    }
                }
            }
        } finally {
            // TODO: 重新标记中断状态
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Notify all progressive listeners.
     * <p>
     * No attempt is made to ensure notification order if multiple calls are made to this method before
     * the original invocation completes.
     * <p>
     * This will do an iteration over all listeners to get all of type {@link GenericProgressiveFutureListener}s.
     * @param progress the new progress.
     * @param total the total progress.
     */
    @SuppressWarnings("unchecked")
    void notifyProgressiveListeners(final long progress, final long total) {
        final Object listeners = progressiveListeners();
        if (listeners == null) {
            return;
        }

        final ProgressiveFuture<V> self = (ProgressiveFuture<V>) this;

        EventExecutor executor = executor();
        if (executor.inEventLoop()) {
            if (listeners instanceof GenericProgressiveFutureListener[]) {
                notifyProgressiveListeners0(
                        self, (GenericProgressiveFutureListener<?>[]) listeners, progress, total);
            } else {
                notifyProgressiveListener0(
                        self, (GenericProgressiveFutureListener<ProgressiveFuture<V>>) listeners, progress, total);
            }
        } else {
            if (listeners instanceof GenericProgressiveFutureListener[]) {
                final GenericProgressiveFutureListener<?>[] array =
                        (GenericProgressiveFutureListener<?>[]) listeners;
                safeExecute(executor, new Runnable() {
                    @Override
                    public void run() {
                        notifyProgressiveListeners0(self, array, progress, total);
                    }
                });
            } else {
                final GenericProgressiveFutureListener<ProgressiveFuture<V>> l =
                        (GenericProgressiveFutureListener<ProgressiveFuture<V>>) listeners;
                safeExecute(executor, new Runnable() {
                    @Override
                    public void run() {
                        notifyProgressiveListener0(self, l, progress, total);
                    }
                });
            }
        }
    }

    /**
     * Returns a {@link GenericProgressiveFutureListener}, an array of {@link GenericProgressiveFutureListener}, or
     * {@code null}.
     */
    private synchronized Object progressiveListeners() {
        Object listeners = this.listeners;
        if (listeners == null) {
            // No listeners added
            return null;
        }

        if (listeners instanceof DefaultFutureListeners) {
            // Copy DefaultFutureListeners into an array of listeners.
            DefaultFutureListeners dfl = (DefaultFutureListeners) listeners;
            int progressiveSize = dfl.progressiveSize();
            switch (progressiveSize) {
                case 0:
                    return null;
                case 1:
                    for (GenericFutureListener<?> l: dfl.listeners()) {
                        if (l instanceof GenericProgressiveFutureListener) {
                            return l;
                        }
                    }
                    return null;
            }

            GenericFutureListener<?>[] array = dfl.listeners();
            GenericProgressiveFutureListener<?>[] copy = new GenericProgressiveFutureListener[progressiveSize];
            for (int i = 0, j = 0; j < progressiveSize; i ++) {
                GenericFutureListener<?> l = array[i];
                if (l instanceof GenericProgressiveFutureListener) {
                    copy[j ++] = (GenericProgressiveFutureListener<?>) l;
                }
            }

            return copy;
        } else if (listeners instanceof GenericProgressiveFutureListener) {
            return listeners;
        } else {
            // Only one listener was added and it's not a progressive listener.
            return null;
        }
    }

    private static void notifyProgressiveListeners0(
            ProgressiveFuture<?> future, GenericProgressiveFutureListener<?>[] listeners, long progress, long total) {
        for (GenericProgressiveFutureListener<?> l: listeners) {
            if (l == null) {
                break;
            }
            notifyProgressiveListener0(future, l, progress, total);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void notifyProgressiveListener0(
            ProgressiveFuture future, GenericProgressiveFutureListener l, long progress, long total) {
        try {
            l.operationProgressed(future, progress, total);
        } catch (Throwable t) {
            if (logger.isWarnEnabled()) {
                logger.warn("An exception was thrown by " + l.getClass().getName() + ".operationProgressed()", t);
            }
        }
    }

    private static boolean isCancelled0(Object result) {
        return result instanceof CauseHolder && ((CauseHolder) result).cause instanceof CancellationException;
    }

    /**
     * TODO：主要是对result进行判断，如果result不为空，并且不是 UNCANCELLABLE ，那就返回true
     * @param result
     * @return
     */
    private static boolean isDone0(Object result) {
        return result != null && result != UNCANCELLABLE;
    }

    private static final class CauseHolder {
        final Throwable cause;
        CauseHolder(Throwable cause) {
            this.cause = cause;
        }
    }

    private static void safeExecute(EventExecutor executor, Runnable task) {
        try {
            executor.execute(task);
        } catch (Throwable t) {
            rejectedExecutionLogger.error("Failed to submit a listener notification task. Event loop shut down?", t);
        }
    }
}
