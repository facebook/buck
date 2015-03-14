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

package com.facebook.buck.gwt;

import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.java.JavaLibrary;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.nio.file.Path;

public class GwtBinaryDescription implements Description<GwtBinaryDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("gwt_binary");

  /** Default value for {@link Arg#style}. */
  private static final String DEFAULT_STYLE = GwtBinary.Style.OBF.name();

  /** Default value for {@link Arg#localWorkers}. */
  private static final Integer DEFAULT_NUM_LOCAL_WORKERS = Integer.valueOf(2);

  /** Default value for {@link Arg#draftCompile}. */
  private static final Boolean DEFAULT_DRAFT_COMPILE = Boolean.FALSE;

  /** Default value for {@link Arg#strict}. */
  private static final Boolean DEFAULT_STRICT = Boolean.FALSE;

  /**
   * This value is taken from GWT's source code: http://bit.ly/1nZtmMv
   */
  private static final Integer DEFAULT_OPTIMIZE = Integer.valueOf(9);

  @SuppressFieldNotInitialized
  public static class Arg {
    public Optional<ImmutableSortedSet<String>> modules;
    public Optional<ImmutableSortedSet<BuildTarget>> moduleDeps;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;

    /**
     * In practice, these may be values such as {@code -Xmx512m}.
     */
    public Optional<ImmutableList<String>> vmArgs;

    /** This will be passed to the GWT Compiler's {@code -style} flag. */
    // TODO(simons): t4058780 Introduce an EnumTypeCoercer so we can make this Optional<Style>.
    public Optional<String> style;

    /** If {@code true}, the GWT Compiler's {@code -draftCompile} flag will be set. */
    public Optional<Boolean> draftCompile;

    /** This will be passed to the GWT Compiler's {@code -optimize} flag. */
    public Optional<Integer> optimize;

    /** This will be passed to the GWT Compiler's {@code -localWorkers} flag. */
    public Optional<Integer> localWorkers;

    /** If {@code true}, the GWT Compiler's {@code -strict} flag will be set. */
    public Optional<Boolean> strict;

    /**
     * In practice, these may be values such as {@code -XenableClosureCompiler},
     * {@code -XdisableClassMetadata}, {@code -XdisableCastChecking}, or {@code -XfragmentMerge}.
     */
    public Optional<ImmutableList<String>> experimentalArgs;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      BuildRuleParams params,
      final BuildRuleResolver resolver,
      A args) {

    final ImmutableSortedSet.Builder<BuildRule> extraDeps = ImmutableSortedSet.naturalOrder();

    // Find all of the reachable JavaLibrary rules and grab their associated GwtModules.
    final ImmutableSortedSet.Builder<Path> gwtModuleJarsBuilder =
        ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet<BuildRule> moduleDependencies = resolver.getAllRules(args.moduleDeps.get());
    new AbstractBreadthFirstTraversal<BuildRule>(moduleDependencies) {
      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) {
        if (!(rule instanceof JavaLibrary)) {
          return ImmutableSet.of();
        }

        JavaLibrary javaLibrary = (JavaLibrary) rule;
        BuildTarget gwtModuleTarget = BuildTargets.createFlavoredBuildTarget(
            javaLibrary.getBuildTarget().checkUnflavored(),
            JavaLibrary.GWT_MODULE_FLAVOR);
        Optional<BuildRule> gwtModule = resolver.getRuleOptional(gwtModuleTarget);

        // Note that gwtModule could be absent if javaLibrary is a rule with no srcs of its own,
        // but a rule that exists only as a collection of deps.
        if (gwtModule.isPresent()) {
          extraDeps.add(gwtModule.get());
          gwtModuleJarsBuilder.add(
              Preconditions.checkNotNull(gwtModule.get().getPathToOutputFile()));
        }

        // Traverse all of the deps of this rule.
        return rule.getDeps();
      }
    }.start();

    return new GwtBinary(
        params.copyWithExtraDeps(Suppliers.ofInstance(extraDeps.build())),
        new SourcePathResolver(resolver),
        args.modules.get(),
        args.vmArgs.get(),
        GwtBinary.Style.valueOf(args.style.or(DEFAULT_STYLE)),
        args.draftCompile.or(DEFAULT_DRAFT_COMPILE),
        args.optimize.or(DEFAULT_OPTIMIZE),
        args.localWorkers.or(DEFAULT_NUM_LOCAL_WORKERS),
        args.strict.or(DEFAULT_STRICT),
        args.experimentalArgs.get(),
        moduleDependencies,
        gwtModuleJarsBuilder.build());
  }
}
