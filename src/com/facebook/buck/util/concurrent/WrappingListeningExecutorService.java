/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.util.concurrent;

import com.google.common.util.concurrent.ForwardingListeningExecutorService;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.concurrent.Callable;
import javax.annotation.Nullable;

/**
 * A {@link ListeningExecutorService} that wraps the {@link Runnable}s and {@link Callable}s passed
 * into it.
 */
public abstract class WrappingListeningExecutorService extends ForwardingListeningExecutorService {
  private final ListeningExecutorService delegate;

  public WrappingListeningExecutorService(ListeningExecutorService delegate) {
    this.delegate = delegate;
  }

  @Override
  protected ListeningExecutorService delegate() {
    return delegate;
  }

  @Override
  public <T> ListenableFuture<T> submit(Callable<T> task) {
    return super.submit(wrap(task));
  }

  @Override
  public ListenableFuture<?> submit(Runnable task) {
    return submit(wrap(task, null));
  }

  @Override
  public <T> ListenableFuture<T> submit(Runnable task, T result) {
    return super.submit(wrap(task, result));
  }

  @Override
  public void execute(Runnable command) {
    submit(command);
  }

  private <T> Callable<T> wrap(Runnable inner, @Nullable T result) {
    return wrap(
        () -> {
          inner.run();
          return result;
        });
  }

  protected abstract <T> Callable<T> wrap(Callable<T> inner);
}
