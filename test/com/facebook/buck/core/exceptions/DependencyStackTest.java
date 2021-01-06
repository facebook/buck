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

package com.facebook.buck.core.exceptions;

import com.google.common.collect.ImmutableList;
import org.junit.Assert;
import org.junit.Test;

public class DependencyStackTest {
  private enum ElementImpl implements DependencyStack.Element {
    A,
    B,
    C,
    D,
  }

  @Test
  public void collect() {
    Assert.assertEquals(ImmutableList.of(), DependencyStack.root().collect());
    Assert.assertEquals(
        ImmutableList.of(ElementImpl.A), DependencyStack.top(ElementImpl.A).collect());
    Assert.assertEquals(
        ImmutableList.of(ElementImpl.A), DependencyStack.root().child(ElementImpl.A).collect());
    Assert.assertEquals(
        ImmutableList.of(ElementImpl.A), DependencyStack.root().child(ElementImpl.A).collect());
    Assert.assertEquals(
        ImmutableList.of(ElementImpl.B, ElementImpl.A),
        DependencyStack.root().child(ElementImpl.A).child(ElementImpl.B).collect());
  }

  @Test
  public void collectStringsFilterAdjacentDupes() {
    Assert.assertEquals(
        ImmutableList.of(), DependencyStack.root().collectStringsFilterAdjacentDupes());
    Assert.assertEquals(
        ImmutableList.of("A"),
        DependencyStack.top(ElementImpl.A).collectStringsFilterAdjacentDupes());
    Assert.assertEquals(
        ImmutableList.of("B", "A"),
        DependencyStack.top(ElementImpl.A)
            .child(ElementImpl.B)
            .collectStringsFilterAdjacentDupes());
    Assert.assertEquals(
        ImmutableList.of("A"),
        DependencyStack.top(ElementImpl.A)
            .child(ElementImpl.A)
            .collectStringsFilterAdjacentDupes());
    Assert.assertEquals(
        ImmutableList.of("B", "A"),
        DependencyStack.top(ElementImpl.A)
            .child(ElementImpl.A)
            .child(ElementImpl.B)
            .collectStringsFilterAdjacentDupes());
    Assert.assertEquals(
        ImmutableList.of("B", "A"),
        DependencyStack.top(ElementImpl.A)
            .child(ElementImpl.B)
            .child(ElementImpl.B)
            .collectStringsFilterAdjacentDupes());
  }
}
