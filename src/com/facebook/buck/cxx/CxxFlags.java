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

import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Iterables;

import java.util.Map;

public class CxxFlags {

  private CxxFlags() {}

  public static Function<String, String> getTranslateMacrosFn(final CxxPlatform cxxPlatform) {
    return new Function<String, String>() {
      @Override
      public String apply(String flag) {
        // TODO(agallager): We're currently tied to `$VARIABLE` style of macros as much of the apple
        // support relies on this.  Long-term though, we should make this consistent with the
        // `$(macro ...)` style we use in the rest of the codebase.
        for (Map.Entry<String, String> entry : cxxPlatform.getFlagMacros().entrySet()) {
          flag = flag.replace("$" + entry.getKey(), entry.getValue());
        }
        return flag;
      }
    };
  }

  public static ImmutableList<String> getFlags(
      Optional<ImmutableList<String>> flags,
      Optional<PatternMatchedCollection<ImmutableList<String>>> platformFlags,
      CxxPlatform platform) {
    return FluentIterable
        .from(flags.or(ImmutableList.<String>of()))
        .append(
            Iterables.concat(
                platformFlags
                    .or(PatternMatchedCollection.<ImmutableList<String>>of())
                    .getMatchingValues(platform.getFlavor().toString())))
        .transform(getTranslateMacrosFn(platform))
        .toList();
  }

  private static ImmutableMultimap<CxxSource.Type, String> toLanguageFlags(
      ImmutableList<String> flags) {

    ImmutableMultimap.Builder<CxxSource.Type, String> result = ImmutableMultimap.builder();

    for (CxxSource.Type type : CxxSource.Type.values()) {
      result.putAll(type, flags);
    }

    return result.build();
  }

  public static ImmutableMultimap<CxxSource.Type, String> getLanguageFlags(
      Optional<ImmutableList<String>> flags,
      Optional<PatternMatchedCollection<ImmutableList<String>>> platformFlags,
      Optional<ImmutableMap<CxxSource.Type, ImmutableList<String>>> languageFlags,
      CxxPlatform platform) {

    ImmutableMultimap.Builder<CxxSource.Type, String> langFlags = ImmutableMultimap.builder();

    langFlags.putAll(toLanguageFlags(getFlags(flags, platformFlags, platform)));

    for (ImmutableMap.Entry<CxxSource.Type, ImmutableList<String>> entry :
         languageFlags.or(ImmutableMap.<CxxSource.Type, ImmutableList<String>>of()).entrySet()) {
      langFlags.putAll(
          entry.getKey(),
          Iterables.transform(entry.getValue(), getTranslateMacrosFn(platform)));
    }

    return langFlags.build();
  }

}
