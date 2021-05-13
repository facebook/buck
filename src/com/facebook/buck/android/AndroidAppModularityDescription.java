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

package com.facebook.buck.android;

import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasApplicationModuleBlacklist;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.arg.Hint;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.rules.query.Query;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.List;
import java.util.Optional;
import org.immutables.value.Value;

public class AndroidAppModularityDescription
    implements DescriptionWithTargetGraph<AndroidAppModularityDescriptionArg> {

  @Override
  public Class<AndroidAppModularityDescriptionArg> getConstructorArgType() {
    return AndroidAppModularityDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      AndroidAppModularityDescriptionArg args) {
    APKModuleGraph apkModuleGraph =
        new APKModuleGraph(
            Optional.of(args.getApplicationModuleConfigs()),
            args.getApplicationModuleDependencies(),
            APKModuleGraph.extractTargetsFromQueries(args.getApplicationModuleBlacklist()),
            ImmutableSet.of(),
            ImmutableSet.of(),
            context.getTargetGraph(),
            buildTarget);

    AndroidAppModularityGraphEnhancer graphEnhancer =
        new AndroidAppModularityGraphEnhancer(
            buildTarget,
            params,
            context.getActionGraphBuilder(),
            context.getToolchainProvider(),
            args.getNoDx(),
            args.getShouldIncludeLibraries(),
            apkModuleGraph,
            context.getConfigurationRuleRegistry());

    AndroidAppModularityGraphEnhancementResult result = graphEnhancer.createAdditionalBuildables();

    return new AndroidAppModularity(
        buildTarget,
        context.getProjectFilesystem(),
        result,
        args.getShouldIncludeClasses(),
        apkModuleGraph);
  }

  @RuleArg
  interface AbstractAndroidAppModularityDescriptionArg
      extends BuildRuleArg, HasDeclaredDeps, HasApplicationModuleBlacklist {
    ImmutableMap<String, ImmutableList<BuildTarget>> getApplicationModuleConfigs();

    Optional<ImmutableMap<String, ImmutableList<String>>> getApplicationModuleDependencies();

    @Hint(isDep = false)
    ImmutableSet<BuildTarget> getNoDx();

    @Value.Default
    default boolean getShouldIncludeClasses() {
      return true;
    }

    @Value.Default
    default boolean getShouldIncludeLibraries() {
      return false;
    }

    @Override
    default AndroidAppModularityDescriptionArg withApplicationModuleBlacklist(List<Query> queries) {
      if (getApplicationModuleBlacklist().equals(Optional.of(queries))) {
        return (AndroidAppModularityDescriptionArg) this;
      }
      return AndroidAppModularityDescriptionArg.builder()
          .from(this)
          .setApplicationModuleBlacklist(queries)
          .build();
    }
  }
}
