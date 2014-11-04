/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.rules.coercer;

import java.util.Objects;

/**
 * Simple type representing a pair of differently typed values.
 *
 * Used to represent pair-like structures in coerced values.
 */
public class Pair<FIRST, SECOND> {
  private FIRST first;
  private SECOND second;

  public Pair(FIRST first, SECOND second) {
    this.first = first;
    this.second = second;
  }

  public FIRST getFirst() {
    return first;
  }

  public SECOND getSecond() {
    return second;
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof Pair)) {
      return false;
    }

    Pair<?, ?> that = (Pair<?, ?>) other;
    return Objects.equals(this.first, that.first) &&
        Objects.equals(this.second, that.second);
  }

  @Override
  public int hashCode() {
    return Objects.hash(first, second);
  }
}
