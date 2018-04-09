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

package com.facebook.buck.features.gwt;

import com.facebook.buck.features.gwt.GwtBinary.Style;
import com.facebook.buck.graph.AbstractBreadthFirstTraversal;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.toolchain.JavaOptionsProvider;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import org.immutables.value.Value;

public class GwtBinaryDescription implements Description<GwtBinaryDescriptionArg> {

  /** Default value for {@link GwtBinaryDescriptionArg#style}. */
  private static final Style DEFAULT_STYLE = Style.OBF;

  /** Default value for {@link GwtBinaryDescriptionArg#localWorkers}. */
  private static final Integer DEFAULT_NUM_LOCAL_WORKERS = Integer.valueOf(2);

  /** Default value for {@link GwtBinaryDescriptionArg#draftCompile}. */
  private static final Boolean DEFAULT_DRAFT_COMPILE = Boolean.FALSE;

  /** Default value for {@link GwtBinaryDescriptionArg#strict}. */
  private static final Boolean DEFAULT_STRICT = Boolean.FALSE;

  /** This value is taken from GWT's source code: http://bit.ly/1nZtmMv */
  private static final Integer DEFAULT_OPTIMIZE = Integer.valueOf(9);

  private final ToolchainProvider toolchainProvider;

  public GwtBinaryDescription(ToolchainProvider toolchainProvider) {
    this.toolchainProvider = toolchainProvider;
  }

  @Override
  public Class<GwtBinaryDescriptionArg> getConstructorArgType() {
    return GwtBinaryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      GwtBinaryDescriptionArg args) {

    BuildRuleResolver resolver = context.getBuildRuleResolver();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);

    ImmutableSortedSet.Builder<BuildRule> extraDeps = ImmutableSortedSet.naturalOrder();

    // Find all of the reachable JavaLibrary rules and grab their associated GwtModules.
    ImmutableSortedSet.Builder<SourcePath> gwtModuleJarsBuilder = ImmutableSortedSet.naturalOrder();
    ImmutableSortedSet<BuildRule> moduleDependencies = resolver.getAllRules(args.getModuleDeps());
    new AbstractBreadthFirstTraversal<BuildRule>(moduleDependencies) {
      @Override
      public Iterable<BuildRule> visit(BuildRule rule) {
        if (!(rule instanceof JavaLibrary)) {
          return ImmutableSet.of();
        }

        // If the java library doesn't generate any output, it doesn't contribute a GwtModule
        JavaLibrary javaLibrary = (JavaLibrary) rule;
        if (javaLibrary.getSourcePathToOutput() == null) {
          return rule.getBuildDeps();
        }

        Optional<BuildRule> gwtModule;
        if (javaLibrary.getSourcePathToOutput() != null) {
          gwtModule =
              Optional.of(
                  resolver.computeIfAbsent(
                      BuildTarget.of(
                          javaLibrary.getBuildTarget().checkUnflavored(),
                          ImmutableSet.of(JavaLibrary.GWT_MODULE_FLAVOR)),
                      gwtModuleTarget -> {
                        ImmutableSortedSet<SourcePath> filesForGwtModule =
                            ImmutableSortedSet.<SourcePath>naturalOrder()
                                .addAll(javaLibrary.getSources())
                                .addAll(javaLibrary.getResources())
                                .build();
                        ImmutableSortedSet<BuildRule> deps =
                            ImmutableSortedSet.copyOf(
                                ruleFinder.filterBuildRuleInputs(filesForGwtModule));

                        return new GwtModule(
                            gwtModuleTarget,
                            context.getProjectFilesystem(),
                            params.withDeclaredDeps(deps).withoutExtraDeps(),
                            ruleFinder,
                            filesForGwtModule);
                      }));
        } else {
          gwtModule = Optional.empty();
        }

        // Note that gwtModule could be absent if javaLibrary is a rule with no srcs of its own,
        // but a rule that exists only as a collection of deps.
        if (gwtModule.isPresent()) {
          extraDeps.add(gwtModule.get());
          gwtModuleJarsBuilder.add(
              Preconditions.checkNotNull(gwtModule.get().getSourcePathToOutput()));
        }

        // Traverse all of the deps of this rule.
        return rule.getBuildDeps();
      }
    }.start();

    return new GwtBinary(
        buildTarget,
        context.getProjectFilesystem(),
        params.withExtraDeps(extraDeps.build()),
        args.getModules(),
        toolchainProvider
            .getByName(JavaOptionsProvider.DEFAULT_NAME, JavaOptionsProvider.class)
            .getJavaOptions()
            .getJavaRuntimeLauncher(),
        args.getVmArgs(),
        args.getStyle().orElse(DEFAULT_STYLE),
        args.getDraftCompile().orElse(DEFAULT_DRAFT_COMPILE),
        args.getOptimize().orElse(DEFAULT_OPTIMIZE),
        args.getLocalWorkers().orElse(DEFAULT_NUM_LOCAL_WORKERS),
        args.getStrict().orElse(DEFAULT_STRICT),
        args.getExperimentalArgs(),
        gwtModuleJarsBuilder.build());
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractGwtBinaryDescriptionArg extends CommonDescriptionArg, HasDeclaredDeps {
    @Value.NaturalOrder
    ImmutableSortedSet<String> getModules();

    @Value.NaturalOrder
    ImmutableSortedSet<BuildTarget> getModuleDeps();

    /** In practice, these may be values such as {@code -Xmx512m}. */
    ImmutableList<String> getVmArgs();

    /** This will be passed to the GWT Compiler's {@code -style} flag. */
    Optional<Style> getStyle();

    /** If {@code true}, the GWT Compiler's {@code -draftCompile} flag will be set. */
    Optional<Boolean> getDraftCompile();

    /** This will be passed to the GWT Compiler's {@code -optimize} flag. */
    Optional<Integer> getOptimize();

    /** This will be passed to the GWT Compiler's {@code -localWorkers} flag. */
    Optional<Integer> getLocalWorkers();

    /** If {@code true}, the GWT Compiler's {@code -strict} flag will be set. */
    Optional<Boolean> getStrict();

    /**
     * In practice, these may be values such as {@code -XenableClosureCompiler}, {@code
     * -XdisableClassMetadata}, {@code -XdisableCastChecking}, or {@code -XfragmentMerge}.
     */
    ImmutableList<String> getExperimentalArgs();
  }
}
