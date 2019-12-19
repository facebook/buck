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

package com.facebook.buck.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Objects;
import java.util.Optional;

/**
 * Configuration that resolves to unconfigured platform, which results in error on any operations
 * with platforms (like {@code compatible_with} or {@code select()}
 */
public class UnconfiguredTargetConfiguration extends TargetConfiguration {
  public static final UnconfiguredTargetConfiguration INSTANCE =
      new UnconfiguredTargetConfiguration();

  private final int hashCode = Objects.hash(UnconfiguredTargetConfiguration.class.getName());

  private UnconfiguredTargetConfiguration() {}

  @Override
  public int hashCode() {
    return hashCode;
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof UnconfiguredTargetConfiguration;
  }

  @JsonIgnore
  @Override
  public Optional<BuildTarget> getConfigurationTarget() {
    return Optional.empty();
  }

  private static final String TO_STRING = UnconfiguredTargetConfiguration.class.getSimpleName();

  @Override
  public String toString() {
    return TO_STRING;
  }
}
