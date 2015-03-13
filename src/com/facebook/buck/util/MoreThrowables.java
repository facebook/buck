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

import com.google.common.collect.ImmutableSet;

import java.io.InterruptedIOException;
import java.nio.channels.ClosedByInterruptException;

public class MoreThrowables {

  private static final ImmutableSet<Class<? extends Throwable>> INTERRUPTED_EXCEPTIONS =
      ImmutableSet.<Class<? extends Throwable>>of(
          ClosedByInterruptException.class,
          InterruptedIOException.class);

  private MoreThrowables() {}

  /**
   * Propagates an {@link InterruptedException} masquerading as another {@code Throwable}.
   */
  public static void propagateIfInterrupt(Throwable thrown) throws InterruptedException {

    // If it's already an `InterruptedException`, just rethrow it.
    if (thrown instanceof InterruptedException) {
      throw (InterruptedException) thrown;
    }

    // I/O operations will throw these types of `IOException` when interrupted, so
    // propagate these along as an `InterruptedException`, so we handle this as expected.
    for (Class<? extends Throwable> clazz : INTERRUPTED_EXCEPTIONS) {
      if (clazz.isInstance(thrown)) {
        InterruptedException e = new InterruptedException();
        e.initCause(thrown);
        throw e;
      }
    }
  }

}
