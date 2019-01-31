/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.swift;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
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
import com.facebook.buck.core.model.targetgraph.FakeTargetNodeBuilder;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TestBuildRuleCreationContextFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLink;
import com.facebook.buck.cxx.FakeCxxLibrary;
import com.facebook.buck.cxx.HeaderSymlinkTreeWithHeaderMap;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.FileListableLinkerInputArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class SwiftLibraryIntegrationTest {
  @Rule public final TemporaryPaths tmpDir = new TemporaryPaths();

  private ActionGraphBuilder graphBuilder;
  private SourcePathResolver pathResolver;
  private SourcePathRuleFinder ruleFinder;

  @Before
  public void setUp() {
    graphBuilder = new TestActionGraphBuilder();
    ruleFinder = new SourcePathRuleFinder(graphBuilder);
    pathResolver = DefaultSourcePathResolver.from(ruleFinder);
  }

  @Test
  public void headersOfDependentTargetsAreIncluded() {
    // The output path used by the buildable for the link tree.
    BuildTarget symlinkTarget = BuildTargetFactory.newInstance("//:symlink");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem(tmpDir.getRoot());
    Path symlinkTreeRoot =
        BuildTargetPaths.getGenPath(projectFilesystem, symlinkTarget, "%s/symlink-tree-root");

    // Setup the map representing the link tree.
    ImmutableMap<Path, SourcePath> links = ImmutableMap.of();

    HeaderSymlinkTreeWithHeaderMap symlinkTreeBuildRule =
        HeaderSymlinkTreeWithHeaderMap.create(
            symlinkTarget, projectFilesystem, symlinkTreeRoot, links, ruleFinder);
    graphBuilder.addToIndex(symlinkTreeBuildRule);

    BuildTarget libTarget = BuildTargetFactory.newInstance("//:lib");
    BuildRuleParams libParams = TestBuildRuleParams.create();
    FakeCxxLibrary depRule =
        new FakeCxxLibrary(
            libTarget,
            new FakeProjectFilesystem(),
            libParams,
            BuildTargetFactory.newInstance("//:header"),
            symlinkTarget,
            BuildTargetFactory.newInstance("//:privateheader"),
            BuildTargetFactory.newInstance("//:privatesymlink"),
            new FakeBuildRule("//:archive"),
            new FakeBuildRule("//:shared"),
            Paths.get("output/path/lib.so"),
            "lib.so",
            ImmutableSortedSet.of());

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar#iphoneos-x86_64");
    BuildRuleParams params =
        TestBuildRuleParams.create().withDeclaredDeps(ImmutableSortedSet.of(depRule));

    SwiftLibraryDescriptionArg args = createDummySwiftArg();

    SwiftCompile buildRule =
        (SwiftCompile)
            FakeAppleRuleDescriptions.SWIFT_LIBRARY_DESCRIPTION.createBuildRule(
                TestBuildRuleCreationContextFactory.create(graphBuilder, projectFilesystem),
                buildTarget,
                params,
                args);

    ImmutableList<String> swiftIncludeArgs = buildRule.getSwiftIncludeArgs(pathResolver);

    assertThat(swiftIncludeArgs.size(), Matchers.equalTo(2));
    assertThat(swiftIncludeArgs.get(0), Matchers.equalTo("-I"));
    assertThat(swiftIncludeArgs.get(1), Matchers.endsWith("symlink.hmap"));
  }

  @Test
  public void testSwiftCompileAndLinkArgs() throws NoSuchBuildTargetException {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar#iphoneos-x86_64");
    BuildTarget swiftCompileTarget =
        buildTarget.withAppendedFlavors(SwiftLibraryDescription.SWIFT_COMPILE_FLAVOR);
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildRuleParams params = TestBuildRuleParams.create();

    SwiftLibraryDescriptionArg args = createDummySwiftArg();
    SwiftCompile buildRule =
        (SwiftCompile)
            FakeAppleRuleDescriptions.SWIFT_LIBRARY_DESCRIPTION.createBuildRule(
                TestBuildRuleCreationContextFactory.create(graphBuilder, projectFilesystem),
                swiftCompileTarget,
                params,
                args);
    graphBuilder.addToIndex(buildRule);

    ImmutableList<Arg> astArgs = buildRule.getAstLinkArgs();
    assertThat(astArgs, Matchers.hasSize(3));
    assertThat(astArgs.get(0), Matchers.equalTo(StringArg.of("-Xlinker")));
    assertThat(astArgs.get(1), Matchers.equalTo(StringArg.of("-add_ast_path")));

    assertThat(astArgs.get(2), Matchers.instanceOf(SourcePathArg.class));
    SourcePathArg sourcePathArg = (SourcePathArg) astArgs.get(2);
    assertThat(
        sourcePathArg.getPath(),
        Matchers.equalTo(
            ExplicitBuildTargetSourcePath.of(
                swiftCompileTarget,
                pathResolver
                    .getRelativePath(buildRule.getSourcePathToOutput())
                    .resolve("bar.swiftmodule"))));

    Arg objArg = buildRule.getFileListLinkArg().get(0);
    assertThat(objArg, Matchers.instanceOf(FileListableLinkerInputArg.class));
    FileListableLinkerInputArg fileListArg = (FileListableLinkerInputArg) objArg;
    ExplicitBuildTargetSourcePath fileListSourcePath =
        ExplicitBuildTargetSourcePath.of(
            swiftCompileTarget,
            pathResolver.getRelativePath(buildRule.getSourcePathToOutput()).resolve("bar.o"));
    assertThat(fileListArg.getPath(), Matchers.equalTo(fileListSourcePath));

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(FakeTargetNodeBuilder.build(buildRule));
    CxxLink linkRule =
        (CxxLink)
            FakeAppleRuleDescriptions.SWIFT_LIBRARY_DESCRIPTION.createBuildRule(
                TestBuildRuleCreationContextFactory.create(
                    targetGraph, graphBuilder, projectFilesystem),
                buildTarget.withAppendedFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR),
                params,
                args);

    assertThat(linkRule.getArgs(), Matchers.hasItem(objArg));
    assertThat(
        linkRule.getArgs(), Matchers.not(Matchers.hasItem(SourcePathArg.of(fileListSourcePath))));
  }

  @Test
  public void testBridgingHeaderTracking() throws Exception {
    assumeThat(
        AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.IPHONESIMULATOR), is(true));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "bridging_header_tracking", tmpDir);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("cxx", "untracked_headers", "error");

    BuildTarget target = workspace.newBuildTarget("//:BigLib#iphonesimulator-x86_64,static");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();
  }

  @Test
  public void testBridgingHeaderTrackingTransitive() throws Exception {
    assumeThat(
        AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.IPHONESIMULATOR), is(true));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "bridging_header_tracking", tmpDir);
    workspace.setUp();
    workspace.addBuckConfigLocalOption("cxx", "untracked_headers", "error");

    BuildTarget target =
        workspace.newBuildTarget("//:BigLibTransitive#iphonesimulator-x86_64,static");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();
  }

  @Test
  public void testGlobalFlagsInRuleKey() throws Exception {
    assumeThat(
        AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.IPHONESIMULATOR), is(true));
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "helloworld", tmpDir);
    workspace.setUp();

    BuildTarget target = workspace.newBuildTarget("//:hello#iphonesimulator-x86_64,swift-compile");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();
    workspace
        .getBuildLog()
        .assertTargetBuiltLocally("//:hello#iphonesimulator-x86_64,swift-compile");

    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();
    workspace
        .getBuildLog()
        .assertTargetHadMatchingRuleKey("//:hello#iphonesimulator-x86_64,swift-compile");

    workspace.addBuckConfigLocalOption("swift", "compiler_flags", "-D DEBUG");
    workspace.runBuckCommand("build", target.getFullyQualifiedName()).assertSuccess();
    workspace
        .getBuildLog()
        .assertTargetBuiltLocally("//:hello#iphonesimulator-x86_64,swift-compile");
  }

  @Test
  public void testEmitModuleDocArgsAreIncludedInCompilerCommand() {
    assumeThat(
        AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.IPHONESIMULATOR), is(true));
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar#iphoneos-x86_64");
    BuildTarget swiftCompileTarget =
        buildTarget.withAppendedFlavors(SwiftLibraryDescription.SWIFT_COMPILE_FLAVOR);
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    BuckConfig buckConfig =
        FakeBuckConfig.builder().setSections("[swift]", "emit_swiftdocs = True").build();

    SwiftLibraryDescription swiftLibraryDescription =
        FakeAppleRuleDescriptions.createSwiftLibraryDescription(buckConfig);

    SwiftCompile buildRule =
        (SwiftCompile)
            swiftLibraryDescription.createBuildRule(
                TestBuildRuleCreationContextFactory.create(graphBuilder, projectFilesystem),
                swiftCompileTarget,
                TestBuildRuleParams.create(),
                createDummySwiftArg());

    BuildContext buildContext = FakeBuildContext.withSourcePathResolver(pathResolver);
    ImmutableList<Step> steps = buildRule.getBuildSteps(buildContext, new FakeBuildableContext());
    SwiftCompileStep compileStep = (SwiftCompileStep) steps.get(1);
    ImmutableList<String> compilerCommand =
        ImmutableList.copyOf(compileStep.getDescription(null).split(" "));

    String expectedSwiftdocPath =
        ExplicitBuildTargetSourcePath.of(
                swiftCompileTarget,
                pathResolver
                    .getRelativePath(buildRule.getSourcePathToOutput())
                    .resolve("bar.swiftdoc"))
            .getResolvedPath()
            .toString();
    assertThat(
        compilerCommand,
        Matchers.hasItems("-emit-module-doc", "-emit-module-doc-path", expectedSwiftdocPath));
  }

  private SwiftLibraryDescriptionArg createDummySwiftArg() {
    return SwiftLibraryDescriptionArg.builder().setName("dummy").build();
  }
}
