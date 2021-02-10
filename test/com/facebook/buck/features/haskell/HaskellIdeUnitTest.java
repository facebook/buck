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

package com.facebook.buck.features.haskell;

import static org.hamcrest.MatcherAssert.assertThat;

import com.google.common.collect.ImmutableList;
import org.hamcrest.Matchers;
import org.junit.Test;

public class HaskellIdeUnitTest {

  @Test
  public void deduplicateEmptyFlags() {
    assertThat(
        HaskellIdeDescription.deduplicateFlags(ImmutableList.of()),
        Matchers.equalTo(ImmutableList.of()));
  }

  @Test
  public void deduplicateFlagsSingle() {
    assertThat(
        HaskellIdeDescription.deduplicateFlags(
            ImmutableList.of("-dup", "-bar", "-dup", "-dup", "-foo")),
        Matchers.equalTo(ImmutableList.of("-dup", "-bar", "-foo")));
  }

  @Test
  public void deduplicateFlagsMulti() {
    assertThat(
        HaskellIdeDescription.deduplicateFlags(ImmutableList.of("-f", "a", "-f", "b", "-f", "b")),
        Matchers.equalTo(ImmutableList.of("-f", "a", "-f", "b")));
  }

  @Test
  public void deduplicateFlagsMixed() {
    assertThat(
        HaskellIdeDescription.deduplicateFlags(
            ImmutableList.of("-dup", "-f", "a", "-f", "b", "-foo", "-f", "b", "-dup")),
        Matchers.equalTo(ImmutableList.of("-dup", "-foo", "-f", "a", "-f", "b")));
  }
}
