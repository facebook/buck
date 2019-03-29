/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.sourcepath.SourcePath;
import java.util.Optional;

public class ProGuardConfig {
  private final String SECTION = "tools";
  private final String PROGUARD_CONFIG = "proguard";

  private final BuckConfig delegate;

  public ProGuardConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  /**
   * @return The path to the proguard.jar file that is overridden by the current project. If not
   *     specified, the Android platform proguard.jar will be used.
   */
  public Optional<SourcePath> getProguardJarOverride(TargetConfiguration targetConfiguration) {
    return delegate.getSourcePath(SECTION, PROGUARD_CONFIG, targetConfiguration);
  }

  public Optional<BuildTarget> getProguardTarget(TargetConfiguration targetConfiguration) {
    return delegate.getMaybeBuildTarget(SECTION, PROGUARD_CONFIG, targetConfiguration);
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
