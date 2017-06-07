/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.args.RuleKeyAppendableFunction;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.util.RichStream;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;

public class CxxFlags {

  private CxxFlags() {}

  public static RuleKeyAppendableFunction<String, String> getTranslateMacrosFn(
      final CxxPlatform cxxPlatform) {
    final ImmutableSortedMap<String, String> flagMacros =
        ImmutableSortedMap.copyOf(cxxPlatform.getFlagMacros());
    return new RuleKeyAppendableFunction<String, String>() {

      @Override
      public void appendToRuleKey(RuleKeyObjectSink sink) {
        SortedMap<String, String> sanitizedMap =
            Maps.transformValues(
                flagMacros, cxxPlatform.getCompilerDebugPathSanitizer().sanitize(Optional.empty()));
        sink.setReflectively("flagMacros", sanitizedMap);
      }

      @Override
      public String apply(String flag) {
        // TODO(agallager): We're currently tied to `$VARIABLE` style of macros as much of the apple
        // support relies on this.  Long-term though, we should make this consistent with the
        // `$(macro ...)` style we use in the rest of the codebase.
        for (Map.Entry<String, String> entry : flagMacros.entrySet()) {
          flag = flag.replace("$" + entry.getKey(), entry.getValue());
        }
        return flag;
      }
    };
  }

  public static ImmutableList<String> getFlagsWithPlatformMacroExpansion(
      ImmutableList<String> flags,
      PatternMatchedCollection<ImmutableList<String>> platformFlags,
      CxxPlatform platform) {
    return RichStream.from(getFlags(flags, platformFlags, platform))
        .map(getTranslateMacrosFn(platform)::apply)
        .toImmutableList();
  }

  public static ImmutableList<StringWithMacros> getFlagsWithMacrosWithPlatformMacroExpansion(
      ImmutableList<StringWithMacros> flags,
      PatternMatchedCollection<ImmutableList<StringWithMacros>> platformFlags,
      CxxPlatform platform) {
    RuleKeyAppendableFunction<String, String> translateMacrosFn = getTranslateMacrosFn(platform);
    return RichStream.from(getFlags(flags, platformFlags, platform))
        .map(s -> s.mapStrings(translateMacrosFn::apply))
        .toImmutableList();
  }

  public static <T> ImmutableList<T> getFlags(
      ImmutableList<T> flags,
      PatternMatchedCollection<ImmutableList<T>> platformFlags,
      CxxPlatform platform) {
    ImmutableList.Builder<T> result = ImmutableList.builder();
    result.addAll(flags);
    for (ImmutableList<T> fl : platformFlags.getMatchingValues(platform.getFlavor().toString())) {
      result.addAll(fl);
    }
    return result.build();
  }

  private static ImmutableListMultimap<CxxSource.Type, String> toLanguageFlags(
      ImmutableList<String> flags) {

    ImmutableListMultimap.Builder<CxxSource.Type, String> result = ImmutableListMultimap.builder();

    for (CxxSource.Type type : CxxSource.Type.values()) {
      result.putAll(type, flags);
    }

    return result.build();
  }

  public static ImmutableListMultimap<CxxSource.Type, String> getLanguageFlags(
      ImmutableList<String> flags,
      PatternMatchedCollection<ImmutableList<String>> platformFlags,
      ImmutableMap<CxxSource.Type, ImmutableList<String>> languageFlags,
      CxxPlatform platform) {

    ImmutableListMultimap.Builder<CxxSource.Type, String> langFlags =
        ImmutableListMultimap.builder();

    langFlags.putAll(
        toLanguageFlags(getFlagsWithPlatformMacroExpansion(flags, platformFlags, platform)));

    for (ImmutableMap.Entry<CxxSource.Type, ImmutableList<String>> entry :
        languageFlags.entrySet()) {
      langFlags.putAll(
          entry.getKey(), Iterables.transform(entry.getValue(), getTranslateMacrosFn(platform)));
    }

    return langFlags.build();
  }
}
