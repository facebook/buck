/*
 * Copyright 2018-present Facebook, Inc.
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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.macros.MacroException;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.CompositeArg;
import com.facebook.buck.rules.args.SanitizedArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.args.WriteToFileArg;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.util.HashMap;
import java.util.Optional;
import java.util.function.Function;
import org.immutables.value.Value;

/**
 * Converts a {@link StringWithMacros} into an {@link Arg}. Performs conversion eagerly, and meant
 * as a replacement for the lazy {@link StringWithMacrosArg}.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractStringWithMacrosConverter {

  @Value.Parameter
  abstract BuildTarget getBuildTarget();

  @Value.Parameter
  abstract CellPathResolver getCellPathResolver();

  @Value.Parameter
  abstract BuildRuleResolver getResolver();

  @Value.Parameter
  abstract ImmutableList<AbstractMacroExpander<? extends Macro, ?>> getExpanders();

  abstract Optional<Function<String, String>> getSanitizer();

  @Value.Default
  @Value.Auxiliary
  @SuppressWarnings("PMD.LooseCoupling")
  HashMap<Macro, Object> getPrecomputedWorkCache() {
    return new HashMap<>();
  }

  @Value.Derived
  ImmutableMap<Class<? extends Macro>, AbstractMacroExpander<? extends Macro, ?>>
      getClassExpanders() {
    ImmutableMap.Builder<Class<? extends Macro>, AbstractMacroExpander<? extends Macro, ?>>
        builder = ImmutableMap.builder();
    for (AbstractMacroExpander<? extends Macro, ?> expander : getExpanders()) {
      builder.put(expander.getInputClass(), expander);
    }
    return builder.build();
  }

  @SuppressWarnings("unchecked")
  private <M extends Macro, P> AbstractMacroExpander<M, P> getExpander(M macro)
      throws MacroException {
    AbstractMacroExpander<M, P> expander =
        (AbstractMacroExpander<M, P>) getClassExpanders().get(macro.getClass());
    if (expander == null) {
      throw new MacroException(String.format("unexpected macro %s", macro.getClass()));
    }
    return expander;
  }

  @SuppressWarnings("unchecked")
  private <T extends Macro, P> Arg expand(T macro) throws MacroException {
    AbstractMacroExpander<T, P> expander = getExpander(macro);

    // Calculate precomputed work.
    P precomputedWork = (P) getPrecomputedWorkCache().get(macro);
    if (precomputedWork == null) {
      precomputedWork =
          expander.precomputeWorkFrom(
              getBuildTarget(), getCellPathResolver(), getResolver(), macro);
      getPrecomputedWorkCache().put(macro, precomputedWork);
    }

    return expander.expandFrom(
        getBuildTarget(), getCellPathResolver(), getResolver(), macro, precomputedWork);
  }

  /**
   * Expand the input given for the this macro to some string, which is intended to be written to a
   * file.
   */
  private Arg expand(MacroContainer macroContainer) throws MacroException {
    Arg arg = expand(macroContainer.getMacro());

    // If specified, wrap this macro's output in a `WriteToFileArg`.
    if (macroContainer.isOutputToFile()) {
      // "prefix" should give a stable name, so that the same delegate with the same input can
      // output the same file. We won't optimise for this case, since it's actually unlikely to
      // happen within a single run, but using a random name would cause 'buck-out' to expand in an
      // uncontrolled manner.
      Hasher hasher = Hashing.sha256().newHasher();
      hasher.putString(macroContainer.getMacro().getClass().getName(), UTF_8);
      hasher.putInt(macroContainer.getMacro().hashCode());
      String prefix = hasher.hash().toString();
      arg = new WriteToFileArg(getBuildTarget(), prefix, arg);
    }

    return arg;
  }

  public Arg convert(StringWithMacros val) {
    return CompositeArg.of(
        val.map(
            str ->
                getSanitizer()
                    .<Arg>map(sanitizer -> SanitizedArg.create(sanitizer, str))
                    .orElseGet(() -> StringArg.of(str)),
            macroContainer -> {
              try {
                return expand(macroContainer);
              } catch (MacroException e) {
                throw new HumanReadableException(e, "%s: %s", getBuildTarget(), e.getMessage());
              }
            }));
  }
}
