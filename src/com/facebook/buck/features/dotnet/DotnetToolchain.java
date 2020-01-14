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

package com.facebook.buck.features.dotnet;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.analysis.context.DependencyOnlyRuleAnalysisContext;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.collect.impl.ProviderInfoCollectionImpl;
import com.facebook.buck.core.rules.providers.lib.ImmutableDefaultInfo;
import com.facebook.buck.core.rules.providers.lib.RunInfo;
import com.facebook.buck.core.toolchain.RuleAnalysisLegacyToolchain;
import com.facebook.buck.core.toolchain.Toolchain;
import com.facebook.buck.core.toolchain.toolprovider.RuleAnalysisLegacyToolProvider;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.toolchain.toolprovider.impl.SystemToolProvider;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.base.Verify;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import java.util.function.Consumer;

/** Toolchain for dotnet */
@BuckStyleValue
abstract class DotnetToolchain implements RuleAnalysisLegacyToolchain, Toolchain {
  static final String DEFAULT_NAME = "dotnet-toolchain";

  abstract DotnetBuckConfig getDotnetBuckConfig();

  abstract SystemToolProvider getSystemCsharpCompiler();

  public ToolProvider getCsharpCompiler() {
    return getDotnetBuckConfig().getCsharpCompiler().orElse(getSystemCsharpCompiler());
  }

  @Override
  public String getName() {
    return DEFAULT_NAME;
  }

  @Override
  public ProviderInfoCollection getProviders(
      DependencyOnlyRuleAnalysisContext context, TargetConfiguration targetConfiguration) {
    ToolProvider provider = getCsharpCompiler();
    Verify.verify(provider instanceof RuleAnalysisLegacyToolProvider);

    RunInfo compilerInfo =
        ((RuleAnalysisLegacyToolProvider) provider).getRunInfo(context, targetConfiguration);
    DotnetLegacyToolchainInfo info = new ImmutableDotnetLegacyToolchainInfo(compilerInfo);

    return ProviderInfoCollectionImpl.builder()
        .put(info)
        .build(new ImmutableDefaultInfo(SkylarkDict.empty(), ImmutableList.of()));
  }

  @Override
  public void visitToolDependencies(
      TargetConfiguration targetConfiguration, Consumer<BuildTarget> builder) {
    getCsharpCompiler().getParseTimeDeps(targetConfiguration).forEach(builder);
  }
}
