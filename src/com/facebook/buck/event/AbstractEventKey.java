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

package com.facebook.buck.event;

import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;

import org.immutables.value.Value;

import java.util.concurrent.atomic.AtomicLong;

@Value.Immutable
@BuckStyleImmutable
/**
 * Used to associate sets of {@link BuckEvent}s with each other. The keys are only considered unique
 * in the scope of the same type of {@link BuckEvent}.
 */
abstract class AbstractEventKey {

  private static final AtomicLong SEQUENCE_NUMBER = new AtomicLong(1);

  @Value.Parameter
  abstract long getValue();

  @VisibleForTesting
  public static void setSequenceValueForTest(long value) {
    // Minus one because we increment before we get.
    SEQUENCE_NUMBER.set(value - 1);
  }

  /**
   * @return an EventKey unique to the current process.
   */
  public static EventKey of() {
    return EventKey.of(SEQUENCE_NUMBER.incrementAndGet());
  }

  /**
   * Prefer calling chain(started) in the @{link BuckEvent} over the use of this method.
   *
   * This variant of the method creates a key based on the set of supplied inputs. This is useful
   * in situations where a key with the same identity must be created multiple times and the
   * corresponding start event is not available.
   *
   * @param objects objects which supply the identity of the key. {@link Object#toString()} will be
   *                called on every instance and the result will be used to calculate the key.
   * @return EventKey with the identity of the supplied objects.
   */
  public static EventKey slowValueKey(Object... objects) {
    return EventKey.of(Objects.hashCode(objects));
  }
}
