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

import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;

final class WorkThreadTrackingCompletedFuture<T> implements WorkThreadTrackingFuture<T> {
  private final T value;

  WorkThreadTrackingCompletedFuture(T value) {
    this.value = value;
  }

  @Override
  public T get() {
    return value;
  }

  @Override
  public T get(long timeout, @Nonnull TimeUnit unit) {
    return value;
  }

  @Override
  public boolean isBeingWorkedOnByCurrentThread() {
    return false;
  }

  @Override
  public boolean cancel(boolean mayInterruptIfRunning) {
    return false;
  }

  @Override
  public boolean isCancelled() {
    return false;
  }

  @Override
  public boolean isDone() {
    return true;
  }
}
