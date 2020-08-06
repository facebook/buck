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

package com.facebook.buck.cxx;

import com.facebook.buck.core.rulekey.CustomFieldBehavior;
import com.facebook.buck.core.rulekey.DefaultFieldSerialization;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.rules.args.AddsToRuleKeyFunction;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.CompositeArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Iterables;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CxxFlags {

  private CxxFlags() {}

  public static ImmutableList<String> getFlagsWithPlatformMacroExpansion(
      ImmutableList<String> flags,
      PatternMatchedCollection<ImmutableList<String>> platformFlags,
      CxxPlatform platform) {
    return RichStream.from(getFlags(flags, platformFlags, platform))
        .map(new TranslateMacrosFunction(ImmutableSortedMap.copyOf(platform.getFlagMacros())))
        .toImmutableList();
  }

  public static ImmutableList<StringWithMacros> getFlagsWithMacrosWithPlatformMacroExpansion(
      ImmutableList<StringWithMacros> flags,
      PatternMatchedCollection<ImmutableList<StringWithMacros>> platformFlags,
      CxxPlatform platform) {
    AddsToRuleKeyFunction<String, String> translateMacrosFn =
        new TranslateMacrosFunction(ImmutableSortedMap.copyOf(platform.getFlagMacros()));
    return RichStream.from(getFlags(flags, platformFlags, platform))
        .map(s -> s.mapStrings(translateMacrosFn))
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

  static <T> ImmutableListMultimap<CxxSource.Type, T> toLanguageFlags(Iterable<T> flags) {

    ImmutableListMultimap.Builder<CxxSource.Type, T> result = ImmutableListMultimap.builder();

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
          entry.getKey(),
          Iterables.transform(
              entry.getValue(),
              (new TranslateMacrosFunction(ImmutableSortedMap.copyOf(platform.getFlagMacros())))
                  ::apply));
    }

    return langFlags.build();
  }

  public static ImmutableListMultimap<CxxSource.Type, StringWithMacros> getLanguageFlagsWithMacros(
      ImmutableList<StringWithMacros> flags,
      PatternMatchedCollection<ImmutableList<StringWithMacros>> platformFlags,
      ImmutableMap<CxxSource.Type, ? extends Collection<StringWithMacros>> languageFlags,
      ImmutableMap<CxxSource.Type, PatternMatchedCollection<ImmutableList<StringWithMacros>>>
          languagePlatformFlags,
      CxxPlatform platform) {

    ImmutableListMultimap.Builder<CxxSource.Type, StringWithMacros> langFlags =
        ImmutableListMultimap.builder();

    langFlags.putAll(
        toLanguageFlags(
            getFlagsWithMacrosWithPlatformMacroExpansion(flags, platformFlags, platform)));

    for (ImmutableMap.Entry<CxxSource.Type, ? extends Collection<StringWithMacros>> entry :
        languageFlags.entrySet()) {
      AddsToRuleKeyFunction<String, String> translateMacrosFn =
          new TranslateMacrosFunction(ImmutableSortedMap.copyOf(platform.getFlagMacros()));
      langFlags.putAll(
          entry.getKey(),
          entry.getValue().stream().map(s -> s.mapStrings(translateMacrosFn))::iterator);
    }

    for (ImmutableMap.Entry<
            CxxSource.Type, PatternMatchedCollection<ImmutableList<StringWithMacros>>>
        entry : languagePlatformFlags.entrySet()) {
      langFlags.putAll(entry.getKey(), getFlags(ImmutableList.of(), entry.getValue(), platform));
    }

    return langFlags.build();
  }

  /** Function for translating cxx flag macros. */
  public static class TranslateMacrosFunction implements AddsToRuleKeyFunction<String, String> {

    @CustomFieldBehavior(DefaultFieldSerialization.class)
    private final ImmutableSortedMap<String, String> flagMacros;

    public TranslateMacrosFunction(ImmutableSortedMap<String, String> flagMacros) {
      this.flagMacros = flagMacros;
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
  }

  /** Function for translating cxx flag macros in list of Arg. */
  public static class TranslateMacrosArgsFunction implements AddsToRuleKeyFunction<Arg, Arg> {

    @CustomFieldBehavior(DefaultFieldSerialization.class)
    private final ImmutableSortedMap<String, Arg> flagMacros;

    private final Pattern macrosPattern;

    public TranslateMacrosArgsFunction(ImmutableSortedMap<String, Arg> flagMacros) {
      this.flagMacros = flagMacros;
      this.macrosPattern =
          Pattern.compile(
              String.join(
                  "|",
                  flagMacros.keySet().stream()
                      .map(macro -> "\\$" + macro)
                      .collect(ImmutableList.toImmutableList())));
    }

    @Override
    public Arg apply(Arg flag) {
      if (!(flag instanceof StringArg)) {
        return flag;
      }
      String argValue = ((StringArg) flag).getArg();
      Matcher matcher = macrosPattern.matcher(argValue);
      int startPos = 0;
      ImmutableList.Builder<Arg> argBuilder = new ImmutableList.Builder<>();
      while (matcher.find()) {
        if (matcher.start() > startPos) {
          argBuilder.add(StringArg.of(argValue.substring(startPos, matcher.start())));
        }
        // Get macro value. Take macro name without $ sign.
        argBuilder.add(flagMacros.get(argValue.substring(matcher.start() + 1, matcher.end())));
        startPos = matcher.end();
      }
      if (startPos < argValue.length()) {
        argBuilder.add(StringArg.of(argValue.substring(startPos)));
      }
      ImmutableList<Arg> compositeArgParts = argBuilder.build();
      if (compositeArgParts.size() == 1) {
        return compositeArgParts.get(0);
      }
      return CompositeArg.of(compositeArgParts);
    }
  }

  /** Expand flag macros in all CxxPlatform StringArg flags. */
  public static void translateCxxPlatformFlags(
      CxxPlatform.Builder cxxPlatformBuilder,
      CxxPlatform cxxPlatform,
      ImmutableMap<String, Arg> flagMacros) {
    Function<Arg, Arg> translateFunction =
        new CxxFlags.TranslateMacrosArgsFunction(ImmutableSortedMap.copyOf(flagMacros));
    Function<ImmutableList<Arg>, ImmutableList<Arg>> expandMacros =
        flags -> flags.stream().map(translateFunction).collect(ImmutableList.toImmutableList());
    cxxPlatformBuilder.setAsflags(expandMacros.apply(cxxPlatform.getAsflags()));
    cxxPlatformBuilder.setAsppflags(expandMacros.apply(cxxPlatform.getAsppflags()));
    cxxPlatformBuilder.setCflags(expandMacros.apply(cxxPlatform.getCflags()));
    cxxPlatformBuilder.setCxxflags(expandMacros.apply(cxxPlatform.getCxxflags()));
    cxxPlatformBuilder.setCppflags(expandMacros.apply(cxxPlatform.getCppflags()));
    cxxPlatformBuilder.setCxxppflags(expandMacros.apply(cxxPlatform.getCxxppflags()));
    cxxPlatformBuilder.setCudappflags(expandMacros.apply(cxxPlatform.getCudappflags()));
    cxxPlatformBuilder.setCudaflags(expandMacros.apply(cxxPlatform.getCudaflags()));
    cxxPlatformBuilder.setHipppflags(expandMacros.apply(cxxPlatform.getHipppflags()));
    cxxPlatformBuilder.setHipflags(expandMacros.apply(cxxPlatform.getHipflags()));
    cxxPlatformBuilder.setAsmppflags(expandMacros.apply(cxxPlatform.getAsmppflags()));
    cxxPlatformBuilder.setAsmflags(expandMacros.apply(cxxPlatform.getAsmflags()));
    cxxPlatformBuilder.setLdflags(expandMacros.apply(cxxPlatform.getLdflags()));
    cxxPlatformBuilder.setStripFlags(expandMacros.apply(cxxPlatform.getStripFlags()));
    cxxPlatformBuilder.setArflags(expandMacros.apply(cxxPlatform.getArflags()));
    cxxPlatformBuilder.setRanlibflags(expandMacros.apply(cxxPlatform.getRanlibflags()));
  }
}
