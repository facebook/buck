/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.macros.AbstractMacroExpander;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/**
 * Packages a {@link StringWithMacros} along with necessary objects to implement the {@link Arg}
 * interface.
 */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractStringWithMacrosArg implements Arg {

  abstract StringWithMacros getStringWithMacros();

  abstract ImmutableList<AbstractMacroExpander<? extends Macro>> getExpanders();

  abstract BuildTarget getBuildTarget();

  abstract CellPathResolver getCellPathResolver();

  abstract BuildRuleResolver getBuildRuleResolver();

  @Value.Derived
  ImmutableMap<Class<? extends Macro>, AbstractMacroExpander<? extends Macro>> getClassExpanders() {
    ImmutableMap.Builder<Class<? extends Macro>, AbstractMacroExpander<? extends Macro>> builder =
        ImmutableMap.builder();
    for (AbstractMacroExpander<? extends Macro> expander : getExpanders()) {
      builder.put(expander.getInputClass(), expander);
    }
    return builder.build();
  }

  @SuppressWarnings("unchecked")
  private <M extends Macro> AbstractMacroExpander<M> getExpander(M macro) throws MacroException {
    AbstractMacroExpander<M> expander =
        (AbstractMacroExpander<M>) getClassExpanders().get(macro.getClass());
    if (expander == null) {
      throw new MacroException(String.format("unexpected macro %s", macro.getClass()));
    }
    return expander;
  }

  private HumanReadableException toHumanReadableException(MacroException e) {
    return new HumanReadableException(e, "%s: %s", getBuildTarget(), e.getMessage());
  }

  private <M extends Macro> ImmutableCollection<BuildRule> extractDeps(M macro) {
    try {
      return getExpander(macro)
          .extractBuildTimeDepsFrom(
              getBuildTarget(), getCellPathResolver(), getBuildRuleResolver(), macro);
    } catch (MacroException e) {
      throw toHumanReadableException(e);
    }
  }

  @Nullable
  private <M extends Macro> Object extractRuleKeyAppendables(M macro) {
    try {
      return getExpander(macro)
          .extractRuleKeyAppendablesFrom(
              getBuildTarget(), getCellPathResolver(), getBuildRuleResolver(), macro);
    } catch (MacroException e) {
      throw toHumanReadableException(e);
    }
  }

  private <M extends Macro> String expand(M macro) {
    try {
      return getExpander(macro)
          .expandFrom(getBuildTarget(), getCellPathResolver(), getBuildRuleResolver(), macro);
    } catch (MacroException e) {
      throw toHumanReadableException(e);
    }
  }

  /** @return the build-time deps from all embedded macros. */
  public ImmutableCollection<BuildRule> getDeps() {
    return RichStream.from(getStringWithMacros().getMacros())
        .map(this::extractDeps)
        .flatMap(ImmutableCollection::stream)
        .toImmutableList();
  }

  @Override
  public ImmutableCollection<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
    return getDeps();
  }

  /** @return the inputs from all embedded macros. */
  @Override
  public ImmutableCollection<SourcePath> getInputs() {
    return RichStream.from(getDeps()).map(BuildRule::getSourcePathToOutput).toImmutableList();
  }

  /** Expands all macros to strings and append them to the given builder. */
  @Override
  public void appendToCommandLine(
      ImmutableCollection.Builder<String> builder, SourcePathResolver pathResolver) {
    builder.add(getStringWithMacros().format(this::expand));
  }

  /** Add the macros to the rule key. */
  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively(
        "macros", getStringWithMacros().map(s -> s, this::extractRuleKeyAppendables));
  }
}
