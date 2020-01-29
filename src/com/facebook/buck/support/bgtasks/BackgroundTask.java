/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.support.bgtasks;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.immutables.value.Value;

/**
 * Abstract class for background tasks to be run after build, e.g. cleanup/logging. Tasks take an
 * action (e.g. close an event listener) and the arguments for that action. Tasks should be run by a
 * {@link BackgroundTaskManager}.
 */
@BuckStyleValue
public abstract class BackgroundTask<T> {

  abstract String getName();

  abstract TaskAction<T> getAction();

  abstract T getActionArgs();

  abstract Optional<Timeout> getTimeout();

  @Value.Default
  boolean getShouldCancelOnRepeat() {
    return false;
  }

  public static <T> BackgroundTask<T> of(String name, TaskAction<T> action, T actionArgs) {
    return of(name, action, actionArgs, Optional.empty(), false);
  }

  public static <T> BackgroundTask<T> of(
      String name, TaskAction<T> action, T actionArgs, Timeout timeout) {
    return of(name, action, actionArgs, Optional.of(timeout), false);
  }

  public static <T> BackgroundTask<T> of(
      String name,
      TaskAction<T> action,
      T actionArgs,
      Optional<? extends BackgroundTask.Timeout> timeout,
      boolean shouldCancelOnRepeat) {
    return ImmutableBackgroundTask.of(name, action, actionArgs, timeout, shouldCancelOnRepeat);
  }

  /** Timeout object for {@link BackgroundTask}. */
  @BuckStyleValue
  public abstract static class Timeout {
    abstract long timeout();

    abstract TimeUnit unit();

    public static Timeout of(long timeout, TimeUnit unit) {
      return ImmutableTimeout.of(timeout, unit);
    }
  }
}
