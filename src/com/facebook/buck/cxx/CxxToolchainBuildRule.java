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
package com.facebook.buck.cxx;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.NoopBuildRule;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.ProvidesCxxPlatform;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This {@link BuildRule} is just a placeholder to hold the created {@link CxxPlatform}. It's a
 * {@link NoopBuildRule} with no build steps or outputs.
 */
class CxxToolchainBuildRule extends NoopBuildRule implements ProvidesCxxPlatform {
  private final CxxPlatform.Builder platformBuilder;
  private final Map<Flavor, CxxPlatform> cache;

  public CxxToolchainBuildRule(
      BuildTarget buildTarget,
      BuildRuleCreationContextWithTargetGraph context,
      CxxPlatform.Builder platformBuilder) {
    super(buildTarget, context.getProjectFilesystem());
    this.platformBuilder = platformBuilder;
    this.cache = new ConcurrentHashMap<>();
  }

  @Override
  public CxxPlatform getPlatformWithFlavor(Flavor flavor) {
    return cache.computeIfAbsent(
        flavor,
        ignored -> {
          synchronized (platformBuilder) {
            // TODO(cjhopman): This can be removed when we no longer use flavors to pass around
            // configuration.
            platformBuilder.setFlavor(flavor);
            return platformBuilder.build();
          }
        });
  }
}
