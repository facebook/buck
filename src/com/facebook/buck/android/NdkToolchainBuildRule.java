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
package com.facebook.buck.android;

import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatform;
import com.facebook.buck.android.toolchain.ndk.NdkCxxRuntime;
import com.facebook.buck.android.toolchain.ndk.ProvidesNdkCxxPlatform;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.NoopBuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.cxx.toolchain.ProvidesCxxPlatform;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This {@link BuildRule} is just a placeholder to hold the created {@link NdkCxxPlatform}. It's a
 * {@link NoopBuildRule} with no build steps or outputs.
 */
public class NdkToolchainBuildRule extends NoopBuildRule implements ProvidesNdkCxxPlatform {
  private final ProvidesCxxPlatform cxxPlatformRule;
  private final Optional<SourcePath> sharedRuntimePath;
  private final NdkCxxRuntime cxxRuntime;
  private final Tool objdump;

  private final Map<Flavor, NdkCxxPlatform> resolvedCache;

  public NdkToolchainBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ProvidesCxxPlatform cxxPlatformRule,
      Optional<SourcePath> sharedRuntimePath,
      NdkCxxRuntime cxxRuntime,
      Tool objdump) {
    super(buildTarget, projectFilesystem);

    this.cxxPlatformRule = cxxPlatformRule;
    this.sharedRuntimePath = sharedRuntimePath;
    this.cxxRuntime = cxxRuntime;
    this.objdump = objdump;
    this.resolvedCache = new ConcurrentHashMap<>();
  }

  @Override
  public NdkCxxPlatform getNdkCxxPlatform(Flavor flavor) {
    return resolvedCache.computeIfAbsent(
        flavor,
        ignored ->
            NdkCxxPlatform.builder()
                .setCxxPlatform(cxxPlatformRule.getPlatformWithFlavor(flavor))
                .setCxxSharedRuntimePath(sharedRuntimePath)
                .setCxxRuntime(cxxRuntime)
                .setObjdump(objdump)
                .build());
  }
}
