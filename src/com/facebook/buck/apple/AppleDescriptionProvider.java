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

package com.facebook.buck.apple;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.core.description.DescriptionCreationContext;
import com.facebook.buck.core.model.targetgraph.DescriptionProvider;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.cxx.CxxBinaryFactory;
import com.facebook.buck.cxx.CxxBinaryFlavored;
import com.facebook.buck.cxx.CxxBinaryImplicitFlavors;
import com.facebook.buck.cxx.CxxBinaryMetadataFactory;
import com.facebook.buck.cxx.CxxLibraryFactory;
import com.facebook.buck.cxx.CxxLibraryFlavored;
import com.facebook.buck.cxx.CxxLibraryImplicitFlavors;
import com.facebook.buck.cxx.CxxLibraryMetadataFactory;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.InferBuckConfig;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.swift.SwiftLibraryDescription;
import com.facebook.buck.toolchain.ToolchainProvider;
import java.util.Arrays;
import java.util.Collection;
import org.pf4j.Extension;

@Extension
public class AppleDescriptionProvider implements DescriptionProvider {
  @Override
  public Collection<DescriptionWithTargetGraph<?>> getDescriptions(
      DescriptionCreationContext context) {
    ToolchainProvider toolchainProvider = context.getToolchainProvider();
    BuckConfig config = context.getBuckConfig();
    SwiftBuckConfig swiftBuckConfig = new SwiftBuckConfig(config);
    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(config);
    InferBuckConfig inferBuckConfig = new InferBuckConfig(config);
    AppleConfig appleConfig = config.getView(AppleConfig.class);

    CxxBinaryImplicitFlavors cxxBinaryImplicitFlavors =
        new CxxBinaryImplicitFlavors(toolchainProvider, cxxBuckConfig);
    CxxBinaryFactory cxxBinaryFactory =
        new CxxBinaryFactory(toolchainProvider, cxxBuckConfig, inferBuckConfig);
    CxxBinaryMetadataFactory cxxBinaryMetadataFactory =
        new CxxBinaryMetadataFactory(toolchainProvider);
    CxxBinaryFlavored cxxBinaryFlavored = new CxxBinaryFlavored(toolchainProvider, cxxBuckConfig);

    CxxLibraryImplicitFlavors cxxLibraryImplicitFlavors =
        new CxxLibraryImplicitFlavors(toolchainProvider, cxxBuckConfig);
    CxxLibraryFlavored cxxLibraryFlavored =
        new CxxLibraryFlavored(toolchainProvider, cxxBuckConfig);
    CxxLibraryFactory cxxLibraryFactory =
        new CxxLibraryFactory(toolchainProvider, cxxBuckConfig, inferBuckConfig);
    CxxLibraryMetadataFactory cxxLibraryMetadataFactory =
        new CxxLibraryMetadataFactory(toolchainProvider);

    SwiftLibraryDescription swiftLibraryDescription =
        new SwiftLibraryDescription(toolchainProvider, cxxBuckConfig, swiftBuckConfig);

    AppleLibraryDescription appleLibraryDescription =
        new AppleLibraryDescription(
            toolchainProvider,
            swiftLibraryDescription,
            appleConfig,
            swiftBuckConfig,
            cxxLibraryImplicitFlavors,
            cxxLibraryFlavored,
            cxxLibraryFactory,
            cxxLibraryMetadataFactory);

    AppleBinaryDescription appleBinaryDescription =
        new AppleBinaryDescription(
            toolchainProvider,
            swiftLibraryDescription,
            appleConfig,
            cxxBinaryImplicitFlavors,
            cxxBinaryFactory,
            cxxBinaryMetadataFactory,
            cxxBinaryFlavored);

    return Arrays.asList(
        new AppleAssetCatalogDescription(),
        new AppleResourceDescription(),
        new CoreDataModelDescription(),
        new XcodePrebuildScriptDescription(),
        new XcodePostbuildScriptDescription(),
        appleLibraryDescription,
        new PrebuiltAppleFrameworkDescription(toolchainProvider, cxxBuckConfig),
        appleBinaryDescription,
        new ApplePackageDescription(
            toolchainProvider, context.getSandboxExecutionStrategy(), appleConfig),
        new AppleBundleDescription(
            toolchainProvider, appleBinaryDescription, appleLibraryDescription, appleConfig),
        new AppleTestDescription(toolchainProvider, appleConfig, appleLibraryDescription),
        new SceneKitAssetsDescription());
  }
}
