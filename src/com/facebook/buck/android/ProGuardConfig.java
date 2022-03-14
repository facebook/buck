/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.android;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

public class ProGuardConfig {
  private final String SECTION = "tools";
  private final String PROGUARD_JAR = "proguard";
  private final String PROGUARD_CONFIG = "proguard_config";
  private final String OPTIMIZED_PROGUARD_CONFIG = "optimized_proguard_config";

  private final BuckConfig delegate;

  public ProGuardConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  /**
   * @return The path to the proguard.jar file that is overridden by the current project. If not
   *     specified, the Android platform proguard.jar will be used.
   */
  public Optional<SourcePath> getProguardJarOverride(TargetConfiguration targetConfiguration) {
    return delegate.getSourcePath(SECTION, PROGUARD_JAR, targetConfiguration);
  }

  public ImmutableList<BuildTarget> getProguardTargets(TargetConfiguration targetConfiguration) {
    ImmutableList.Builder<BuildTarget> buildTargets = ImmutableList.builder();
    ImmutableList<String> configStrings =
        ImmutableList.of(PROGUARD_JAR, PROGUARD_CONFIG, OPTIMIZED_PROGUARD_CONFIG);
    for (String configString : configStrings) {
      delegate
          .getMaybeBuildTarget(SECTION, configString, targetConfiguration)
          .ifPresent(buildTargets::add);
    }

    return buildTargets.build();
  }

  public Optional<SourcePath> getProguardConfigOverride(TargetConfiguration targetConfiguration) {
    return delegate.getSourcePath(SECTION, PROGUARD_CONFIG, targetConfiguration);
  }

  public Optional<SourcePath> getOptimizedProguardConfigOverride(
      TargetConfiguration targetConfiguration) {
    return delegate.getSourcePath(SECTION, OPTIMIZED_PROGUARD_CONFIG, targetConfiguration);
  }

  /** @return The upper heap size limit for Proguard if specified. */
  public String getProguardMaxHeapSize() {
    return delegate.getValue(SECTION, "proguard-max-heap-size").orElse("1024M");
  }

  /** @return The agentpath for profiling if specified. */
  public Optional<String> getProguardAgentPath() {
    return delegate.getValue(SECTION, "proguard-agentpath");
  }
}
