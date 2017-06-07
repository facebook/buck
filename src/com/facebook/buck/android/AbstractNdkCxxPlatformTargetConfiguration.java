/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;
import org.immutables.value.Value;

/** A container for all configuration settings needed to define a build target. */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractNdkCxxPlatformTargetConfiguration {

  public abstract NdkCxxPlatforms.Toolchain getToolchain();

  public abstract NdkCxxPlatforms.ToolchainTarget getToolchainTarget();

  public abstract NdkCxxPlatforms.TargetArch getTargetArch();

  public abstract NdkCxxPlatforms.TargetArchAbi getTargetArchAbi();

  public abstract String getTargetAppPlatform();

  public abstract NdkCxxPlatformCompiler getCompiler();

  public abstract ImmutableMap<NdkCxxPlatformCompiler.Type, ImmutableList<String>>
      getCompilerFlags();

  public abstract ImmutableMap<NdkCxxPlatformCompiler.Type, ImmutableList<String>>
      getAssemblerFlags();

  public abstract ImmutableMap<NdkCxxPlatformCompiler.Type, ImmutableList<String>> getLinkerFlags();

  public ImmutableList<String> getAssemblerFlags(NdkCxxPlatformCompiler.Type type) {
    return Optional.ofNullable(getAssemblerFlags().get(type)).orElse(ImmutableList.of());
  }

  public ImmutableList<String> getCompilerFlags(NdkCxxPlatformCompiler.Type type) {
    return Optional.ofNullable(getCompilerFlags().get(type)).orElse(ImmutableList.of());
  }

  public ImmutableList<String> getLinkerFlags(NdkCxxPlatformCompiler.Type type) {
    return Optional.ofNullable(getLinkerFlags().get(type)).orElse(ImmutableList.of());
  }
}
