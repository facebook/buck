/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.infer.toolchain;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.HashedFileTool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.toolchain.toolprovider.impl.ConstantToolProvider;
import com.facebook.buck.infer.ImmutableInferConfig;
import com.facebook.buck.infer.ImmutableInferPlatform;
import com.facebook.buck.infer.InferConfig;
import com.facebook.buck.infer.InferPlatform;
import com.facebook.buck.infer.UnresolvedInferPlatform;
import com.facebook.buck.io.ExecutableFinder;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/** Utility class to construct {@link InferPlatform}. */
public class InferPlatformFactory {
  private static final Path DEFAULT_INFER_TOOL = Paths.get("infer");

  /** Tries to construct {@link InferPlatform} based on buck config or otherwise env vars. */
  public static Optional<UnresolvedInferPlatform> getBasedOnConfigAndPath(
      BuckConfig buckConfig, ExecutableFinder executableFinder) {
    InferConfig inferConfig = new ImmutableInferConfig(buckConfig);

    Optional<ToolProvider> fromConfig = getBinaryFromConfig(inferConfig);
    Optional<ToolProvider> toolProviderOpt;

    if (fromConfig.isPresent()) {
      toolProviderOpt = fromConfig;
    } else {
      toolProviderOpt = getBinaryFromEnv(buckConfig, executableFinder);
    }

    return toolProviderOpt.map(
        provider ->
            new UnresolvedInferPlatform() {
              private final InferConfig config = inferConfig;
              private final ToolProvider toolProvider = provider;

              @Override
              public InferPlatform resolve(
                  BuildRuleResolver resolver, TargetConfiguration targetConfiguration) {
                Tool tool = provider.resolve(resolver, targetConfiguration);

                Optional<SourcePath> configFile = config.getConfigFile(targetConfiguration);
                Optional<String> version = config.getVersion();

                return new ImmutableInferPlatform(tool, version, configFile);
              }

              @Override
              public Iterable<BuildTarget> getParseTimeDeps(
                  TargetConfiguration targetConfiguration) {
                ImmutableList.Builder<BuildTarget> deps = ImmutableList.builder();

                deps.addAll(toolProvider.getParseTimeDeps(targetConfiguration));

                inferConfig
                    .getConfigFile(targetConfiguration)
                    .filter(BuildTargetSourcePath.class::isInstance)
                    .ifPresent(cf -> deps.add(((BuildTargetSourcePath) cf).getTarget()));

                return deps.build();
              }
            });
  }

  private static Optional<ToolProvider> getBinaryFromConfig(InferConfig inferConfig) {
    return inferConfig.getBinary();
  }

  private static Optional<ToolProvider> getBinaryFromEnv(
      BuckConfig buckConfig, ExecutableFinder executableFinder) {
    return executableFinder
        .getOptionalExecutable(DEFAULT_INFER_TOOL, buckConfig.getEnvironment())
        .map(path -> new HashedFileTool(() -> buckConfig.getPathSourcePath(path)))
        .map(ConstantToolProvider::new);
  }
}
