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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.Map;

/**
 * Extracts macros from input strings and calls registered expanders to handle their input.
 */
public class MacroHandler {

  private static final MacroFinder MACRO_FINDER = new MacroFinder();

  private final ImmutableMap<String, MacroExpander> expanders;

  public MacroHandler(ImmutableMap<String, MacroExpander> expanders) {
    this.expanders = addOutputToFileExpanders(expanders);
  }

  public Function<String, String> getExpander(
      final BuildTarget target,
      final BuildRuleResolver resolver,
      final ProjectFilesystem filesystem) {
    return new Function<String, String>() {
      @Override
      public String apply(String blob) {
        try {
          return expand(target, resolver, filesystem, blob);
        } catch (MacroException e) {
          throw new HumanReadableException("%s: %s", target, e.getMessage());
        }
      }
    };
  }

  public MacroHandler extend(ImmutableMap<String, MacroExpander> additionalExpanders) {
    return new MacroHandler(
        ImmutableMap.<String, MacroExpander>builder()
            .putAll(expanders)
            .putAll(addOutputToFileExpanders(additionalExpanders))
            .build());
  }

  private static ImmutableMap<String, MacroExpander> addOutputToFileExpanders(
      ImmutableMap<String, MacroExpander> source) {
    ImmutableMap.Builder<String, MacroExpander> builder = ImmutableMap.builder();
    for (Map.Entry<String, MacroExpander> entry : source.entrySet()) {
      builder.put(entry.getKey(), entry.getValue());
      builder.put("@" + entry.getKey(), new OutputToFileExpander(entry.getValue()));
    }
    return builder.build();
  }

  private MacroExpander getExpander(String name) throws MacroException {
    MacroExpander expander = expanders.get(name);
    if (expander == null) {
      throw new MacroException(String.format("no such macro \"%s\"", name));
    }
    return expander;
  }

  public String expand(
      final BuildTarget target,
      final BuildRuleResolver resolver,
      final ProjectFilesystem filesystem,
      String blob)
      throws MacroException {
    ImmutableMap.Builder<String, MacroReplacer> replacers = ImmutableMap.builder();
    for (final Map.Entry<String, MacroExpander> entry : expanders.entrySet()) {
      replacers.put(
          entry.getKey(),
          new MacroReplacer() {
            @Override
            public String replace(String input) throws MacroException {
              return getExpander(entry.getKey()).expand(target, resolver, filesystem, input);
            }
          });
    }
    return MACRO_FINDER.replace(replacers.build(), blob);
  }

  public ImmutableList<BuildTarget> extractTargets(
      BuildTarget target,
      String blob)
      throws MacroException {

    ImmutableList.Builder<BuildTarget> targets = ImmutableList.builder();

    // Iterate over all macros found in the string, collecting all `BuildTargets` each expander
    // extract for their respective macros.
    for (Pair<String, String> match : MACRO_FINDER.findAll(expanders.keySet(), blob)) {
      targets.addAll(getExpander(match.getFirst()).extractTargets(target, match.getSecond()));
    }

    return targets.build();
  }

}
