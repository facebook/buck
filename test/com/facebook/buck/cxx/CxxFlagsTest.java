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

import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Test;

/** Tests for {@link CxxConstructorArg}. */
public class CxxFlagsTest {

  @Test
  public void getLanguageFlagsMultimapIsEmpty() {
    assertThat(
        CxxFlags.getLanguageFlags(
                ImmutableList.of(),
                PatternMatchedCollection.of(),
                ImmutableMap.of(),
                CxxPlatformUtils.DEFAULT_PLATFORM)
            .entries(),
        empty());
  }

  @Test
  public void getLanguageFlagsMultimapContainsAllSourceTypes() {
    ImmutableMultimap<CxxSource.Type, String> flags =
        CxxFlags.getLanguageFlags(
            ImmutableList.of("flag"),
            PatternMatchedCollection.of(),
            ImmutableMap.of(),
            CxxPlatformUtils.DEFAULT_PLATFORM);
    assertThat(ImmutableSet.copyOf(CxxSource.Type.values()), equalTo(flags.keySet()));
    assertThat(flags.values(), everyItem(equalTo("flag")));
  }

  @Test
  public void getLanguageFlagsMultimapContainsSomeSourceTypes() {
    ImmutableMultimap<CxxSource.Type, String> flags =
        CxxFlags.getLanguageFlags(
            ImmutableList.of(),
            PatternMatchedCollection.of(),
            ImmutableMap.of(
                CxxSource.Type.C, ImmutableList.of("foo", "bar"),
                CxxSource.Type.CXX, ImmutableList.of("baz", "blech"),
                CxxSource.Type.OBJC, ImmutableList.of("quux", "xyzzy")),
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
            ImmutableList.of("common"),
            PatternMatchedCollection.of(),
            ImmutableMap.of(
                CxxSource.Type.C, ImmutableList.of("foo", "bar"),
                CxxSource.Type.CXX, ImmutableList.of("baz", "blech"),
                CxxSource.Type.OBJC, ImmutableList.of("quux", "xyzzy")),
            CxxPlatformUtils.DEFAULT_PLATFORM);
    assertThat(ImmutableList.of("common", "foo", "bar"), equalTo(flags.get(CxxSource.Type.C)));
    assertThat(ImmutableList.of("common", "baz", "blech"), equalTo(flags.get(CxxSource.Type.CXX)));
    assertThat(
        ImmutableList.of("common", "quux", "xyzzy"), equalTo(flags.get(CxxSource.Type.OBJC)));
    assertThat(ImmutableList.of("common"), equalTo(flags.get(CxxSource.Type.OBJCXX)));
  }

  @Test
  public void getLanguageFlagsWithLangPlatformFlags() {
    ImmutableMultimap<CxxSource.Type, StringWithMacros> flags =
        CxxFlags.getLanguageFlagsWithMacros(
            ImmutableList.of(),
            PatternMatchedCollection.of(),
            ImmutableMap.of(),
            ImmutableMap.of(
                CxxSource.Type.C,
                PatternMatchedCollection.<ImmutableList<StringWithMacros>>builder()
                    .add(
                        Pattern.compile(CxxPlatformUtils.DEFAULT_PLATFORM.getFlavor().getName()),
                        ImmutableList.of(StringWithMacrosUtils.format("foo")))
                    .build()),
            CxxPlatformUtils.DEFAULT_PLATFORM);
    assertThat(flags.get(CxxSource.Type.C), Matchers.contains(StringWithMacrosUtils.format("foo")));
    assertThat(flags.get(CxxSource.Type.CXX), empty());
  }

  @Test
  public void macroExpansionsInFlags() {

    // Test that platform macros are expanded via `getFlagsWithPlatformMacroExpansion`.
    assertThat(
        CxxFlags.getFlagsWithPlatformMacroExpansion(
            ImmutableList.of("-I$MACRO"),
            new PatternMatchedCollection.Builder<ImmutableList<String>>()
                .add(Pattern.compile(".*"), ImmutableList.of("-F$MACRO"))
                .build(),
            CxxPlatformUtils.DEFAULT_PLATFORM.withFlagMacros(
                ImmutableMap.of("MACRO", "expansion"))),
        containsInAnyOrder("-Iexpansion", "-Fexpansion"));

    // Test that platform macros are expanded via `getFlagsWithPlatformMacroExpansion`.
    ImmutableList<StringWithMacros> flags =
        CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
            ImmutableList.of(StringWithMacrosUtils.format("-I$MACRO")),
            new PatternMatchedCollection.Builder<ImmutableList<StringWithMacros>>()
                .add(
                    Pattern.compile(".*"),
                    ImmutableList.of(StringWithMacrosUtils.format("-F$MACRO")))
                .build(),
            CxxPlatformUtils.DEFAULT_PLATFORM.withFlagMacros(
                ImmutableMap.of("MACRO", "expansion")));
    assertThat(
        RichStream.from(flags)
            .map(
                s ->
                    s.format(
                        m -> {
                          throw new IllegalStateException();
                        }))
            .toImmutableList(),
        containsInAnyOrder("-Iexpansion", "-Fexpansion"));

    // Test that platform macros are expanded via `getLanguageFlags`.
    assertThat(
        CxxFlags.getLanguageFlags(
                ImmutableList.of("-I$MACRO"),
                new PatternMatchedCollection.Builder<ImmutableList<String>>()
                    .add(Pattern.compile(".*"), ImmutableList.of("-F$MACRO"))
                    .build(),
                ImmutableMap.of(CxxSource.Type.C, ImmutableList.of("-isystem$MACRO")),
                CxxPlatformUtils.DEFAULT_PLATFORM.withFlagMacros(
                    ImmutableMap.of("MACRO", "expansion")))
            .get(CxxSource.Type.C),
        containsInAnyOrder("-Iexpansion", "-isystemexpansion", "-Fexpansion"));
  }

  @Test
  public void getFlagsWithMacrosWithPlatformMacroExpansion() {
    assertThat(
        CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
            ImmutableList.of(StringWithMacrosUtils.format("-I$MACRO")),
            new PatternMatchedCollection.Builder<ImmutableList<StringWithMacros>>()
                .add(
                    Pattern.compile(".*"),
                    ImmutableList.of(StringWithMacrosUtils.format("-F$MACRO")))
                .build(),
            CxxPlatformUtils.DEFAULT_PLATFORM.withFlagMacros(
                ImmutableMap.of("MACRO", "expansion"))),
        equalTo(
            ImmutableList.of(
                StringWithMacrosUtils.format("-Iexpansion"),
                StringWithMacrosUtils.format("-Fexpansion"))));
  }
}
