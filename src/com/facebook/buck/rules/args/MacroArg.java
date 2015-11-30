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

package com.facebook.buck.rules.args;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.macros.MacroException;
import com.facebook.buck.rules.macros.MacroHandler;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableCollection;

import java.nio.file.Path;
import java.util.Objects;

/**
 * An {@link Arg} which contains macros that need to be expanded.
 */
public class MacroArg extends Arg {

  private final MacroHandler expander;
  private final BuildTarget target;
  private final Function<Optional<String>, Path> cellNames;
  private final BuildRuleResolver resolver;
  private final ProjectFilesystem filesystem;
  private final String unexpanded;

  public MacroArg(
      MacroHandler expander,
      BuildTarget target,
      Function<Optional<String>, Path> cellNames,
      BuildRuleResolver resolver,
      ProjectFilesystem filesystem,
      String unexpanded) {
    this.expander = expander;
    this.target = target;
    this.cellNames = cellNames;
    this.resolver = resolver;
    this.filesystem = filesystem;
    this.unexpanded = unexpanded;
  }

  @Override
  public void appendToCommandLine(ImmutableCollection.Builder<String> builder) {
    try {
      builder.add(expander.expand(target, cellNames, resolver, filesystem, unexpanded));
    } catch (MacroException e) {
      throw new HumanReadableException(e, "%s: %s", target, e.getMessage());
    }
  }

  @Override
  public ImmutableCollection<BuildRule> getDeps(SourcePathResolver pathResolver) {
    try {
      return expander.extractBuildTimeDeps(target, cellNames, resolver, unexpanded);
    } catch (MacroException e) {
      throw new HumanReadableException(e, "%s: %s", target, e.getMessage());
    }
  }

  @Override
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
    try {
      return builder
          .setReflectively("arg", unexpanded)
          .setReflectively(
              "macros",
              expander.extractRuleKeyAppendables(target, cellNames, resolver, unexpanded));
    } catch (MacroException e) {
      throw new HumanReadableException(e, "%s: %s", target, e.getMessage());
    }
  }

  @Override
  public String toString() {
    return unexpanded;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof MacroArg)) {
      return false;
    }
    MacroArg macroArg = (MacroArg) o;
    return Objects.equals(target, macroArg.target) &&
        Objects.equals(unexpanded, macroArg.unexpanded);
  }

  @Override
  public int hashCode() {
    return Objects.hash(target, unexpanded);
  }

  public static Function<String, Arg> toMacroArgFunction(
      final MacroHandler expander,
      final BuildTarget target,
      final Function<Optional<String>, Path> cellNames,
      final BuildRuleResolver resolver,
      final ProjectFilesystem filesystem) {
    return new Function<String, Arg>() {
      @Override
      public MacroArg apply(String unexpanded) {
        return new MacroArg(expander, target, cellNames, resolver, filesystem, unexpanded);
      }
    };
  }

}
