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

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.toolchain.tool.impl.HashedFileTool;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.toolchain.toolprovider.impl.ConstantToolProvider;
import com.facebook.buck.infer.InferConfig;
import com.facebook.buck.infer.InferPlatform;
import com.facebook.buck.infer.UnresolvedInferPlatform;
import com.facebook.buck.io.ExecutableFinder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/** Utility class to construct {@link InferPlatform}. */
public class InferPlatformFactory {
  private static final Path DEFAULT_INFER_TOOL = Paths.get("infer");

  /** Tries to construct {@link InferPlatform} based on buck config or otherwise env vars. */
  public static Optional<UnresolvedInferPlatform> getBasedOnConfigAndPath(
      BuckConfig buckConfig, ExecutableFinder executableFinder) {
    InferConfig inferConfig = InferConfig.of(buckConfig);
    Optional<ToolProvider> toolProviderOpt;

    Optional<ToolProvider> binaryProvider = inferConfig.getBinary();
    if (binaryProvider.isPresent()) {
      toolProviderOpt = binaryProvider;
    } else {
      Optional<ToolProvider> distProvider = inferConfig.getDist();
      if (distProvider.isPresent()) {
        toolProviderOpt = distProvider;
      } else {
        toolProviderOpt = getBinaryFromEnv(inferConfig, executableFinder);
      }
    }

    return toolProviderOpt.map(
        (toolProvider) -> new DefaultUnresolvedInferPlatform(inferConfig, toolProvider));
  }

  private static Optional<ToolProvider> getBinaryFromEnv(
      InferConfig inferConfig, ExecutableFinder executableFinder) {
    return executableFinder
        .getOptionalExecutable(DEFAULT_INFER_TOOL, inferConfig.getDelegate().getEnvironment())
        .map(path -> new HashedFileTool(() -> inferConfig.getDelegate().getPathSourcePath(path)))
        .map(ConstantToolProvider::new);
  }
}
