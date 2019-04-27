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
package com.facebook.buck.core.rules.platform;

import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.model.impl.DefaultTargetConfiguration;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.model.platform.PlatformResolver;
import com.facebook.buck.core.model.platform.TargetPlatformResolver;
import com.google.common.base.Preconditions;

/**
 * An implementation of {@link TargetPlatformResolver} that creates {@link Platform} from {@link
 * PlatformRule} for {@link com.facebook.buck.core.model.impl.DefaultTargetConfiguration}.
 *
 * <p>Note that the clients of this class need to make sure the queries are made with the correct
 * target configuration type.
 */
public class RuleBasedTargetPlatformResolver implements TargetPlatformResolver {

  private final PlatformResolver platformResolver;

  public RuleBasedTargetPlatformResolver(PlatformResolver platformResolver) {
    this.platformResolver = platformResolver;
  }

  @Override
  public Platform getTargetPlatform(TargetConfiguration targetConfiguration) {
    Preconditions.checkState(
        targetConfiguration instanceof DefaultTargetConfiguration,
        "Wrong target configuration type: " + targetConfiguration);

    UnconfiguredBuildTargetView buildTarget =
        ((DefaultTargetConfiguration) targetConfiguration).getTargetPlatform();

    return platformResolver.getPlatform(buildTarget);
  }
}
