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

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.junit.Assert.assertThat;

import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.util.regex.Pattern;

/**
 * Tests for {@link CxxConstructorArg}.
 */
public class CxxFlagsTest {

  @Test
  public void getLanguageFlagsMultimapIsEmpty() {
    assertThat(
        CxxFlags.getLanguageFlags(
            Optional.<ImmutableList<String>>absent(),
            Optional.<PatternMatchedCollection<ImmutableList<String>>>absent(),
            Optional.<ImmutableMap<CxxSource.Type, ImmutableList<String>>>absent(),
            CxxPlatformUtils.DEFAULT_PLATFORM).entries(),
        empty());
  }

  @Test
  public void getLanguageFlagsMultimapContainsAllSourceTypes() {
    ImmutableMultimap<CxxSource.Type, String> flags =
        CxxFlags.getLanguageFlags(
            Optional.of(ImmutableList.of("flag")),
            Optional.<PatternMatchedCollection<ImmutableList<String>>>absent(),
            Optional.<ImmutableMap<CxxSource.Type, ImmutableList<String>>>absent(),
            CxxPlatformUtils.DEFAULT_PLATFORM);
    assertThat(
        ImmutableSet.copyOf(CxxSource.Type.values()),
        equalTo(flags.keySet()));
    assertThat(flags.values(), everyItem(equalTo("flag")));
  }

  @Test
  public void getLanguageFlagsMultimapContainsSomeSourceTypes() {
    ImmutableMultimap<CxxSource.Type, String> flags =
        CxxFlags.getLanguageFlags(
            Optional.<ImmutableList<String>>absent(),
            Optional.<PatternMatchedCollection<ImmutableList<String>>>absent(),
            Optional.of(
                ImmutableMap.of(
                    CxxSource.Type.C, ImmutableList.of("foo", "bar"),
                    CxxSource.Type.CXX, ImmutableList.of("baz", "blech"),
                    CxxSource.Type.OBJC, ImmutableList.of("quux", "xyzzy"))),
            CxxPlatformUtils.DEFAULT_PLATFORM);
    assertThat(
        ImmutableSet.of(CxxSource.Type.C, CxxSource.Type.CXX, CxxSource.Type.OBJC),
        equalTo(flags.keySet()));
    assertThat(ImmutableList.of("foo", "bar"), equalTo(flags.get(CxxSource.Type.C)));
    assertThat(ImmutableList.of("baz", "blech"), equalTo(flags.get(CxxSource.Type.CXX)));
    assertThat(ImmutableList.of("quux", "xyzzy"), equalTo(flags.get(CxxSource.Type.OBJC)));
    assertThat(flags.get(CxxSource.Type.OBJCXX), empty());
  }

  @Test
  public void getLanguageFlagsMultimapContainsConcatenatedFlags() {
    ImmutableMultimap<CxxSource.Type, String> flags =
        CxxFlags.getLanguageFlags(
            Optional.of(ImmutableList.of("common")),
            Optional.<PatternMatchedCollection<ImmutableList<String>>>absent(),
            Optional.of(
                ImmutableMap.of(
                    CxxSource.Type.C, ImmutableList.of("foo", "bar"),
                    CxxSource.Type.CXX, ImmutableList.of("baz", "blech"),
                    CxxSource.Type.OBJC, ImmutableList.of("quux", "xyzzy"))),
            CxxPlatformUtils.DEFAULT_PLATFORM);
    assertThat(
        ImmutableList.of("common", "foo", "bar"), equalTo(flags.get(CxxSource.Type.C)));
    assertThat(
        ImmutableList.of("common", "baz", "blech"), equalTo(flags.get(CxxSource.Type.CXX)));
    assertThat(
        ImmutableList.of("common", "quux", "xyzzy"), equalTo(flags.get(CxxSource.Type.OBJC)));
    assertThat(
        ImmutableList.of("common"), equalTo(flags.get(CxxSource.Type.OBJCXX)));
  }

  @Test
  public void macroExpansionsInFlags() {

    // Test that platform macros are expanded via `getFlags`.
    assertThat(
        CxxFlags.getFlags(
            Optional.of(ImmutableList.of("-I$MACRO")),
            Optional.of(
                new PatternMatchedCollection.Builder<ImmutableList<String>>()
                    .add(Pattern.compile(".*"), ImmutableList.of("-F$MACRO"))
                    .build()),
            CxxPlatformUtils.DEFAULT_PLATFORM
                .withFlagMacros(ImmutableMap.of("MACRO", "expansion"))),
        containsInAnyOrder(
            "-Iexpansion",
            "-Fexpansion"));

    // Test that platform macros are expanded via `getLanguageFlags`.
    assertThat(
        CxxFlags.getLanguageFlags(
            Optional.of(ImmutableList.of("-I$MACRO")),
            Optional.of(
                new PatternMatchedCollection.Builder<ImmutableList<String>>()
                    .add(Pattern.compile(".*"), ImmutableList.of("-F$MACRO"))
                    .build()),
            Optional.of(
                ImmutableMap.of(
                    CxxSource.Type.C, ImmutableList.of("-isystem$MACRO"))),
            CxxPlatformUtils.DEFAULT_PLATFORM
                .withFlagMacros(ImmutableMap.of("MACRO", "expansion")))
            .get(CxxSource.Type.C),
        containsInAnyOrder(
            "-Iexpansion",
            "-isystemexpansion",
            "-Fexpansion"));
  }

}
