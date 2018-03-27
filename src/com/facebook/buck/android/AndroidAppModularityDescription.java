/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.android.apkmodule.APKModuleGraph;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.Hint;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.immutables.value.Value;

public class AndroidAppModularityDescription
    implements Description<AndroidAppModularityDescriptionArg> {

  @Override
  public Class<AndroidAppModularityDescriptionArg> getConstructorArgType() {
    return AndroidAppModularityDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      AndroidAppModularityDescriptionArg args) {
    APKModuleGraph apkModuleGraph =
        new APKModuleGraph(
            Optional.of(args.getApplicationModuleConfigs()),
            args.getApplicationModuleDependencies(),
            context.getTargetGraph(),
            buildTarget);

    AndroidAppModularityGraphEnhancer graphEnhancer =
        new AndroidAppModularityGraphEnhancer(
            buildTarget, params, context.getBuildRuleResolver(), args.getNoDx(), apkModuleGraph);

    AndroidAppModularityGraphEnhancementResult result = graphEnhancer.createAdditionalBuildables();

    return new AndroidAppModularity(
        buildTarget,
        context.getProjectFilesystem(),
        params.withExtraDeps(result.getFinalDeps()),
        result);
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractAndroidAppModularityDescriptionArg
      extends CommonDescriptionArg, HasDeclaredDeps {

    Map<String, List<BuildTarget>> getApplicationModuleConfigs();

    Optional<Map<String, List<String>>> getApplicationModuleDependencies();

    @Hint(isDep = false)
    ImmutableSet<BuildTarget> getNoDx();
  }
}
