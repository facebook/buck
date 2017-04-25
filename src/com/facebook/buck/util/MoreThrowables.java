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

import com.google.common.base.Preconditions;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

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
    if (throwable.getCause() != null) {
      Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
      seen.add(throwable);
      return getInitialCause(throwable, seen);
    }
    return throwable;
  }

  private static Throwable getInitialCause(Throwable throwable, Set<Throwable> seen) {
    Throwable cause = throwable.getCause();
    if ((cause == null) || seen.contains(cause)) {
      return throwable;
    }
    seen.add(throwable);
    return getInitialCause(cause, seen);
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
}
