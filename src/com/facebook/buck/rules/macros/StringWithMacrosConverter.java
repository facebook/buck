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

package com.facebook.buck.rules.macros;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.macros.MacroException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.CompositeArg;
import com.facebook.buck.rules.args.SanitizedArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.args.WriteToFileArg;
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
 * as a replacement for the lazy {@link Arg}.
 *
 * <p>As this holds a reference to an {@link ActionGraphBuilder}, instances of this object should
 * not be capture by anything in the action graph.
 */
@BuckStyleValue
public abstract class StringWithMacrosConverter {

  public abstract BuildTarget getBuildTarget();

  protected abstract CellNameResolver getCellNameResolver();

  public abstract ActionGraphBuilder getActionGraphBuilder();

  public abstract ImmutableList<MacroExpander<? extends Macro, ?>> getExpanders();

  public abstract Optional<Function<String, String>> getSanitizer();

  public static StringWithMacrosConverter of(
      BuildTarget buildTarget,
      CellNameResolver cellNameResolver,
      ActionGraphBuilder actionGraphBuilder,
      ImmutableList<MacroExpander<? extends Macro, ?>> expanders) {
    return of(
        buildTarget,
        cellNameResolver,
        actionGraphBuilder,
        expanders,
        Optional.empty(),
        new HashMap<>());
  }

  public static StringWithMacrosConverter of(
      BuildTarget buildTarget,
      CellNameResolver cellNameResolver,
      ActionGraphBuilder actionGraphBuilder,
      ImmutableList<MacroExpander<? extends Macro, ?>> expanders,
      Optional<Function<String, String>> sanitizer) {
    return of(
        buildTarget, cellNameResolver, actionGraphBuilder, expanders, sanitizer, new HashMap<>());
  }

  @SuppressWarnings("PMD.LooseCoupling")
  public static StringWithMacrosConverter of(
      BuildTarget buildTarget,
      CellNameResolver cellNameResolver,
      ActionGraphBuilder actionGraphBuilder,
      ImmutableList<MacroExpander<? extends Macro, ?>> expanders,
      Optional<Function<String, String>> sanitizer,
      HashMap<Macro, Object> precomputedWorkCache) {
    return ImmutableStringWithMacrosConverter.of(
        buildTarget,
        cellNameResolver,
        actionGraphBuilder,
        expanders,
        sanitizer,
        precomputedWorkCache);
  }

  @Value.Auxiliary
  @SuppressWarnings("PMD.LooseCoupling")
  public abstract HashMap<Macro, Object> getPrecomputedWorkCache();

  @Value.Derived
  public ImmutableMap<Class<? extends Macro>, MacroExpander<? extends Macro, ?>>
      getClassExpanders() {
    ImmutableMap.Builder<Class<? extends Macro>, MacroExpander<? extends Macro, ?>> builder =
        ImmutableMap.builder();
    for (MacroExpander<? extends Macro, ?> expander : getExpanders()) {
      builder.put(expander.getInputClass(), expander);
    }
    return builder.build();
  }

  @SuppressWarnings("unchecked")
  private <M extends Macro, P> MacroExpander<M, P> getExpander(M macro) throws MacroException {
    MacroExpander<M, P> expander =
        (MacroExpander<M, P>) getClassExpanders().get(macro.getMacroClass());
    if (expander == null) {
      throw new MacroException(String.format("unexpected macro %s", macro.getMacroClass()));
    }
    return expander;
  }

  @SuppressWarnings("unchecked")
  private <T extends Macro, P> Arg expand(T macro) throws MacroException {
    MacroExpander<T, P> expander = getExpander(macro);

    // Calculate precomputed work.
    P precomputedWork = (P) getPrecomputedWorkCache().get(macro);
    if (precomputedWork == null) {
      precomputedWork =
          expander.precomputeWorkFrom(
              getBuildTarget(), getCellNameResolver(), getActionGraphBuilder(), macro);
      getPrecomputedWorkCache().put(macro, precomputedWork);
    }

    return expander.expandFrom(getBuildTarget(), getActionGraphBuilder(), macro, precomputedWork);
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
      hasher.putString(macroContainer.getMacro().getMacroClass().getName(), UTF_8);
      hasher.putInt(macroContainer.getMacro().hashCode());
      String prefix = hasher.hash().toString();
      arg = new WriteToFileArg(getBuildTarget(), prefix, arg);
    }

    return arg;
  }

  private Arg transformString(String str) {
    return getSanitizer()
        .<Arg>map(sanitizer -> SanitizedArg.create(sanitizer, str))
        .orElseGet(() -> StringArg.of(str));
  }

  private Arg transformMacro(MacroContainer macroContainer) {
    try {
      return expand(macroContainer);
    } catch (MacroException e) {
      throw new HumanReadableException(e, "%s: %s", getBuildTarget(), e.getMessage());
    }
  }

  public Arg convert(StringWithMacros val) {
    if (val.getParts().size() == 0) {
      return StringArg.of("");
    }
    if (val.getParts().size() == 1) {
      return val.getParts().get(0).transform(this::transformString, this::transformMacro);
    }
    return CompositeArg.of(val.map(this::transformString, this::transformMacro));
  }
}
