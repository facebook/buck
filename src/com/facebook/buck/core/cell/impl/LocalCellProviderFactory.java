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

package com.facebook.buck.core.cell.impl;

import com.facebook.buck.core.cell.CellConfig;
import com.facebook.buck.core.cell.CellProvider;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.toolchain.ToolchainProviderFactory;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;

/** Creates a {@link CellProvider} to be used in a local (non-distributed) build. */
public class LocalCellProviderFactory {

  /** Create a cell provider at a given root. */
  public static CellProvider create(
      ProjectFilesystem rootFilesystem,
      BuckConfig rootConfig,
      CellConfig rootCellConfigOverrides,
      DefaultCellPathResolver rootCellCellPathResolver,
      ToolchainProviderFactory toolchainProviderFactory,
      ProjectFilesystemFactory projectFilesystemFactory,
      UnconfiguredBuildTargetViewFactory unconfiguredBuildTargetFactory) {

    return new CellProviderImpl(
        rootFilesystem,
        rootConfig,
        rootCellConfigOverrides,
        rootCellCellPathResolver,
        toolchainProviderFactory,
        projectFilesystemFactory,
        unconfiguredBuildTargetFactory);
  }
}
