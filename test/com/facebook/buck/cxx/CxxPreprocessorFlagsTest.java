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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.equalTo;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

/**
 * Tests for {@link CxxPreprocessorFlags}.
 */
public class CxxPreprocessorFlagsTest {
  @Test
  public void fromNotPresentToMultimapIsEmpty() {
    assertThat(
        CxxPreprocessorFlags.fromArgs(
            Optional.<ImmutableList<String>>absent(),
            Optional.<ImmutableMap<CxxSource.Type, ImmutableList<String>>>absent()).entries(),
        empty());
  }

  @Test
  public void fromListToMultimapContainsAllSourceTypes() {
    ImmutableMultimap<CxxSource.Type, String> flags =
        CxxPreprocessorFlags.fromArgs(
            Optional.of(ImmutableList.of("flag")),
            Optional.<ImmutableMap<CxxSource.Type, ImmutableList<String>>>absent());
    assertThat(
        ImmutableSet.copyOf(CxxSource.Type.values()),
        equalTo(flags.keySet()));
    assertThat(flags.values(), everyItem(equalTo("flag")));
  }

  @Test
  public void fromMapToMultimapContainsSomeSourceTypes() {
    ImmutableMultimap<CxxSource.Type, String> flags =
        CxxPreprocessorFlags.fromArgs(
            Optional.<ImmutableList<String>>absent(),
            Optional.of(ImmutableMap.of(
                CxxSource.Type.C, ImmutableList.of("foo", "bar"),
                CxxSource.Type.CXX, ImmutableList.of("baz", "blech"),
                CxxSource.Type.OBJC, ImmutableList.of("quux", "xyzzy"))));
    assertThat(
        ImmutableSet.of(CxxSource.Type.C, CxxSource.Type.CXX, CxxSource.Type.OBJC),
        equalTo(flags.keySet()));
    assertThat(ImmutableList.of("foo", "bar"), equalTo(flags.get(CxxSource.Type.C)));
    assertThat(ImmutableList.of("baz", "blech"), equalTo(flags.get(CxxSource.Type.CXX)));
    assertThat(ImmutableList.of("quux", "xyzzy"), equalTo(flags.get(CxxSource.Type.OBJC)));
    assertThat(flags.get(CxxSource.Type.OBJCXX), empty());
  }

  @Test
  public void fromBothToMultimapContainsConcatenatedFlags() {
    ImmutableMultimap<CxxSource.Type, String> flags =
        CxxPreprocessorFlags.fromArgs(
            Optional.of(ImmutableList.<String>of("common")),
            Optional.of(ImmutableMap.of(
                CxxSource.Type.C, ImmutableList.of("foo", "bar"),
                CxxSource.Type.CXX, ImmutableList.of("baz", "blech"),
                CxxSource.Type.OBJC, ImmutableList.of("quux", "xyzzy"))));
    assertThat(
        ImmutableList.of("common", "foo", "bar"), equalTo(flags.get(CxxSource.Type.C)));
    assertThat(
        ImmutableList.of("common", "baz", "blech"), equalTo(flags.get(CxxSource.Type.CXX)));
    assertThat(
        ImmutableList.of("common", "quux", "xyzzy"), equalTo(flags.get(CxxSource.Type.OBJC)));
    assertThat(
        ImmutableList.of("common"), equalTo(flags.get(CxxSource.Type.OBJCXX)));
  }
}
