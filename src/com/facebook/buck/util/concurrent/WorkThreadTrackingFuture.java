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

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nonnull;

/** A {@link Future} which keeps track of the thread running its computation. */
public interface WorkThreadTrackingFuture<T> extends Future<T> {

  /** {@inheritDoc} */
  @Override
  T get() throws InterruptedException, ExecutionException;

  /** {@inheritDoc} */
  @Override
  T get(long timeout, @Nonnull TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException;

  /** Returns whether the future is being computed by the current thread. */
  boolean isBeingWorkedOnByCurrentThread();
}
