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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.apple.AppleNativeIntegrationTestUtils;
import com.facebook.buck.apple.FakeAppleRuleDescriptions;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.core.build.buildable.context.FakeBuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TestBuildRuleCreationContextFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.collect.ImmutableSortedSet;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class SwiftOutputFileMapTest {

  @Rule public final TemporaryPaths tmpDir = new TemporaryPaths();

  private ProjectFilesystem projectFilesystem;
  private BuildRuleResolver ruleResolver;
  private SourcePathResolverAdapter resolver;

  @Before
  public void setUp() throws Exception {
    projectFilesystem = new FakeProjectFilesystem(tmpDir.getRoot());
    ruleResolver = new TestActionGraphBuilder(TargetGraph.EMPTY);
    resolver = ruleResolver.getSourcePathResolver();
  }

  @Test
  public void testOutputFileMapGenerationSteps() {
    assumeThat(AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.MACOSX), is(true));

    BuildContext buildContext = FakeBuildContext.withSourcePathResolver(resolver);
    FakeBuildableContext buildableContext = new FakeBuildableContext();
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar#iphoneos-arm64");
    BuildTarget swiftCompileTarget =
        buildTarget.withAppendedFlavors(SwiftLibraryDescription.SWIFT_COMPILE_FLAVOR);

    BuckConfig buckConfig =
        FakeBuckConfig.builder().setSections("[swift]", "incremental_imports = True").build();
    SwiftLibraryDescription swiftLibraryDescription =
        FakeAppleRuleDescriptions.createSwiftLibraryDescription(buckConfig);
    SwiftLibraryDescriptionArg swiftArgs =
        SwiftLibraryDescriptionArg.builder()
            .setName("dummy")
            .setSrcs(ImmutableSortedSet.of(FakeSourcePath.of("bar/file.swift")))
            .build();

    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();

    SwiftCompile swiftCompileRule =
        (SwiftCompile)
            swiftLibraryDescription.createBuildRule(
                TestBuildRuleCreationContextFactory.create(graphBuilder, projectFilesystem),
                swiftCompileTarget,
                TestBuildRuleParams.create(),
                swiftArgs);

    Path outputPath =
        BuildTargetPaths.getGenPath(projectFilesystem.getBuckPaths(), swiftCompileTarget, "%s")
            .getPath();

    OutputFileMapStep expectedStep =
        new OutputFileMapStep(
            projectFilesystem,
            outputPath.resolve("OutputFileMap.json"),
            new OutputFileMap(
                resolver, ImmutableSortedSet.of(FakeSourcePath.of("bar/file.swift")), outputPath));

    Step step = swiftCompileRule.getBuildSteps(buildContext, buildableContext).get(1);
    assertEquals(expectedStep, step);
  }

  @Test
  public void testOutputFileMapOutput() throws IOException {
    assumeThat(AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.MACOSX), is(true));

    Path outputPath = Path.of("/output/file/path/");

    PathSourcePath sourcePath1 =
        PathSourcePath.of(projectFilesystem, Path.of("/input/file1.swift"));
    PathSourcePath sourcePath2 =
        PathSourcePath.of(projectFilesystem, Path.of("/input/file2.swift"));
    ImmutableSortedSet<SourcePath> sourceFiles = ImmutableSortedSet.of(sourcePath1, sourcePath2);

    OutputFileMap outputFileMap = new OutputFileMap(resolver, sourceFiles, outputPath);

    ByteArrayOutputStream expectedOutput = new ByteArrayOutputStream();
    try (PrintStream sink = new PrintStream(expectedOutput);
        JsonGenerator generator = ObjectMappers.createGenerator(sink).useDefaultPrettyPrinter()) {
      outputFileMap.render(generator);
    }

    assertEquals(
        "{\n"
            + "  \"/input/file1.swift\" : {\n"
            + "    \"object\" : \"/output/file/path/file1.o\",\n"
            + "    \"llvm-bc\" : \"/output/file/path/file1.o\",\n"
            + "    \"swift-dependencies\" : \"/output/file/path/file1.swiftdeps\",\n"
            + "    \"swiftmodule\" : \"/output/file/path/file1~partial.swiftmodule\",\n"
            + "    \"swiftdoc\" : \"/output/file/path/file1~partial.swiftdoc\"\n"
            + "  },\n"
            + "  \"/input/file2.swift\" : {\n"
            + "    \"object\" : \"/output/file/path/file2.o\",\n"
            + "    \"llvm-bc\" : \"/output/file/path/file2.o\",\n"
            + "    \"swift-dependencies\" : \"/output/file/path/file2.swiftdeps\",\n"
            + "    \"swiftmodule\" : \"/output/file/path/file2~partial.swiftmodule\",\n"
            + "    \"swiftdoc\" : \"/output/file/path/file2~partial.swiftdoc\"\n"
            + "  },\n"
            + "  \"\" : {\n"
            + "    \"swift-dependencies\" : \"/output/file/path/module-build-record.swiftdeps\"\n"
            + "  }\n"
            + "}",
        expectedOutput.toString());
  }
}
