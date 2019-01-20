/*
 * Copyright 2012-present Facebook, Inc.
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

import java.util.concurrent.atomic.AtomicLong;

/** Utility methods for common routines with atomic variables */
public final class MoreAtomics {

  private MoreAtomics() {}

  /**
   * Update atomic variable if provided value is greater than value stored in atomic variable.
   * Implementation is lock-free.
   *
   * @param value A value to check against atomic value
   * @param atomicValue Atomic variable that will keep a maximum of two values
   * @return New value of {@code atomicValue}
   */
  public static long setMaxAndGet(long value, AtomicLong atomicValue) {
    return atomicValue.updateAndGet(cur -> Math.max(cur, value));
  }

  /**
   * Update atomic variable if provided value is less than value stored in atomic variable.
   * Implementation is lock-free.
   *
   * @param value A value to check against atomic value
   * @param atomicValue Atomic variable that will keep a minimum of two values
   * @return New value of {@code atomicValue}
   */
  public static long setMinAndGet(long value, AtomicLong atomicValue) {
    return atomicValue.updateAndGet(cur -> Math.min(cur, value));
  }
}
