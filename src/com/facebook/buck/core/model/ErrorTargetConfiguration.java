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

/** Stub configuration which can be used when configuration is not actually needed. */
public class ErrorTargetConfiguration extends TargetConfiguration {
  public static final ErrorTargetConfiguration INSTANCE = new ErrorTargetConfiguration();

  private final int hashCode = Objects.hash(ErrorTargetConfiguration.class.getName());

  private ErrorTargetConfiguration() {}

  @Override
  public int hashCode() {
    return hashCode;
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof ErrorTargetConfiguration;
  }

  @JsonIgnore
  @Override
  public Optional<BuildTarget> getConfigurationTarget() {
    return Optional.empty();
  }

  @Override
  public String toString() {
    return ErrorTargetConfiguration.class.getSimpleName();
  }
}
