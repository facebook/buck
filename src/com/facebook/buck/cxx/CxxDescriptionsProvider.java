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

package com.facebook.buck.cxx;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.description.Description;
import com.facebook.buck.core.description.DescriptionCreationContext;
import com.facebook.buck.core.model.targetgraph.DescriptionProvider;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.InferBuckConfig;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.remoteexecution.config.RemoteExecutionConfig;
import com.facebook.buck.sandbox.SandboxConfig;
import com.facebook.buck.support.cli.config.CliConfig;
import java.util.Arrays;
import java.util.Collection;
import org.pf4j.Extension;

@Extension
public class CxxDescriptionsProvider implements DescriptionProvider {

  @Override
  public Collection<Description<?>> getDescriptions(DescriptionCreationContext context) {
    ToolchainProvider toolchainProvider = context.getToolchainProvider();
    BuckConfig buckConfig = context.getBuckConfig();
    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(buckConfig);
    InferBuckConfig inferBuckConfig = new InferBuckConfig(buckConfig);
    DownwardApiConfig downwardApiConfig = buckConfig.getView(DownwardApiConfig.class);
    CliConfig cliConfig = buckConfig.getView(CliConfig.class);
    SandboxConfig sandboxConfig = buckConfig.getView(SandboxConfig.class);
    RemoteExecutionConfig reConfig = buckConfig.getView(RemoteExecutionConfig.class);

    CxxBinaryImplicitFlavors cxxBinaryImplicitFlavors =
        new CxxBinaryImplicitFlavors(toolchainProvider, cxxBuckConfig);
    CxxBinaryFactory cxxBinaryFactory =
        new CxxBinaryFactory(toolchainProvider, cxxBuckConfig, downwardApiConfig, inferBuckConfig);
    CxxBinaryMetadataFactory cxxBinaryMetadataFactory =
        new CxxBinaryMetadataFactory(toolchainProvider);
    CxxBinaryFlavored cxxBinaryFlavored = new CxxBinaryFlavored(toolchainProvider, cxxBuckConfig);

    CxxBinaryDescription cxxBinaryDescription =
        new CxxBinaryDescription(
            toolchainProvider,
            cxxBinaryImplicitFlavors,
            cxxBinaryFactory,
            cxxBinaryMetadataFactory,
            cxxBinaryFlavored);

    CxxLibraryImplicitFlavors cxxLibraryImplicitFlavors =
        new CxxLibraryImplicitFlavors(toolchainProvider, cxxBuckConfig);
    CxxLibraryFlavored cxxLibraryFlavored =
        new CxxLibraryFlavored(toolchainProvider, cxxBuckConfig);
    CxxLibraryFactory cxxLibraryFactory =
        new CxxLibraryFactory(toolchainProvider, cxxBuckConfig, inferBuckConfig, downwardApiConfig);
    CxxLibraryMetadataFactory cxxLibraryMetadataFactory =
        new CxxLibraryMetadataFactory(
            toolchainProvider, buckConfig.getFilesystem(), cxxBuckConfig, downwardApiConfig);

    CxxLibraryDescription cxxLibraryDescription =
        new CxxLibraryDescription(
            cxxLibraryImplicitFlavors,
            cxxLibraryFlavored,
            cxxLibraryFactory,
            cxxLibraryMetadataFactory);

    return Arrays.asList(
        cxxBinaryDescription,
        cxxLibraryDescription,
        new CxxGenruleDescription(
            toolchainProvider,
            sandboxConfig,
            reConfig,
            downwardApiConfig,
            cliConfig,
            cxxBuckConfig,
            context.getSandboxExecutionStrategy()),
        new CxxToolchainDescription(downwardApiConfig),
        new CxxTestDescription(
            toolchainProvider, cxxBuckConfig, downwardApiConfig, cxxBinaryMetadataFactory),
        new PrebuiltCxxLibraryDescription(toolchainProvider, cxxBuckConfig, downwardApiConfig),
        new PrebuiltCxxLibraryGroupDescription(),
        new CxxPrecompiledHeaderDescription());
  }
}
