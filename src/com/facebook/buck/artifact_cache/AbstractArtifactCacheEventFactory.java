/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.artifact_cache;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.EmptyTargetConfiguration;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.TargetConfigurationSerializer;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import java.util.function.Function;
import javax.annotation.Nullable;

public abstract class AbstractArtifactCacheEventFactory implements ArtifactCacheEventFactory {
  private static final String TARGET_KEY = "TARGET";
  private static final String CONFIGURATION_KEY = "CONFIGURATION";

  private final Function<String, UnconfiguredBuildTargetView> unconfiguredBuildTargetFactory;
  private final TargetConfigurationSerializer targetConfigurationSerializer;

  protected AbstractArtifactCacheEventFactory(
      Function<String, UnconfiguredBuildTargetView> unconfiguredBuildTargetFactory,
      TargetConfigurationSerializer targetConfigurationSerializer) {
    this.unconfiguredBuildTargetFactory = unconfiguredBuildTargetFactory;
    this.targetConfigurationSerializer = targetConfigurationSerializer;
  }

  protected final Optional<BuildTarget> getTarget(ImmutableMap<String, String> metadata) {
    return getTarget(unconfiguredBuildTargetFactory, targetConfigurationSerializer, metadata);
  }

  public static Optional<BuildTarget> getTarget(
      Function<String, UnconfiguredBuildTargetView> unconfiguredBuildTargetFactory,
      TargetConfigurationSerializer targetConfigurationSerializer,
      ImmutableMap<String, String> metadata) {
    return metadata.containsKey(TARGET_KEY)
        ? getTarget(
            unconfiguredBuildTargetFactory,
            metadata.get(TARGET_KEY),
            getTargetConfigurationFromMetadata(targetConfigurationSerializer, metadata))
        : Optional.empty();
  }

  private static TargetConfiguration getTargetConfigurationFromMetadata(
      TargetConfigurationSerializer targetConfigurationSerializer,
      ImmutableMap<String, String> metadata) {
    String configuration = metadata.get(CONFIGURATION_KEY);
    if (Strings.isNullOrEmpty(configuration)) {
      return EmptyTargetConfiguration.INSTANCE;
    }
    return targetConfigurationSerializer.deserialize(configuration);
  }

  public static Optional<BuildTarget> getTarget(
      Function<String, UnconfiguredBuildTargetView> unconfiguredBuildTargetFactory,
      @Nullable String target,
      TargetConfiguration targetConfiguration) {
    if (target == null) {
      return Optional.empty();
    } else {
      return Optional.of(
          unconfiguredBuildTargetFactory.apply(target).configure(targetConfiguration));
    }
  }
}
