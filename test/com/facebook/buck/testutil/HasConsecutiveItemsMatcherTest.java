/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.testutil;

import static com.facebook.buck.testutil.HasConsecutiveItemsMatcher.hasConsecutiveItems;
import static org.hamcrest.core.IsNot.not;
import static org.junit.Assert.assertThat;

import com.google.common.collect.ImmutableList;

import org.junit.Test;

/**
 * Unit tests for {@link HasConsecutiveItemsMatcher}.
 */
public class HasConsecutiveItemsMatcherTest {

  @Test
  public void emptyIncludesEmpty() {
    assertThat(
        ImmutableList.of(),
        hasConsecutiveItems());
  }

  @Test
  public void nonEmptyIncludesEmpty() {
    assertThat(
        ImmutableList.of("a", "b", "c"),
        hasConsecutiveItems());
  }

  @Test
  public void includesSingleItem() {
    assertThat(
        ImmutableList.of("a", "b", "c"),
        hasConsecutiveItems("a"));
    assertThat(
        ImmutableList.of("a", "b", "c"),
        hasConsecutiveItems("b"));
    assertThat(
        ImmutableList.of("a", "b", "c"),
        hasConsecutiveItems("c"));
  }

  @Test
  public void includesSublist() {
    assertThat(
        ImmutableList.of("a", "b", "c"),
        hasConsecutiveItems("b", "c"));
    assertThat(
        ImmutableList.of("a", "b", "c"),
        hasConsecutiveItems("a", "b"));
  }

  @Test
  public void includesEntireList() {
    assertThat(
        ImmutableList.of("a", "b", "c"),
        hasConsecutiveItems("a", "b", "c"));
  }

  @Test
  public void doesNotIncludeExtraItem() {
    assertThat(
        ImmutableList.of("a", "b", "fnord", "c"),
        not(hasConsecutiveItems("a", "b", "c")));
  }

  @Test
  public void doesNotIncludeMissingItem() {
    assertThat(
        ImmutableList.of("a", "b", "c"),
        not(hasConsecutiveItems("d")));
  }

  @Test
  public void doesNotIncludeListTooLong() {
    assertThat(
        ImmutableList.of("a", "b", "c"),
        not(hasConsecutiveItems("a", "b", "c", "d")));
  }

  @Test
  public void includesCollection() {
    assertThat(
        ImmutableList.of("a", "b", "c"),
        hasConsecutiveItems(ImmutableList.of("b", "c")));
  }

  @Test
  public void doesNotIncludeCollectionWithMissingItem() {
    assertThat(
        ImmutableList.of("a", "b", "c"),
        not(hasConsecutiveItems(ImmutableList.of("d"))));
  }
}
