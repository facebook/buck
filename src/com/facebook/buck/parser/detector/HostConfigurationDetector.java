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

package com.facebook.buck.parser.detector;

import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.util.environment.Platform;
import java.util.EnumMap;
import java.util.Optional;

/** Describes how to map host platform to host {@link TargetConfiguration}. */
public class HostConfigurationDetector {
  private final EnumMap<Platform, TargetConfiguration> hostConfigurationByHostOs;

  HostConfigurationDetector(EnumMap<Platform, TargetConfiguration> hostConfigurationByHostOs) {
    this.hostConfigurationByHostOs = hostConfigurationByHostOs.clone();
  }

  /** Do detect. */
  public Optional<TargetConfiguration> detectHostConfiguration(Platform hostOs) {
    return Optional.ofNullable(hostConfigurationByHostOs.get(hostOs));
  }
}
