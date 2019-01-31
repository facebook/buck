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

import com.facebook.buck.core.toolchain.ToolchainCreationContext;
import com.facebook.buck.core.toolchain.ToolchainDescriptor;
import com.facebook.buck.core.toolchain.ToolchainFactory;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.toolchain.ToolchainSupplier;
import com.facebook.buck.core.toolchain.toolprovider.impl.SystemToolProvider;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import org.pf4j.Extension;

/** Toolchains for dotnet development */
@Extension
public class DotnetToolchainSupplier implements ToolchainSupplier {

  @Override
  public Collection<ToolchainDescriptor<?>> getToolchainDescriptor() {
    return Collections.singleton(
        ToolchainDescriptor.of(
            DotnetToolchain.DEFAULT_NAME, DotnetToolchain.class, DotnetToolchainFactory.class));
  }

  /** Factory */
  public static class DotnetToolchainFactory implements ToolchainFactory<DotnetToolchain> {
    public DotnetToolchainFactory() {}

    @Override
    public Optional<DotnetToolchain> createToolchain(
        ToolchainProvider toolchainProvider, ToolchainCreationContext context) {
      DotnetBuckConfig dotnetBuckConfig = context.getBuckConfig().getView(DotnetBuckConfig.class);
      SystemToolProvider systemCsharpCompiler =
          SystemToolProvider.builder()
              .setExecutableFinder(context.getExecutableFinder())
              .setSourcePathConverter(context.getBuckConfig()::getPathSourcePath)
              .setName(Paths.get("csc"))
              .setEnvironment(context.getEnvironment())
              .build();
      return Optional.of(DotnetToolchain.of(dotnetBuckConfig, systemCsharpCompiler));
    }
  }
}
