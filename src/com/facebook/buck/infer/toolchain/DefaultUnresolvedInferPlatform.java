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

package com.facebook.buck.infer.toolchain;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.infer.InferConfig;
import com.facebook.buck.infer.InferPlatform;
import com.facebook.buck.infer.UnresolvedInferPlatform;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

/**
 * Default implementation of {@link UnresolvedInferPlatform} which build platform based on values
 * from config and (if provided) environment.
 */
class DefaultUnresolvedInferPlatform implements UnresolvedInferPlatform {
  private final InferConfig inferConfig;
  private final ToolProvider toolProvider;

  public DefaultUnresolvedInferPlatform(InferConfig inferConfig, ToolProvider toolProvider) {
    this.inferConfig = inferConfig;
    this.toolProvider = toolProvider;
  }

  @Override
  public InferPlatform resolve(BuildRuleResolver resolver, TargetConfiguration configuration) {
    Tool tool = toolProvider.resolve(resolver, configuration);

    Optional<SourcePath> configFile = inferConfig.getConfigFile(configuration);
    Optional<String> version = inferConfig.getVersion();
    Optional<SourcePath> nullsafeThirdPartySignatures =
        inferConfig.getNullsafeThirdPartySignatures(configuration);
    Boolean executeRemotely = inferConfig.executeRemotely();

    return InferPlatform.of(
        tool, version, configFile, nullsafeThirdPartySignatures, executeRemotely);
  }

  @Override
  public Iterable<BuildTarget> getParseTimeDeps(TargetConfiguration configuration) {
    ImmutableList.Builder<BuildTarget> deps = ImmutableList.builder();

    deps.addAll(toolProvider.getParseTimeDeps(configuration));

    ImmutableList.of(
            inferConfig.getConfigFile(configuration),
            inferConfig.getNullsafeThirdPartySignatures(configuration))
        .forEach(
            sourcePathOpt ->
                sourcePathOpt
                    .filter(BuildTargetSourcePath.class::isInstance)
                    .ifPresent(cf -> deps.add(((BuildTargetSourcePath) cf).getTarget())));

    return deps.build();
  }
}
