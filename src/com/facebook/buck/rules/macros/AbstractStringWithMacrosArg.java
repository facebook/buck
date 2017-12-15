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

package com.facebook.buck.rules.macros;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.macros.MacroException;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/**
 * Packages a {@link com.facebook.buck.rules.macros.StringWithMacros} along with necessary objects
 * to implement the {@link Arg} interface.
 */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractStringWithMacrosArg implements Arg, RuleKeyAppendable {

  abstract StringWithMacros getStringWithMacros();

  abstract ImmutableList<AbstractMacroExpanderWithoutPrecomputedWork<? extends Macro>>
      getExpanders();

  abstract Optional<Function<String, String>> getSanitizer();

  abstract BuildTarget getBuildTarget();

  abstract CellPathResolver getCellPathResolver();

  abstract BuildRuleResolver getBuildRuleResolver();

  @Value.Derived
  ImmutableMap<Class<? extends Macro>, AbstractMacroExpanderWithoutPrecomputedWork<? extends Macro>>
      getClassExpanders() {
    ImmutableMap.Builder<
            Class<? extends Macro>, AbstractMacroExpanderWithoutPrecomputedWork<? extends Macro>>
        builder = ImmutableMap.builder();
    for (AbstractMacroExpanderWithoutPrecomputedWork<? extends Macro> expander : getExpanders()) {
      builder.put(expander.getInputClass(), expander);
    }
    return builder.build();
  }

  @SuppressWarnings("unchecked")
  private <M extends Macro> AbstractMacroExpanderWithoutPrecomputedWork<M> getExpander(M macro)
      throws MacroException {
    AbstractMacroExpanderWithoutPrecomputedWork<M> expander =
        (AbstractMacroExpanderWithoutPrecomputedWork<M>) getClassExpanders().get(macro.getClass());
    if (expander == null) {
      throw new MacroException(String.format("unexpected macro %s", macro.getClass()));
    }
    return expander;
  }

  private HumanReadableException toHumanReadableException(MacroException e) {
    return new HumanReadableException(e, "%s: %s", getBuildTarget(), e.getMessage());
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

  /** Expands all macros to strings and append them to the given builder. */
  @Override
  public void appendToCommandLine(Consumer<String> consumer, SourcePathResolver pathResolver) {
    consumer.accept(getStringWithMacros().format(this::expand));
  }

  /** Add the macros to the rule key. */
  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively(
        "macros",
        getStringWithMacros()
            .map(
                s -> {
                  if (getSanitizer().isPresent()) {
                    return getSanitizer().get().apply(s);
                  } else {
                    return s;
                  }
                },
                this::extractRuleKeyAppendables));
  }
}
