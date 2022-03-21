/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.swift;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import com.facebook.buck.apple.common.AppleCompilerTargetTriple;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.toolchain.tool.impl.VersionedTool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.swift.toolchain.ExplicitModuleOutput;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Optional;
import org.junit.Test;

public class SwiftSdkDependenciesTest {
  @Test
  public void testLoadSdkDependenciesJson() throws HumanReadableException {
    ProjectFilesystem fakeFilesystem = new FakeProjectFilesystem();
    Path testDataPath = TestDataHelper.getTestDataScenario(this, "swift_sdk_dependencies");
    Path simulatorDeps = testDataPath.resolve("iphonesimulator_15.2_deps.json");
    Tool swiftc = VersionedTool.of("foo", FakeSourcePath.of("swiftc"), "1.0");
    AppleCompilerTargetTriple triple =
        AppleCompilerTargetTriple.of(
            "x86_64", "apple", "ios", Optional.of("13.0"), Optional.empty());

    SwiftSdkDependencies sdkDependencies =
        new SwiftSdkDependencies(
            new TestActionGraphBuilder(TargetGraph.EMPTY),
            fakeFilesystem,
            simulatorDeps.toString(),
            swiftc,
            ImmutableList.of(),
            triple,
            PathSourcePath.of(fakeFilesystem, Paths.get("some/sdk/path")),
            PathSourcePath.of(fakeFilesystem, Paths.get("some/platform/path")),
            PathSourcePath.of(fakeFilesystem, Paths.get("some/resource/dir")));

    SwiftSdkDependencies.SwiftModule module = sdkDependencies.getSwiftModule("Foundation");
    assertThat(
        module.getSwiftDependencies(),
        equalTo(
            ImmutableList.of(
                "Combine",
                "CoreFoundation",
                "CoreGraphics",
                "Darwin",
                "Dispatch",
                "ObjectiveC",
                "Swift",
                "_Concurrency")));
  }

  @Test
  public void testGetSwiftmoduleDependencyPaths() throws HumanReadableException {
    ActionGraphBuilder actionGraphBuilder = new TestActionGraphBuilder(TargetGraph.EMPTY);
    ProjectFilesystem fakeFilesystem = new FakeProjectFilesystem();
    Path testDataPath = TestDataHelper.getTestDataScenario(this, "swift_sdk_dependencies");
    Path simulatorDeps = testDataPath.resolve("iphonesimulator_15.2_deps.json");
    Tool swiftc = VersionedTool.of("foo", FakeSourcePath.of("swiftc"), "1.0");
    AppleCompilerTargetTriple triple =
        AppleCompilerTargetTriple.of(
            "x86_64", "apple", "ios", Optional.of("13.0"), Optional.empty());

    SwiftSdkDependencies sdkDependencies =
        new SwiftSdkDependencies(
            actionGraphBuilder,
            fakeFilesystem,
            simulatorDeps.toString(),
            swiftc,
            ImmutableList.of(),
            triple,
            PathSourcePath.of(fakeFilesystem, Paths.get("some/sdk/path")),
            PathSourcePath.of(fakeFilesystem, Paths.get("some/platform/path")),
            PathSourcePath.of(fakeFilesystem, Paths.get("some/resource/dir")));

    ImmutableSet<ExplicitModuleOutput> swiftmoduleDeps =
        sdkDependencies.getSdkModuleDependencies("SwiftOnoneSupport", triple);
    assertThat(swiftmoduleDeps.size(), equalTo(3));

    ImmutableSortedSet<String> filenames =
        swiftmoduleDeps.stream()
            .map(
                sp ->
                    actionGraphBuilder
                        .getSourcePathResolver()
                        .getIdeallyRelativePath(sp.getOutputPath())
                        .getFileName()
                        .toString())
            .collect(ImmutableSortedSet.toImmutableSortedSet(Comparator.naturalOrder()));
    assertThat(
        filenames,
        equalTo(
            ImmutableSet.of(
                "Swift.swiftmodule", "SwiftOnoneSupport.swiftmodule", "SwiftShims.pcm")));
  }

  @Test
  public void testLinkNames() {
    ActionGraphBuilder actionGraphBuilder = new TestActionGraphBuilder(TargetGraph.EMPTY);
    ProjectFilesystem fakeFilesystem = new FakeProjectFilesystem();
    Path testDataPath = TestDataHelper.getTestDataScenario(this, "swift_sdk_dependencies");
    Path simulatorDeps = testDataPath.resolve("iphonesimulator_15.2_deps.json");
    Tool swiftc = VersionedTool.of("foo", FakeSourcePath.of("swiftc"), "1.0");
    AppleCompilerTargetTriple triple =
        AppleCompilerTargetTriple.of(
            "x86_64", "apple", "ios", Optional.of("13.0"), Optional.empty());

    SwiftSdkDependencies sdkDependencies =
        new SwiftSdkDependencies(
            actionGraphBuilder,
            fakeFilesystem,
            simulatorDeps.toString(),
            swiftc,
            ImmutableList.of(),
            triple,
            PathSourcePath.of(fakeFilesystem, Paths.get("some/sdk/path")),
            PathSourcePath.of(fakeFilesystem, Paths.get("some/platform/path")),
            PathSourcePath.of(fakeFilesystem, Paths.get("some/resource/dir")));

    assertThat(sdkDependencies.getModuleNameForLinkName("Foundation"), equalTo("Foundation"));
    assertThat(sdkDependencies.getModuleNameForLinkName("libate"), equalTo("AppleTextureEncoder"));
  }
}
