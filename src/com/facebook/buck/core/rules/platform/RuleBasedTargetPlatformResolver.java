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

package com.facebook.buck.core.rules.platform;

import com.facebook.buck.core.exceptions.DependencyStack;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.RuleBasedTargetConfiguration;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.platform.Platform;
import com.facebook.buck.core.model.platform.PlatformResolver;
import com.facebook.buck.core.model.platform.TargetPlatformResolver;
import com.google.common.base.Preconditions;

/**
 * An implementation of {@link TargetPlatformResolver} that creates {@link Platform} from {@link
 * PlatformRule} for {@link RuleBasedTargetConfiguration}.
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
  public Platform getTargetPlatform(
      TargetConfiguration targetConfiguration, DependencyStack dependencyStack) {
    Preconditions.checkState(
        targetConfiguration instanceof RuleBasedTargetConfiguration,
        "Wrong target configuration type: " + targetConfiguration);

    BuildTarget buildTarget =
        ((RuleBasedTargetConfiguration) targetConfiguration).getTargetPlatform();

    return platformResolver.getPlatform(buildTarget, dependencyStack);
  }
}
