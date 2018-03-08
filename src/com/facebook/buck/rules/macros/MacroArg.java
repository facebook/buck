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

package com.facebook.buck.rules.macros;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.macros.MacroException;
import com.facebook.buck.model.macros.MacroMatchResult;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.util.HumanReadableException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * An {@link Arg} which contains macros that need to be expanded.
 *
 * <p>Deprecated: Use {@link StringWithMacros} in constructor args and {@link
 * StringWithMacrosConverter} instead.
 */
@Deprecated
public class MacroArg implements Arg, RuleKeyAppendable {

  protected final MacroHandler expander;
  protected final BuildTarget target;
  protected final CellPathResolver cellNames;
  protected final BuildRuleResolver resolver;
  protected final String unexpanded;

  protected Map<MacroMatchResult, Object> precomputedWorkCache = new HashMap<>();

  public MacroArg(
      MacroHandler expander,
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver resolver,
      String unexpanded) {
    this.expander = expander;
    this.target = target;
    this.cellNames = cellNames;
    this.resolver = resolver;
    this.unexpanded = unexpanded;
  }

  @Override
  public void appendToCommandLine(Consumer<String> consumer, SourcePathResolver pathResolver) {
    try {
      consumer.accept(expander.expand(target, cellNames, resolver, unexpanded));
    } catch (MacroException e) {
      throw new HumanReadableException(e, "%s: %s", target, e.getMessage());
    }
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    try {
      sink.setReflectively("arg", unexpanded)
          .setReflectively(
              "macros",
              expander.extractRuleKeyAppendables(
                  target, cellNames, resolver, unexpanded, precomputedWorkCache));
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
    return Objects.equals(target, macroArg.target)
        && Objects.equals(unexpanded, macroArg.unexpanded);
  }

  @Override
  public int hashCode() {
    return Objects.hash(target, unexpanded);
  }

  public static Function<String, Arg> toMacroArgFunction(
      MacroHandler handler,
      BuildTarget target,
      CellPathResolver cellNames,
      BuildRuleResolver resolver) {
    return unexpanded -> {
      MacroArg arg = new MacroArg(handler, target, cellNames, resolver, unexpanded);
      try {
        if (containsWorkerMacro(handler, unexpanded)) {
          return WorkerMacroArg.fromMacroArg(arg, handler, target, cellNames, resolver, unexpanded);
        }
      } catch (MacroException e) {
        throw new HumanReadableException(e, "%s: %s", target, e.getMessage());
      }
      return arg;
    };
  }

  public static boolean containsWorkerMacro(MacroHandler handler, String blob)
      throws MacroException {
    boolean result = false;
    for (MacroMatchResult matchResult : handler.getMacroMatchResults(blob)) {
      if (handler.getExpander(matchResult.getMacroType()) instanceof WorkerMacroExpander) {
        result = true;
      }
    }
    return result;
  }
}
