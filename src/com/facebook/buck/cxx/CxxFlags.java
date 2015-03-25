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

import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Pair;
import com.google.common.base.Optional;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CxxFlags {

  private static final LoadingCache<String, Pattern> patternCache =
      CacheBuilder.newBuilder()
      .build(
          new CacheLoader<String, Pattern>() {
            @Override
            public Pattern load(String regex) throws Exception {
              return Pattern.compile(regex);
            }
          });

  private CxxFlags() {}

  public static ImmutableList<String> getFlags(
      Optional<ImmutableList<String>> flags,
      Optional<ImmutableList<Pair<String, ImmutableList<String>>>> platformFlags,
      Flavor platform) {
    ImmutableList.Builder<String> flagsBuilder = ImmutableList.builder();

    flagsBuilder.addAll(flags.or(ImmutableList.<String>of()));

    for (Pair<String, ImmutableList<String>> pair :
        platformFlags.or(ImmutableList.<Pair<String, ImmutableList<String>>>of())) {
      Pattern pattern = patternCache.getUnchecked(pair.getFirst());
      Matcher matcher = pattern.matcher(platform.toString());
      if (matcher.find()) {
        flagsBuilder.addAll(pair.getSecond());
        break;
      }
    }

    return flagsBuilder.build();
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
      Optional<ImmutableList<Pair<String, ImmutableList<String>>>> platformFlags,
      Optional<ImmutableMap<CxxSource.Type, ImmutableList<String>>> languageFlags,
      Flavor platform) {

    ImmutableMultimap.Builder<CxxSource.Type, String> langFlags = ImmutableMultimap.builder();

    langFlags.putAll(toLanguageFlags(getFlags(flags, platformFlags, platform)));

    for (ImmutableMap.Entry<CxxSource.Type, ImmutableList<String>> entry :
         languageFlags.or(ImmutableMap.<CxxSource.Type, ImmutableList<String>>of()).entrySet()) {
      langFlags.putAll(entry.getKey(), entry.getValue());
    }

    return langFlags.build();
  }

}
