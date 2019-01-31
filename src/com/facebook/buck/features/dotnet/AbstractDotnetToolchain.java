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
package com.facebook.buck.features.dotnet;

import com.facebook.buck.core.toolchain.Toolchain;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.toolchain.toolprovider.impl.SystemToolProvider;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import org.immutables.value.Value;

/** Toolchain for dotnet */
@Value.Immutable(copy = false, builder = false)
@BuckStyleImmutable
abstract class AbstractDotnetToolchain implements Toolchain {
  static final String DEFAULT_NAME = "dotnet-toolchain";

  @Value.Parameter
  abstract DotnetBuckConfig getDotnetBuckConfig();

  @Value.Parameter
  abstract SystemToolProvider getSystemCsharpCompiler();

  public ToolProvider getCsharpCompiler() {
    return getDotnetBuckConfig().getCsharpCompiler().orElse(getSystemCsharpCompiler());
  }

  @Override
  public String getName() {
    return DEFAULT_NAME;
  }
}
