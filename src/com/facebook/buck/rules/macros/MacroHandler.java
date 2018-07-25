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

package com.facebook.buck.rules.macros;

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.macros.MacroException;
import com.facebook.buck.core.macros.MacroFinder;
import com.facebook.buck.core.macros.MacroMatchResult;
import com.facebook.buck.core.macros.MacroReplacer;
import com.facebook.buck.core.macros.StringMacroCombiner;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.common.BuildableSupport;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Extracts macros from input strings and calls registered expanders to handle their input.
 *
 * <p>Deprecated: Use {@link StringWithMacros} in constructor args and {@link
 * StringWithMacrosConverter} instead.
 */
@Deprecated
public class MacroHandler {

  private final ImmutableMap<String, MacroExpander> expanders;

  public MacroHandler(ImmutableMap<String, ? extends MacroExpander> expanders) {
    this.expanders = addOutputToFileExpanders(expanders);
  }

  public Function<String, String> getExpander(
      BuildTarget target, CellPathResolver cellNames, ActionGraphBuilder graphBuilder) {
    return blob -> {
      try {
        return expand(target, cellNames, graphBuilder, blob);
      } catch (MacroException e) {
        throw new HumanReadableException("%s: %s", target, e.getMessage());
      }
    };
  }

  private static ImmutableMap<String, MacroExpander> addOutputToFileExpanders(
      ImmutableMap<String, ? extends MacroExpander> source) {
    ImmutableMap.Builder<String, MacroExpander> builder = ImmutableMap.builder();
    for (Map.Entry<String, ? extends MacroExpander> entry : source.entrySet()) {
      builder.put(entry.getKey(), entry.getValue());
      builder.put("@" + entry.getKey(), entry.getValue());
    }
    return builder.build();
  }

  public MacroExpander getExpander(String name) throws MacroException {
    MacroExpander expander = expanders.get(name);
    if (expander == null) {
      throw new MacroException(String.format("no such macro \"%s\"", name));
    }
    return expander;
  }

  public String expand(
      BuildTarget target, CellPathResolver cellNames, ActionGraphBuilder graphBuilder, String blob)
      throws MacroException {
    return expand(target, cellNames, graphBuilder, blob, new HashMap<>());
  }

  public String expand(
      BuildTarget target,
      CellPathResolver cellNames,
      ActionGraphBuilder graphBuilder,
      String blob,
      Map<MacroMatchResult, Object> precomputedWorkCache)
      throws MacroException {
    ImmutableMap<String, MacroReplacer<String>> replacers =
        getMacroReplacers(target, cellNames, graphBuilder, precomputedWorkCache);
    return MacroFinder.replace(replacers, blob, true, new StringMacroCombiner());
  }

  private ImmutableMap<String, MacroReplacer<String>> getMacroReplacers(
      BuildTarget target,
      CellPathResolver cellNames,
      ActionGraphBuilder graphBuilder,
      Map<MacroMatchResult, Object> precomputedWorkCache) {
    ImmutableMap.Builder<String, MacroReplacer<String>> replacers = ImmutableMap.builder();
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(graphBuilder));
    for (Map.Entry<String, MacroExpander> entry : expanders.entrySet()) {
      MacroReplacer<String> replacer;
      boolean shouldOutputToFile = entry.getKey().startsWith("@");
      try {
        MacroExpander expander = getExpander(entry.getKey());
        replacer =
            input -> {
              Object precomputedWork =
                  ensurePrecomputedWork(
                      input, expander, precomputedWorkCache, target, cellNames, graphBuilder);
              if (shouldOutputToFile) {
                return Arg.stringify(
                    expander.expandForFile(
                        target, cellNames, graphBuilder, input.getMacroInput(), precomputedWork),
                    pathResolver);
              } else {
                return Arg.stringify(
                    expander.expand(
                        target, cellNames, graphBuilder, input.getMacroInput(), precomputedWork),
                    pathResolver);
              }
            };
      } catch (MacroException e) {
        throw new RuntimeException("No matching macro handler found", e);
      }
      replacers.put(entry.getKey(), replacer);
    }
    return replacers.build();
  }

  public ImmutableList<BuildRule> extractBuildTimeDeps(
      BuildTarget target, CellPathResolver cellNames, ActionGraphBuilder graphBuilder, String blob)
      throws MacroException {
    return extractBuildTimeDeps(target, cellNames, graphBuilder, blob, new HashMap<>());
  }

  public ImmutableList<BuildRule> extractBuildTimeDeps(
      BuildTarget target,
      CellPathResolver cellNames,
      ActionGraphBuilder graphBuilder,
      String blob,
      Map<MacroMatchResult, Object> precomputedWorkCache)
      throws MacroException {
    return BuildableSupport.deriveDeps(
            new AddsToRuleKey() {
              @AddToRuleKey
              private final Object object =
                  extractRuleKeyAppendables(
                      target, cellNames, graphBuilder, blob, precomputedWorkCache);
            },
            new SourcePathRuleFinder(graphBuilder))
        .collect(ImmutableList.toImmutableList());
  }

  public void extractParseTimeDeps(
      BuildTarget target,
      CellPathResolver cellNames,
      String blob,
      ImmutableCollection.Builder<BuildTarget> buildDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder)
      throws MacroException {

    // Iterate over all macros found in the string, collecting all `BuildTargetPaths` each expander
    // extract for their respective macros.
    for (MacroMatchResult matchResult : getMacroMatchResults(blob)) {
      getExpander(matchResult.getMacroType())
          .extractParseTimeDeps(
              target,
              cellNames,
              matchResult.getMacroInput(),
              buildDepsBuilder,
              targetGraphOnlyDepsBuilder);
    }
  }

  public ImmutableList<Object> extractRuleKeyAppendables(
      BuildTarget target,
      CellPathResolver cellNames,
      ActionGraphBuilder graphBuilder,
      String blob,
      Map<MacroMatchResult, Object> precomputedWorkCache)
      throws MacroException {

    ImmutableList.Builder<Object> targets = ImmutableList.builder();

    // Iterate over all macros found in the string, collecting all `BuildTargetPaths` each expander
    // extract for their respective macros.
    for (MacroMatchResult matchResult : getMacroMatchResults(blob)) {
      MacroExpander expander = getExpander(matchResult.getMacroType());
      Object precomputedWork =
          ensurePrecomputedWork(
              matchResult, expander, precomputedWorkCache, target, cellNames, graphBuilder);
      Object ruleKeyAppendable =
          expander.extractRuleKeyAppendables(
              target, cellNames, graphBuilder, matchResult.getMacroInput(), precomputedWork);
      if (ruleKeyAppendable != null) {
        targets.add(ruleKeyAppendable);
      }
    }

    return targets.build();
  }

  public ImmutableList<MacroMatchResult> getMacroMatchResults(String blob) throws MacroException {
    return MacroFinder.findAll(expanders.keySet(), blob);
  }

  private static Object ensurePrecomputedWork(
      MacroMatchResult matchResult,
      MacroExpander expander,
      Map<MacroMatchResult, Object> precomputedWorkCache,
      BuildTarget target,
      CellPathResolver cellNames,
      ActionGraphBuilder graphBuilder)
      throws MacroException {
    if (!precomputedWorkCache.containsKey(matchResult)) {
      precomputedWorkCache.put(
          matchResult,
          expander.precomputeWork(target, cellNames, graphBuilder, matchResult.getMacroInput()));
    }
    return precomputedWorkCache.get(matchResult);
  }
}
