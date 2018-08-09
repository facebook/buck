/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;

public class CxxBinaryMetadataFactory {

  private final ToolchainProvider toolchainProvider;

  public CxxBinaryMetadataFactory(ToolchainProvider toolchainProvider) {
    this.toolchainProvider = toolchainProvider;
  }

  public <U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      ActionGraphBuilder graphBuilder,
      ImmutableSortedSet<BuildTarget> deps,
      Class<U> metadataClass) {
    if (!metadataClass.isAssignableFrom(CxxCompilationDatabaseDependencies.class)
        || !buildTarget.getFlavors().contains(CxxCompilationDatabase.COMPILATION_DATABASE)) {
      return Optional.empty();
    }
    return CxxDescriptionEnhancer.createCompilationDatabaseDependencies(
            buildTarget,
            toolchainProvider
                .getByName(CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class)
                .getCxxPlatforms(),
            graphBuilder,
            deps)
        .map(metadataClass::cast);
  }
}
