/*
 * Copyright 2016-present Facebook, Inc.
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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.Assert;
import org.junit.Test;

public class RichStreamTest {
  @Test
  public void emptyStream() {
    Assert.assertEquals(0, RichStream.empty().count());
  }

  @Test
  public void singletonStream() {
    Assert.assertEquals(1, RichStream.of("a").count());
    Assert.assertEquals(Optional.of("a"), RichStream.of("a").findFirst());
  }

  @Test
  public void literalStream() {
    Assert.assertEquals(2, RichStream.of("a", "b").count());
    List<String> asList = RichStream.of("a", "b").collect(Collectors.toList());
    Assert.assertEquals("a", asList.get(0));
    Assert.assertEquals("b", asList.get(1));
  }

  @Test
  public void fromOptional() {
    Assert.assertEquals(0, RichStream.from(Optional.empty()).count());
    Assert.assertEquals(1, RichStream.from(Optional.of("a")).count());
    Assert.assertEquals(Optional.of("a"), RichStream.from(Optional.of("a")).findFirst());
  }

  @Test
  public void concat() {
    List<String> result =
        RichStream.of("a", "b").concat(Stream.of("c", "d")).collect(Collectors.toList());
    Assert.assertEquals(ImmutableList.of("a", "b", "c", "d"), result);
  }

  @Test
  public void filterCast() {
    List<String> result =
        RichStream.of("a", new Object(), "b", 1).filter(String.class).collect(Collectors.toList());
    Assert.assertEquals(ImmutableList.of("a", "b"), result);
  }

  @Test
  public void immutableList() {
    Assert.assertEquals(ImmutableList.of("a", "b"), RichStream.of("a", "b").toImmutableList());
  }

  @Test
  public void immutableSet() {
    Assert.assertEquals(ImmutableSet.of("a", "b"), RichStream.of("a", "b").toImmutableSet());
  }

  @Test
  public void immutableSortedSet() {
    Assert.assertEquals(
        ImmutableSortedSet.of("b", "a"),
        RichStream.of("a", "b").toImmutableSortedSet(Comparator.reverseOrder()));
  }

  @Test
  public void wrappingRichStreamIsNoOp() {
    Stream<Object> stream = RichStream.of("a");
    Assert.assertSame(stream, RichStream.from(stream));
  }

  @Test
  public void wrappingIterable() {
    Assert.assertEquals(
        ImmutableList.of("a", "b"),
        RichStream.from(ImmutableList.of("a", "b")).collect(Collectors.toList()));
  }

  @Test
  public void wrappingIterator() {
    Assert.assertEquals(
        ImmutableList.of("a", "b"),
        RichStream.from(ImmutableList.of("a", "b").iterator()).collect(Collectors.toList()));
  }

  @Test
  public void fromSupplierOfIterable() {
    Assert.assertEquals(
        ImmutableList.of("a", "b"),
        RichStream.fromSupplierOfIterable(() -> ImmutableList.of("a", "b"))
            .collect(Collectors.toList()));
  }

  private static class MyRuntimeException extends RuntimeException {}

  @Test(expected = IOException.class)
  public void forEachOrderedThrowingCanRethrowException() throws IOException {
    RichStream.of("a")
        .forEachOrderedThrowing(
            f -> {
              throw new IOException("test io exception");
            });
  }

  @Test(expected = MyRuntimeException.class)
  public void forEachOrderedThrowingCanPassThroughRuntimeException() {
    RichStream.of("a")
        .forEachOrderedThrowing(
            f -> {
              throw new MyRuntimeException();
            });
  }

  @Test(expected = IOException.class)
  public void forEachThrowingCanRethrowException() throws IOException {
    RichStream.of("a")
        .forEachThrowing(
            f -> {
              throw new IOException("test io exception");
            });
  }

  @Test(expected = MyRuntimeException.class)
  public void forEachThrowingCanPassThroughRuntimeException() {
    RichStream.of("a")
        .forEachThrowing(
            f -> {
              throw new MyRuntimeException();
            });
  }
}
