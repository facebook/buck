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

package com.facebook.buck.features.dotnet;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.toolchain.toolprovider.ToolProvider;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.rules.tool.config.ToolConfig;
import java.util.Optional;

/** Config to get toolchains for dotnet rules */
@BuckStyleValue
public abstract class DotnetBuckConfig implements ConfigView<BuckConfig> {
  private static final String SECTION = "dotnet";
  private static final String CSC = "csc";

  @Override
  public abstract BuckConfig getDelegate();

  public static DotnetBuckConfig of(BuckConfig delegate) {
    return ImmutableDotnetBuckConfig.of(delegate);
  }

  public Optional<ToolProvider> getCsharpCompiler() {
    return getDelegate().getView(ToolConfig.class).getToolProvider(SECTION, CSC);
  }
}
