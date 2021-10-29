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

package com.facebook.buck.features.apple.projectV2;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleDependenciesCache;
import com.facebook.buck.apple.XCodeDescriptions;
import com.facebook.buck.apple.XCodeDescriptionsFactory;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import org.hamcrest.Matchers;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

public class HeaderSearchPathsTest {
  private HeaderSearchPaths headerSearchPaths;
  private ProjectFilesystem projectFilesystem;

  @Before
  public void setUp() throws IOException {
    Assume.assumeThat(Platform.detect(), Matchers.not(Platform.WINDOWS));

    Cells cells = (new TestCellBuilder()).build();
    TargetGraph targetGraph = TargetGraphFactory.newInstance();

    XCodeDescriptions xcodeDescriptions =
        XCodeDescriptionsFactory.create(BuckPluginManagerFactory.createPluginManager());
    AppleDependenciesCache dependenciesCache = new AppleDependenciesCache(targetGraph);
    ProjectGenerationStateCache projGenerationStateCache = new ProjectGenerationStateCache();
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    Cell projectCell = cells.getRootCell();

    ActionGraphBuilder actionGraphBuilder =
        AppleProjectHelper.getActionGraphBuilderNodeFunction(targetGraph);
    SourcePathResolverAdapter defaultPathResolver =
        AppleProjectHelper.defaultSourcePathResolverAdapter(actionGraphBuilder);

    ProjectSourcePathResolver projectSourcePathResolver =
        new ProjectSourcePathResolver(
            cells.getRootCell(), defaultPathResolver, targetGraph, actionGraphBuilder);

    BuckConfig config = AppleProjectHelper.createDefaultBuckConfig(projectFilesystem);

    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(config);
    AppleConfig appleConfig = config.getView(AppleConfig.class);
    SwiftBuckConfig swiftBuckConfig = new SwiftBuckConfig(config);

    PathRelativizer pathRelativizer = AppleProjectHelper.defaultPathRelativizer("test-out");

    SwiftAttributeParser swiftAttributeParser =
        new SwiftAttributeParser(swiftBuckConfig, projGenerationStateCache, projectFilesystem);

    this.projectFilesystem = projectFilesystem;
    headerSearchPaths =
        new HeaderSearchPaths(
            cells,
            projectCell,
            cxxBuckConfig,
            CxxPlatformUtils.DEFAULT_UNRESOLVED_PLATFORM,
            TestRuleKeyConfigurationFactory.create(),
            xcodeDescriptions,
            targetGraph,
            actionGraphBuilder,
            FakeBuildContext.NOOP_CONTEXT,
            dependenciesCache,
            projectSourcePathResolver,
            pathRelativizer,
            swiftAttributeParser,
            appleConfig);
  }

  @Test
  public void testParseSwiftEmptyArgs() {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    headerSearchPaths.parseCommandAndAddToSwiftIncludeFlags(ImmutableList.of(), builder);
    assertEquals(ImmutableList.of(), builder.build());
  }

  @Test
  public void testParseSwiftArgsOmitNonIncludeArgs() {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    headerSearchPaths.parseCommandAndAddToSwiftIncludeFlags(
        ImmutableList.of(
            "-emit-module",
            "-emit-module-path",
            "buck-out/gen/ce9b6f2e/Libraries/DepA#apple-swift-compile,iphonesimulator-x86_64/DepA.swiftmodule",
            "-emit-objc-header-path",
            "buck-out/gen/ce9b6f2e/Libraries/DepA#apple-swift-compile,iphonesimulator-x86_64/DepA-Swift.h",
            "-emit-object"),
        builder);
    assertEquals(ImmutableList.of(), builder.build());
  }

  @Test
  public void testParseSwiftArgsClangFlags() {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    String includePath =
        "buck-out/gen/ce9b6f2e/Libraries/DepA#iphonesimulator-x86_64,swift-compile";
    headerSearchPaths.parseCommandAndAddToSwiftIncludeFlags(
        ImmutableList.of("-Xcc", "-I", "-Xcc", includePath), builder);
    assertEquals(
        ImmutableList.of("-Xcc", "-I", "-Xcc", projectFilesystem.resolve(includePath).toString()),
        builder.build());
  }

  @Test
  public void testParseSwiftArgsIncludeFlags() {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    String includePath =
        "buck-out/gen/ce9b6f2e/Libraries/DepA#iphonesimulator-x86_64,swift-compile";
    headerSearchPaths.parseCommandAndAddToSwiftIncludeFlags(
        ImmutableList.of("-I", includePath), builder);
    assertEquals(
        ImmutableList.of("-I", projectFilesystem.resolve(includePath).toString()), builder.build());
  }

  @Test
  public void testParseSwiftArgsModuleName() {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    headerSearchPaths.parseCommandAndAddToSwiftIncludeFlags(
        ImmutableList.of("-module-name", "DepA"), builder);
    assertEquals(ImmutableList.of("-module-name", "DepA"), builder.build());
  }

  @Test
  public void testParseSwiftArgsWnoFlags() {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    headerSearchPaths.parseCommandAndAddToSwiftIncludeFlags(
        ImmutableList.of("-Xcc", "-Wno-unknown-warning-option"), builder);
    assertEquals(ImmutableList.of("-Xcc", "-Wno-unknown-warning-option"), builder.build());
  }

  @Test
  public void testParseCxxArgsModuleName() {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    headerSearchPaths.parseCommandAndAddToIncludeFlags(
        ImmutableList.of(
            "-fmodule-name=fake_module",
            "-fmodules",
            "-fcxx-modules",
            "-fmodules-cache-path=/var/tmp/buck-module-cache"),
        builder);
    assertEquals(
        ImmutableList.of(
            "-fmodule-name=fake_module",
            "-fmodules",
            "-fcxx-modules",
            "-fmodules-cache-path=/var/tmp/buck-module-cache"),
        builder.build());
  }

  @Test
  public void testParseCxxArgsIncludePaths() {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    String includePath =
        "buck-out/gen/ce9b6f2e/Libraries/DepA#iphonesimulator-x86_64,swift-compile";
    headerSearchPaths.parseCommandAndAddToIncludeFlags(
        ImmutableList.of("-I", includePath), builder);
    assertEquals(
        ImmutableList.of("-I", projectFilesystem.resolve(includePath).toString()), builder.build());
  }

  @Test
  public void testParseCxxArgsWnoFlags() {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    headerSearchPaths.parseCommandAndAddToIncludeFlags(
        ImmutableList.of("-Wno-unknown-warning-option"), builder);
    assertEquals(ImmutableList.of("-Wno-unknown-warning-option"), builder.build());
  }
}
