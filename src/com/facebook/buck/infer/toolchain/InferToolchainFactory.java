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
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.ToolchainFactory;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.io.ExecutableFinder;
import java.util.Optional;

/** Constructs {@link InferToolchain}s. */
public class InferToolchainFactory implements ToolchainFactory<InferToolchain> {
  @Override
  public Optional<InferToolchain> createToolchain(
      ToolchainProvider toolchainProvider,
      ToolchainCreationContext context,
      TargetConfiguration toolchainTargetConfiguration) {
    BuckConfig config = context.getBuckConfig();
    ExecutableFinder executableFinder = context.getExecutableFinder();

    return InferPlatformFactory.getBasedOnConfigAndPath(config, executableFinder)
        .map(ImmutableInferToolchain::of);
  }
}
