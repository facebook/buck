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

package com.facebook.buck.core.model.platform.impl;

import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.model.ConfigurationForConfigurationTargets;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.model.platform.TargetPlatformResolver;

/**
 * Target platform resolver used for configuration graphs (where all configurations are {@link
 * ConfigurationForConfigurationTargets}).
 */
public class ConfigurationPlatformResolver implements TargetPlatformResolver {

  @Override
  public Platform getTargetPlatform(
      TargetConfiguration targetConfiguration, DependencyStack dependencyStack) {

    // When we create configuration targets, we use this instance of platform resolver,
    // So return some platform here.
    // This behavior is consistent with DefaultTargetPlatformResolver.
    if (targetConfiguration instanceof ConfigurationForConfigurationTargets) {
      return UnconfiguredPlatform.INSTANCE;
    }

    throw new UnsupportedOperationException(
        "attempt to resolve configuration: " + targetConfiguration);
  }
}
