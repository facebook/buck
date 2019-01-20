/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.util;

import com.facebook.buck.core.exceptions.ThrowableCauseIterable;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;

public class MoreThrowables {

  private MoreThrowables() {}

  private static InterruptedException asInterruptedException(Throwable throwable) {
    InterruptedException e = new InterruptedException();
    e.initCause(throwable);
    return e;
  }

  /** Propagates an {@link InterruptedException} masquerading as another {@code Throwable}. */
  public static void propagateIfInterrupt(Throwable thrown) throws InterruptedException {

    // If it's already an `InterruptedException`, just rethrow it.
    if (thrown instanceof InterruptedException) {
      throw (InterruptedException) thrown;
    }

    // Thrown when a thread is interrupted while blocked on I/O.  So propagate this as
    // an `InterruptedException`.
    if (thrown instanceof ClosedByInterruptException) {
      throw asInterruptedException(thrown);
    }

    // `InterruptedIOException` can also be thrown when a thread is interrupted while blocked
    // by I/O, so propagate this -- unless it's a `SocketTimeoutException` which is thrown when
    // when a the timeout set on a socket is triggered.
    if (thrown instanceof InterruptedIOException && !(thrown instanceof SocketTimeoutException)) {
      throw asInterruptedException(thrown);
    }
  }

  /** If throwable has a non-empty cause, returns throwable at the bottom of the stack. */
  public static Throwable getInitialCause(Throwable throwable) {
    return Iterables.getLast(ThrowableCauseIterable.of(throwable));
  }

  /**
   * Returns string representing class, method, filename and line number that throwable was thrown
   * from
   */
  public static String getThrowableOrigin(Throwable throwable) {
    StackTraceElement[] stack = throwable.getStackTrace();
    Preconditions.checkState(stack.length > 0);
    StackTraceElement element = stack[0];
    return element.toString();
  }

  /**
   * Traverse exception chain by recursively calling {@code getCause()} and throws it if there is
   * any exception found which is an instance of {@code declaredType}. Example usage:
   *
   * <pre>
   *   try {
   *     future.get()
   *   } catch (ExecutionException) {
   *     MoreThrowables.throwIfAnyCauseInstanceOf(t, BarException.class);
   *   }
   */
  public static <X extends Throwable> void throwIfAnyCauseInstanceOf(
      Throwable throwable, Class<X> declaredType) throws X {
    for (Throwable cause : ThrowableCauseIterable.of(throwable)) {
      Throwables.throwIfInstanceOf(cause, declaredType);
    }
  }

  /**
   * Traverse exception chain by recursively calling {@code getCause()} and throws it if initial
   * exception (the one at the bottom of the stack) is an instance of {@code declaredType}.
   * Example usage:
   *
   * <pre>
   *   try {
   *     future.get()
   *   } catch (ExecutionException) {
   *     MoreThrowables.throwIfInitialCauseInstanceOf(t, BarException.class);
   *   }
   */
  public static <X extends Throwable> void throwIfInitialCauseInstanceOf(
      Throwable throwable, Class<X> declaredType) throws X {
    Throwable cause = getInitialCause(throwable);
    Throwables.throwIfInstanceOf(cause, declaredType);
  }
}
