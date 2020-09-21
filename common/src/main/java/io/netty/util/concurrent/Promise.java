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

/**
 * TODO: 可写的future,可以给future设置执行结果
 *
 * Special {@link Future} which is writable.
 */
public interface Promise<V> extends Future<V> {

    /**
     * TODO: 设置标记这个future 是成功的，并且唤醒所有的listeners
     * Marks this future as a success and notifies all
     * listeners.
     *
     * If it is success or failed already it will throw an {@link IllegalStateException}.
     */
    Promise<V> setSuccess(V result);

    /**
     * TODO: 尝试去设置future成功
     * Marks this future as a success and notifies all
     * listeners.
     * 如果成功的标记了future是成功的，那么会返回true, 否则返回false 因为这个future已经被标记为成功的或者是失败的了。
     *
     * @return {@code true} if and only if successfully marked this future as
     *         a success. Otherwise {@code false} because this future is
     *         already marked as either a success or a failure.
     */
    boolean trySuccess(V result);

    /**
     * TODO: 标记这个future是一个失败的，并且唤醒所有的listener
     *
     * Marks this future as a failure and notifies all
     * listeners.
     *
     * If it is success or failed already it will throw an {@link IllegalStateException}.
     */
    Promise<V> setFailure(Throwable cause);

    /**
     * TODO: 尝试标记这个future是失败的，并且唤醒所有的listener
     * Marks this future as a failure and notifies all
     * listeners.
     *
     * TODO: 如果标记成功了会返回true, 否则返回false,因为它这个future已经是成功的或者是失败的了
     *
     * @return {@code true} if and only if successfully marked this future as
     *         a failure. Otherwise {@code false} because this future is
     *         already marked as either a success or a failure.
     */
    boolean tryFailure(Throwable cause);

    /**
     * TODO: 标记这个future不可以去取消，如果设置成功会返回true,否则返回false, 因为已经被取消了
     * Make this future impossible to cancel.
     *
     * @return {@code true} if and only if successfully marked this future as uncancellable or it is already done
     *         without being cancelled.  {@code false} if this future has been cancelled already.
     */
    boolean setUncancellable();

    /**
     * TODO: 为当前future 添加一个listener
     *
     * @param listener
     * @return
     */
    @Override
    Promise<V> addListener(GenericFutureListener<? extends Future<? super V>> listener);

    /**
     * TODO: 添加多个listener
     *
     * @param listeners
     * @return
     */
    @Override
    Promise<V> addListeners(GenericFutureListener<? extends Future<? super V>>... listeners);

    /* ---------------- 移除listener -------------- */

    @Override
    Promise<V> removeListener(GenericFutureListener<? extends Future<? super V>> listener);

    @Override
    Promise<V> removeListeners(GenericFutureListener<? extends Future<? super V>>... listeners);

    @Override
    Promise<V> await() throws InterruptedException;

    @Override
    Promise<V> awaitUninterruptibly();

    @Override
    Promise<V> sync() throws InterruptedException;

    @Override
    Promise<V> syncUninterruptibly();
}
