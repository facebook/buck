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

package com.facebook.buck.java.abi;

import java.util.Iterator;

/**
 * Modeled as a drop-in replacement for guava's {@link com.google.common.base.Joiner}, but with no
 * third party deps.
 */
class Joiner {
  private String separator;

  private Joiner(String separator) {
    if (separator == null) {
      throw new NullPointerException("Joiner separator must not be null");
    }
    this.separator = separator;
  }

  public static Joiner on(String separator) {
    return new Joiner(separator);
  }

  public void appendTo(StringBuilder builder, Iterable<?> toJoin) {
    Iterator<?> iterator = toJoin.iterator();
    if (!iterator.hasNext()) {
      return;
    }

    builder.append(String.valueOf(iterator.next()));
    while (iterator.hasNext()) {
      builder.append(separator).append(iterator.next());
    }
  }

  public String join(Iterable<?> toJoin) {
    StringBuilder builder = new StringBuilder();
    appendTo(builder, toJoin);
    return builder.toString();
  }
}
