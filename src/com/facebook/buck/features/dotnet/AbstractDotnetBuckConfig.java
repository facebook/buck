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

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.rules.tool.config.ToolConfig;
import java.util.Optional;
import org.immutables.value.Value;

/** Config to get toolchains for dotnet rules */
@BuckStyleImmutable
@Value.Immutable(builder = false, copy = false)
public abstract class AbstractDotnetBuckConfig implements ConfigView<BuckConfig> {
  private static final String SECTION = "dotnet";
  private static final String CSC = "csc";

  @Override
  @Value.Parameter
  public abstract BuckConfig getDelegate();

  @Value.Lazy
  public Optional<ToolProvider> getCsharpCompiler() {
    return getDelegate().getView(ToolConfig.class).getToolProvider(SECTION, CSC);
  }
}
