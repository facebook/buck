/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.core.description.DescriptionCreationContext;
import com.facebook.buck.core.model.targetgraph.DescriptionProvider;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.InferBuckConfig;
import com.facebook.buck.toolchain.ToolchainProvider;
import java.util.Arrays;
import java.util.Collection;
import org.pf4j.Extension;

@Extension
public class CxxDescriptionsProvider implements DescriptionProvider {

  @Override
  public Collection<DescriptionWithTargetGraph<?>> getDescriptions(
      DescriptionCreationContext context) {
    ToolchainProvider toolchainProvider = context.getToolchainProvider();
    BuckConfig config = context.getBuckConfig();
    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(config);
    InferBuckConfig inferBuckConfig = new InferBuckConfig(config);

    CxxBinaryImplicitFlavors cxxBinaryImplicitFlavors =
        new CxxBinaryImplicitFlavors(toolchainProvider, cxxBuckConfig);
    CxxBinaryFactory cxxBinaryFactory =
        new CxxBinaryFactory(toolchainProvider, cxxBuckConfig, inferBuckConfig);
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
        new CxxLibraryFactory(toolchainProvider, cxxBuckConfig, inferBuckConfig);
    CxxLibraryMetadataFactory cxxLibraryMetadataFactory =
        new CxxLibraryMetadataFactory(toolchainProvider);

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
            cxxBuckConfig, toolchainProvider, context.getSandboxExecutionStrategy()),
        new CxxTestDescription(toolchainProvider, cxxBuckConfig, cxxBinaryMetadataFactory),
        new PrebuiltCxxLibraryDescription(toolchainProvider, cxxBuckConfig),
        PrebuiltCxxLibraryGroupDescription.of(),
        new CxxPrecompiledHeaderDescription());
  }
}
