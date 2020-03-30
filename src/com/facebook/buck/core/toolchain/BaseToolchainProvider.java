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

package com.facebook.buck.core.toolchain;

import com.facebook.buck.core.model.TargetConfiguration;
import java.util.Optional;

public abstract class BaseToolchainProvider implements ToolchainProvider {
  @Override
  public <T extends Toolchain> T getByName(
      String toolchainName,
      TargetConfiguration toolchainTargetConfiguration,
      Class<T> toolchainClass) {
    return toolchainClass.cast(getByName(toolchainName, toolchainTargetConfiguration));
  }

  @Override
  public <T extends Toolchain> Optional<T> getByNameIfPresent(
      String toolchainName,
      TargetConfiguration toolchainTargetConfiguration,
      Class<T> toolchainClass) {
    if (isToolchainPresent(toolchainName, toolchainTargetConfiguration)) {
      return Optional.of(getByName(toolchainName, toolchainTargetConfiguration, toolchainClass));
    } else {
      return Optional.empty();
    }
  }
}
