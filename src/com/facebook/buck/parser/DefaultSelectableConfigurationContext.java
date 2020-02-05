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

package com.facebook.buck.parser;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.platform.TargetPlatformResolver;
import com.facebook.buck.core.rules.configsetting.ConfigSettingSelectableConfigurationContext;
import com.facebook.buck.core.select.SelectableConfigurationContext;
import com.facebook.buck.core.util.immutables.BuckStyleValue;

/**
 * An implementation of {@link com.facebook.buck.core.select.SelectableConfigurationContext} that is
 * used in parser implementation.
 */
@BuckStyleValue
public abstract class DefaultSelectableConfigurationContext
    implements ConfigSettingSelectableConfigurationContext {

  @Override
  public abstract BuckConfig getBuckConfig();

  @Override
  public abstract TargetConfiguration getTargetConfiguration();

  @Override
  public abstract TargetPlatformResolver getPlatformProvider();

  @Override
  public SelectableConfigurationContext withTargetConfiguration(TargetConfiguration value) {
    return ImmutableDefaultSelectableConfigurationContext.of(
        getBuckConfig(), value, getPlatformProvider());
  }

  public static DefaultSelectableConfigurationContext of(
      BuckConfig buckConfig,
      TargetConfiguration targetConfiguration,
      TargetPlatformResolver platformProvider) {
    return ImmutableDefaultSelectableConfigurationContext.of(
        buckConfig, targetConfiguration, platformProvider);
  }
}
