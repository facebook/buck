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

import com.google.common.base.Function;

public abstract class MoreFunctions {

  /**
   * @param arg argument to apply.
   * @param <A> argument type.
   * @param <V> result type.
   * @return a function that applies arg to a function it receives as input.
   */
  public static final <A, V> Function<Function<A, V>, V> applyFunction(final A arg) {
    return new Function<Function<A, V>, V>() {
      @Override
      public V apply(Function<A, V> input) {
        return input.apply(arg);
      }
    };
  }
}
