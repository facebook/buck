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

package com.facebook.buck.core.toolchain.rule;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.RuleDescription;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.actions.ActionCreationException;
import com.facebook.buck.core.rules.analysis.RuleAnalysisContext;
import com.facebook.buck.core.rules.analysis.RuleAnalysisException;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.toolchain.RuleAnalysisLegacyToolchain;
import com.facebook.buck.core.toolchain.Toolchain;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.google.common.collect.ImmutableCollection;

/**
 * Description that wraps legacy toolchains by name. e.g. to allow access to .NET toolchains without
 * having to re-implement them.
 *
 * <p>'legacy' toolchains are ones that may do extra work behind the scenes in a way that is not
 * directly able to be represented by Rule Analysis. This might be something like looking at the
 * local environment, finding executables on the system path, etc. They are also used to minimmize
 * the amount of porting work that has to be done up-front. Old rules can continue to use old
 * toolchain retreival methods like {@link ToolchainProvider#getByName(String,
 * TargetConfiguration)}, whereas new UDR/RAG rules can have a clean interface where {@link
 * com.facebook.buck.core.rules.providers.Provider} instances do all necessary communication about
 * toolchains.
 *
 * <p>We expose these currently so that new rules can use existing compilers, tools, etc. However in
 * the future, these should be discarded in favor of proper build rules that only exist in RAG/UDR.
 */
public class LegacyToolchainRuleDescription
    implements RuleDescription<LegacyToolchainDescriptionArg>,
        ImplicitDepsInferringDescription<LegacyToolchainDescriptionArg> {

  private final ToolchainProvider toolchainProvider;

  public LegacyToolchainRuleDescription(ToolchainProvider toolchainProvider) {
    this.toolchainProvider = toolchainProvider;
  }

  @Override
  public ProviderInfoCollection ruleImpl(
      RuleAnalysisContext context, BuildTarget target, LegacyToolchainDescriptionArg args)
      throws ActionCreationException, RuleAnalysisException {
    TargetConfiguration configuration = target.getTargetConfiguration();
    RuleAnalysisLegacyToolchain toolchain = getToolchain(args.getToolchainName(), configuration);
    return toolchain.getProviders(context, configuration);
  }

  @Override
  public Class<LegacyToolchainDescriptionArg> getConstructorArgType() {
    return LegacyToolchainDescriptionArg.class;
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      LegacyToolchainDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {

    TargetConfiguration configuration = buildTarget.getTargetConfiguration();
    String toolchainName = constructorArg.getToolchainName();
    RuleAnalysisLegacyToolchain toolchain = getToolchain(toolchainName, configuration);
    toolchain.visitToolDependencies(configuration, extraDepsBuilder::add);
  }

  private RuleAnalysisLegacyToolchain getToolchain(
      String toolchainName, TargetConfiguration targetConfiguration) {
    Toolchain toolchain = toolchainProvider.getByName(toolchainName, targetConfiguration);

    if (!(toolchain instanceof RuleAnalysisLegacyToolchain)) {
      throw new HumanReadableException(
          "Toolchain %s is not compatible with RuleAnalysis currently. This must be done to use %s ",
          toolchainName, toolchainName);
    }
    return (RuleAnalysisLegacyToolchain) toolchain;
  }

  /** Args for legacy_toolchain() */
  @RuleArg
  interface AbstractLegacyToolchainDescriptionArg extends BuildRuleArg {
    /** The name of the legacy toolchain, from {@link Toolchain#getName()} */
    String getToolchainName();
  }
}
