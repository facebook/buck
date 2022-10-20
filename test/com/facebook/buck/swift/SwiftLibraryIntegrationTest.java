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

import static com.facebook.buck.util.environment.Platform.WINDOWS;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assume.assumeThat;

import com.facebook.buck.apple.AppleLibraryBuilder;
import com.facebook.buck.apple.AppleNativeIntegrationTestUtils;
import com.facebook.buck.apple.FakeAppleRuleDescriptions;
import com.facebook.buck.apple.common.AppleFlavors;
import com.facebook.buck.apple.toolchain.ApplePlatform;
import com.facebook.buck.apple.toolchain.AppleSdkPaths;
import com.facebook.buck.core.build.buildable.context.FakeBuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.context.FakeBuildContext;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.targetgraph.FakeTargetNodeBuilder;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.model.targetgraph.TestBuildRuleCreationContextFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLink;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.FileListableLinkerInputArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.ProcessResult;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.BiFunction;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class SwiftLibraryIntegrationTest {
  @Rule public final TemporaryPaths tmpDir = new TemporaryPaths();

  private ActionGraphBuilder graphBuilder;
  private SourcePathResolverAdapter pathResolver;

  @Before
  public void setUp() {
    assumeThat(Platform.detect(), is(not(WINDOWS)));
    graphBuilder = new TestActionGraphBuilder();
    pathResolver = graphBuilder.getSourcePathResolver();
  }

  private ProjectWorkspace createProjectWorkspaceForScenario(String scenario) throws IOException {
    ProjectWorkspace workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, scenario, tmpDir);
    workspace.addBuckConfigLocalOption("swift", "use_argfile", "true");
    return workspace;
  }

  @Test
  public void testSwiftCompileAndLinkArgsWithWMO() throws NoSuchBuildTargetException {
    testSwiftCompileAndLinkArgsImpl(false);
  }

  @Test
  public void testSwiftCompileAndLinkArgsIncrementally() throws NoSuchBuildTargetException {
    testSwiftCompileAndLinkArgsImpl(true);
  }

  private void testSwiftCompileAndLinkArgsImpl(boolean incremental)
      throws NoSuchBuildTargetException {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar#iphoneos-arm64");
    BuildTarget swiftCompileTarget =
        buildTarget.withAppendedFlavors(SwiftLibraryDescription.SWIFT_COMPILE_FLAVOR);
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildRuleParams params = TestBuildRuleParams.create();

    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections("[swift]", incremental ? "incremental = True" : "incremental = False")
            .build();

    SwiftLibraryDescription swiftLibraryDescription =
        FakeAppleRuleDescriptions.createSwiftLibraryDescription(buckConfig);

    SwiftLibraryDescriptionArg args =
        createDummySwiftArg(ImmutableSortedSet.of(FakeSourcePath.of("file.swift")));
    SwiftCompile buildRule =
        (SwiftCompile)
            swiftLibraryDescription.createBuildRule(
                TestBuildRuleCreationContextFactory.create(graphBuilder, projectFilesystem),
                swiftCompileTarget,
                params,
                args);
    graphBuilder.addToIndex(buildRule);

    assertThat(
        buildRule.getSwiftmoduleLinkerInput(),
        Matchers.equalTo(ImmutableSet.of(buildRule.getSwiftModuleOutputPath())));

    Arg objArg = buildRule.getFileListLinkArg().get(0);
    assertThat(objArg, Matchers.instanceOf(FileListableLinkerInputArg.class));
    FileListableLinkerInputArg fileListArg = (FileListableLinkerInputArg) objArg;
    ExplicitBuildTargetSourcePath fileListSourcePath =
        ExplicitBuildTargetSourcePath.of(
            swiftCompileTarget,
            pathResolver
                .getCellUnsafeRelPath(buildRule.getSourcePathToOutput())
                .resolve(incremental ? "file.o" : "bar.o"));
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
    ProjectWorkspace workspace = createProjectWorkspaceForScenario("bridging_header_tracking");
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
    ProjectWorkspace workspace = createProjectWorkspaceForScenario("bridging_header_tracking");
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
    ProjectWorkspace workspace = createProjectWorkspaceForScenario("helloworld");
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
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar#iphoneos-arm64");
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
                    .getCellUnsafeRelPath(buildRule.getSourcePathToOutput())
                    .resolve("bar.swiftdoc"))
            .getResolvedPath()
            .toString();
    assertThat(
        compilerCommand,
        Matchers.hasItems("-emit-module-doc", "-emit-module-doc-path", expectedSwiftdocPath));
  }

  @Test
  public void testNoClangModuleBreadcrumbArgsAreIncludedInCompilerCommand() {
    assumeThat(
        AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.IPHONESIMULATOR), is(true));
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar#iphoneos-arm64");
    BuildTarget swiftCompileTarget =
        buildTarget.withAppendedFlavors(SwiftLibraryDescription.SWIFT_COMPILE_FLAVOR);
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    BuckConfig buckConfig = FakeBuckConfig.builder().build();

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

    assertThat(compilerCommand, Matchers.hasItem("-no-clang-module-breadcrumbs"));
  }

  @Test
  public void testSwiftCompileDebugPathPrefixFlags() throws NoSuchBuildTargetException {
    assumeThat(
        AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.IPHONESIMULATOR), is(true));
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar#iphoneos-arm64");
    BuildTarget swiftCompileTarget =
        buildTarget.withAppendedFlavors(SwiftLibraryDescription.SWIFT_COMPILE_FLAVOR);
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    BuckConfig buckConfig = FakeBuckConfig.builder().build();

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

    AppleSdkPaths sdkPaths = FakeAppleRuleDescriptions.DEFAULT_IPHONEOS_SDK_PATHS;
    assertThat(
        compilerCommand,
        Matchers.containsInRelativeOrder(
            "-debug-prefix-map", projectFilesystem.getRootPath().toString() + "=.",
            "-debug-prefix-map", sdkPaths.getSdkPath() + "=/APPLE_SDKROOT",
            "-debug-prefix-map", sdkPaths.getPlatformPath() + "=/APPLE_PLATFORM_DIR",
            "-debug-prefix-map", sdkPaths.getDeveloperPath().get() + "=/APPLE_DEVELOPER_DIR"));
  }

  @Test
  public void testPrefixSerializedDebuggingOptions() {
    assumeThat(
        AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.IPHONESIMULATOR), is(true));
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar#iphoneos-arm64");
    BuildTarget swiftCompileTarget =
        buildTarget.withAppendedFlavors(SwiftLibraryDescription.SWIFT_COMPILE_FLAVOR);
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    BuckConfig buckConfig = FakeBuckConfig.builder().build();

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

    assertThat(compilerCommand, Matchers.hasItem("-prefix-serialized-debugging-options"));
  }

  @Test
  public void testIncrementalBuildArgsAreIncludedInCompilerCommand() {
    assumeThat(
        AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.IPHONESIMULATOR), is(true));
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar#iphoneos-arm64");
    BuildTarget swiftCompileTarget =
        buildTarget.withAppendedFlavors(SwiftLibraryDescription.SWIFT_COMPILE_FLAVOR);
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    BuckConfig buckConfig =
        FakeBuckConfig.builder().setSections("[swift]", "incremental = True").build();

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
    SwiftCompileStep compileStep = (SwiftCompileStep) steps.get(2);
    ImmutableList<String> compilerCommand =
        ImmutableList.copyOf(compileStep.getDescription(null).split(" "));

    assertThat(
        compilerCommand,
        Matchers.hasItems(
            "-incremental", "-output-file-map", "-enable-batch-mode", "-driver-batch-count", "1"));
    assertThat(compilerCommand, not(Matchers.hasItem("-enable-incremental-imports")));
  }

  @Test
  public void testIncrementalImportsArgsAreIncludedInCompilerCommand() {
    assumeThat(
        AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.IPHONESIMULATOR), is(true));
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//foo:bar#iphoneos-arm64");
    BuildTarget swiftCompileTarget =
        buildTarget.withAppendedFlavors(SwiftLibraryDescription.SWIFT_COMPILE_FLAVOR);
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    BuckConfig buckConfig =
        FakeBuckConfig.builder().setSections("[swift]", "incremental_imports = True").build();

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
    SwiftCompileStep compileStep = (SwiftCompileStep) steps.get(2);
    ImmutableList<String> compilerCommand =
        ImmutableList.copyOf(compileStep.getDescription(null).split(" "));

    assertThat(
        compilerCommand,
        Matchers.hasItems(
            "-incremental",
            "-output-file-map",
            "-enable-batch-mode",
            "-driver-batch-count",
            "1",
            "-enable-incremental-imports"));
  }

  @Test
  public void testExplicitModules() throws IOException {
    assumeThat(
        AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.IPHONESIMULATOR), is(true));
    ProjectWorkspace workspace = createProjectWorkspaceForScenario("swift_explicit_modules");
    workspace.setUp();

    // We need to set up a relative path symlink to point back to Xcode contents
    AbsPath projectRoot = workspace.getProjectFileSystem().getRootPath();
    Files.createSymbolicLink(
        projectRoot.getPath().resolve("xcode"), Paths.get("/var/db/xcode_select_link"));

    BuildTarget target = workspace.newBuildTarget("//:a#iphonesimulator-x86_64,static");
    ProcessResult result = workspace.runBuckCommand("build", target.getFullyQualifiedName());
    result.assertSuccess();
  }

  @Test
  public void testRulesExportedFromDepsBecomeFirstOrderDeps() {
    assumeThat(AppleNativeIntegrationTestUtils.isSwiftAvailable(ApplePlatform.MACOSX), is(true));

    BuckConfig buckConfig =
        FakeBuckConfig.builder()
            .setSections(
                "[swift]",
                "allow_private_swift_deps = True",
                "[apple]",
                "use_swift_delegate = False")
            .build();

    BuildTarget privateDepLibTarget = BuildTargetFactory.newInstance("//swift:private_dep");
    TargetNode<?> privateDepNode =
        AppleLibraryBuilder.createBuilder(privateDepLibTarget, buckConfig)
            .setSrcs(
                ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("private_dep.swift"))))
            .setReexportAllHeaderDependencies(false)
            .build();

    BuildTarget exportedDepLibTarget = BuildTargetFactory.newInstance("//swift:exported_dep");
    TargetNode<?> exportedDepLibNode =
        AppleLibraryBuilder.createBuilder(exportedDepLibTarget, buckConfig)
            .setSrcs(
                ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("exported_dep.swift"))))
            .setReexportAllHeaderDependencies(false)
            .build();

    BuildTarget directDepLibTarget = BuildTargetFactory.newInstance("//swift:direct_dep");
    TargetNode<?> directDepLibNode =
        AppleLibraryBuilder.createBuilder(directDepLibTarget, buckConfig)
            .setSrcs(
                ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("direct_dep.swift"))))
            .setDeps(ImmutableSortedSet.of(privateDepLibTarget))
            .setExportedDeps(ImmutableSortedSet.of(exportedDepLibTarget))
            .setReexportAllHeaderDependencies(false)
            .build();

    BuildTarget rootLibTarget = BuildTargetFactory.newInstance("//swift:root_lib");
    TargetNode<?> rootLibNode =
        AppleLibraryBuilder.createBuilder(rootLibTarget, buckConfig)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(FakeSourcePath.of("root_lib.swift"))))
            .setDeps(ImmutableSortedSet.of(directDepLibTarget))
            .setReexportAllHeaderDependencies(false)
            .build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            ImmutableSet.of(privateDepNode, exportedDepLibNode, directDepLibNode, rootLibNode));

    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);

    BiFunction<BuildTarget, Flavor, BuildRule> requireRule =
        (target, flavor) -> {
          return graphBuilder.requireRule(
              target.withFlavors(
                  flavor, FakeAppleRuleDescriptions.DEFAULT_MACOSX_X86_64_PLATFORM.getFlavor()));
        };

    BuildRule rootLibRule =
        requireRule.apply(rootLibNode.getBuildTarget(), AppleFlavors.SWIFT_COMPILE_FLAVOR);

    BuildRule directDepHeaderRule =
        requireRule.apply(
            directDepLibNode.getBuildTarget(),
            AppleFlavors.SWIFT_EXPORTED_OBJC_GENERATED_HEADER_SYMLINK_TREE_FLAVOR);

    BuildRule exportedDepHeaderRule =
        requireRule.apply(
            exportedDepLibNode.getBuildTarget(),
            AppleFlavors.SWIFT_EXPORTED_OBJC_GENERATED_HEADER_SYMLINK_TREE_FLAVOR);

    BuildRule directDepCompileRule =
        requireRule.apply(directDepLibNode.getBuildTarget(), AppleFlavors.SWIFT_COMPILE_FLAVOR);

    BuildRule exportedDepCompileRule =
        requireRule.apply(exportedDepLibNode.getBuildTarget(), AppleFlavors.SWIFT_COMPILE_FLAVOR);

    assertThat(
        rootLibRule.getBuildDeps(),
        Matchers.containsInAnyOrder(
            directDepHeaderRule,
            exportedDepHeaderRule,
            directDepCompileRule,
            exportedDepCompileRule));

    Path directDepOutputPath =
        graphBuilder
            .getSourcePathResolver()
            .getIdeallyRelativePath(directDepCompileRule.getSourcePathToOutput());
    Path exportedDepOutputPath =
        graphBuilder
            .getSourcePathResolver()
            .getIdeallyRelativePath(exportedDepCompileRule.getSourcePathToOutput());

    ImmutableList<String> compilerArgs =
        ((SwiftCompile) rootLibRule).constructCompilerArgs(graphBuilder.getSourcePathResolver());

    assertThat(
        compilerArgs,
        Matchers.containsInRelativeOrder(
            "-I", directDepOutputPath.toString(),
            "-I", exportedDepOutputPath.toString()));
  }

  private SwiftLibraryDescriptionArg createDummySwiftArg() {
    return createDummySwiftArg(ImmutableSortedSet.of());
  }

  private SwiftLibraryDescriptionArg createDummySwiftArg(ImmutableSortedSet<SourcePath> srcs) {
    return SwiftLibraryDescriptionArg.builder().setName("dummy").setSrcs(srcs).build();
  }
}
