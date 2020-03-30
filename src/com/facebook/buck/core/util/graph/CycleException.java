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

package com.facebook.buck.core.util.graph;

import static com.facebook.buck.util.string.MoreStrings.linesToText;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;

/** Exception thrown when graph traveral finds a cycle */
public final class CycleException extends Exception {

  private final ImmutableList<?> nodes;

  CycleException(Iterable<?> nodes) {
    super(
        linesToText(
            "Buck can't handle circular dependencies.",
            "The following circular dependency has been found:",
            Joiner.on(" -> ").join(nodes),
            "",
            "Please break the circular dependency and try again."));
    this.nodes = ImmutableList.copyOf(nodes);
  }

  public ImmutableList<?> getCycle() {
    return nodes;
  }
}
