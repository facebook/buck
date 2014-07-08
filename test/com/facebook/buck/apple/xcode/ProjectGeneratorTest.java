/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.apple.xcode;

import static com.facebook.buck.apple.xcode.ProjectGeneratorTestUtils.assertHasSingletonFrameworksPhaseWithFrameworkEntries;
import static com.facebook.buck.apple.xcode.ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget;
import static com.facebook.buck.apple.xcode.ProjectGeneratorTestUtils.createBuildRuleWithDefaults;
import static com.facebook.buck.apple.xcode.ProjectGeneratorTestUtils.createPartialGraphFromBuildRuleResolver;
import static com.facebook.buck.apple.xcode.ProjectGeneratorTestUtils.createPartialGraphFromBuildRules;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.hamcrest.xml.HasXPath.hasXPath;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSString;
import com.facebook.buck.apple.AppleAssetCatalogDescription;
import com.facebook.buck.apple.AppleNativeTargetDescriptionArg;
import com.facebook.buck.apple.AppleResourceDescriptionArg;
import com.facebook.buck.apple.CoreDataModelDescription;
import com.facebook.buck.apple.IosBinaryDescription;
import com.facebook.buck.apple.IosLibraryDescription;
import com.facebook.buck.apple.IosPostprocessResourcesDescription;
import com.facebook.buck.apple.IosResourceDescription;
import com.facebook.buck.apple.IosTestDescription;
import com.facebook.buck.apple.MacosxBinaryDescription;
import com.facebook.buck.apple.MacosxFrameworkDescription;
import com.facebook.buck.apple.clang.HeaderMap;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildFile;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXCopyFilesBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXFileReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXGroup;
import com.facebook.buck.apple.xcode.xcodeproj.PBXHeadersBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXResourcesBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXShellScriptBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXSourcesBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.apple.xcode.xcodeproj.XCBuildConfiguration;
import com.facebook.buck.codegen.SourceSigner;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.rules.coercer.AppleSource;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.rules.coercer.Pair;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

public class ProjectGeneratorTest {

  private static final Path OUTPUT_DIRECTORY = Paths.get("_gen");
  private static final String PROJECT_NAME = "GeneratedProject";
  private static final String PROJECT_CONTAINER = PROJECT_NAME + ".xcodeproj";
  private static final Path OUTPUT_PROJECT_BUNDLE_PATH =
      OUTPUT_DIRECTORY.resolve(PROJECT_CONTAINER);
  private static final Path OUTPUT_PROJECT_FILE_PATH =
      OUTPUT_PROJECT_BUNDLE_PATH.resolve("project.pbxproj");

  private ProjectFilesystem projectFilesystem;
  private FakeProjectFilesystem fakeProjectFilesystem;
  private ExecutionContext executionContext;
  private IosLibraryDescription iosLibraryDescription;
  private IosTestDescription iosTestDescription;
  private IosBinaryDescription iosBinaryDescription;
  private IosPostprocessResourcesDescription iosPostprocessResourcesDescription;
  private IosResourceDescription iosResourceDescription;
  private MacosxFrameworkDescription macosxFrameworkDescription;
  private MacosxBinaryDescription macosxBinaryDescription;
  private CoreDataModelDescription coreDataModelDescription;

  @Before
  public void setUp() throws IOException {
    fakeProjectFilesystem = new FakeProjectFilesystem();
    projectFilesystem = fakeProjectFilesystem;
    executionContext = TestExecutionContext.newInstance();
    iosLibraryDescription = new IosLibraryDescription();
    iosTestDescription = new IosTestDescription();
    iosBinaryDescription = new IosBinaryDescription();
    iosPostprocessResourcesDescription = new IosPostprocessResourcesDescription();
    iosResourceDescription = new IosResourceDescription();
    macosxFrameworkDescription = new MacosxFrameworkDescription();
    macosxBinaryDescription = new MacosxBinaryDescription();
    coreDataModelDescription = new CoreDataModelDescription();

    // Add support files needed by project generation to fake filesystem.
    projectFilesystem.writeContentsToPath(
        "",
        Paths.get(ProjectGenerator.PATH_TO_ASSET_CATALOG_BUILD_PHASE_SCRIPT));
    projectFilesystem.writeContentsToPath("",
        Paths.get(ProjectGenerator.PATH_TO_ASSET_CATALOG_COMPILER));
  }

  @Test
  public void testProjectStructureForEmptyProject() throws IOException {
    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<BuildRule>of(),
        ImmutableSet.<BuildTarget>of());

    Path outputWorkspaceBundlePath = OUTPUT_DIRECTORY.resolve(PROJECT_NAME + ".xcworkspace");
    Path outputWorkspaceFilePath = outputWorkspaceBundlePath.resolve("contents.xcworkspacedata");

    projectGenerator.createXcodeProjects();

    Optional<String> pbxproj = projectFilesystem.readFileIfItExists(OUTPUT_PROJECT_FILE_PATH);
    assertTrue(pbxproj.isPresent());

    Optional<String> xcworkspacedata =
        projectFilesystem.readFileIfItExists(outputWorkspaceFilePath);
    assertTrue(xcworkspacedata.isPresent());
  }

  @Test
  public void testWorkspaceGeneration() throws IOException {
    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<BuildRule>of(),
        ImmutableSet.<BuildTarget>of());
    projectGenerator.createXcodeProjects();

    Document workspace = projectGenerator.getGeneratedWorkspace();
    assertThat(workspace, hasXPath("/Workspace[@version = \"1.0\"]"));
    assertThat(
        workspace,
        hasXPath("/Workspace/FileRef/@location", equalTo("container:" + PROJECT_CONTAINER)));
  }

  @Test
  public void testProjectFileSigning() throws IOException {
    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<BuildRule>of(), ImmutableSet.<BuildTarget>of());

    projectGenerator.createXcodeProjects();

    Optional<String> pbxproj = projectFilesystem.readFileIfItExists(OUTPUT_PROJECT_FILE_PATH);
    assertTrue(pbxproj.isPresent());
    assertEquals(
        SourceSigner.SignatureStatus.OK,
        SourceSigner.getSignatureStatus(pbxproj.get()));
  }

  @Test
  public void testLibrarySourceGroups() throws IOException {
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "lib").build())
            .setType(IosLibraryDescription.TYPE)
            .build();
    AppleNativeTargetDescriptionArg arg = iosLibraryDescription.createUnpopulatedConstructorArg();
    arg.configs = ImmutableMap.of(
        "Debug", ImmutableList.<Either<Path, ImmutableMap<String, String>>>of());
    arg.srcs = ImmutableList.of(
        AppleSource.ofSourceGroup(
            new Pair<>(
                "Group1",
                ImmutableList.of(
                    AppleSource.ofSourcePath(new TestSourcePath("foo.m")),
                    AppleSource.ofSourcePathWithFlags(
                        new Pair<SourcePath, String>(new TestSourcePath("bar.m"), "-Wall"))))),
        AppleSource.ofSourceGroup(
            new Pair<>(
                "Group2",
                ImmutableList.of(
                    AppleSource.ofSourcePath(new TestSourcePath("baz.m")),
                    AppleSource.ofSourcePathWithFlags(
                        new Pair<SourcePath, String>(
                            new TestSourcePath("blech.m"), "-fobjc-arc"))))));
    arg.frameworks = ImmutableSortedSet.of();
    arg.deps = Optional.absent();
    BuildRule rule = iosLibraryDescription.createBuildRule(params, new BuildRuleResolver(), arg);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule),
        ImmutableSet.of(rule.getBuildTarget()));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXGroup targetGroup =
        project.getMainGroup().getOrCreateChildGroupByName(rule.getFullyQualifiedName());
    PBXGroup sourcesGroup = targetGroup.getOrCreateChildGroupByName("Sources");

    assertThat(sourcesGroup.getChildren(), hasSize(2));

    PBXGroup group1 = (PBXGroup) Iterables.get(sourcesGroup.getChildren(), 0);
    assertEquals("Group1", group1.getName());
    assertThat(group1.getChildren(), hasSize(2));
    PBXFileReference fileRefFoo = (PBXFileReference) Iterables.get(group1.getChildren(), 0);
    assertEquals("foo.m", fileRefFoo.getName());
    PBXFileReference fileRefBar = (PBXFileReference) Iterables.get(group1.getChildren(), 1);
    assertEquals("bar.m", fileRefBar.getName());

    PBXGroup group2 = (PBXGroup) Iterables.get(sourcesGroup.getChildren(), 1);
    assertEquals("Group2", group2.getName());
    assertThat(group2.getChildren(), hasSize(2));
    PBXFileReference fileRefBaz = (PBXFileReference) Iterables.get(group2.getChildren(), 0);
    assertEquals("baz.m", fileRefBaz.getName());
    PBXFileReference fileRefBlech = (PBXFileReference) Iterables.get(group2.getChildren(), 1);
    assertEquals("blech.m", fileRefBlech.getName());
  }

  @Test
  public void testLibraryHeaderGroups() throws IOException {
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "lib").build())
            .setType(IosLibraryDescription.TYPE)
            .build();
    AppleNativeTargetDescriptionArg arg = iosLibraryDescription.createUnpopulatedConstructorArg();
    arg.configs = ImmutableMap.of(
        "Debug", ImmutableList.<Either<Path, ImmutableMap<String, String>>>of());
    arg.srcs = ImmutableList.of(
        AppleSource.ofSourceGroup(
            new Pair<>(
                "HeaderGroup1",
                ImmutableList.of(
                    AppleSource.ofSourcePath(new TestSourcePath("foo.h")),
                    AppleSource.ofSourcePathWithFlags(
                        new Pair<SourcePath, String>(new TestSourcePath("bar.h"), "public"))))),
        AppleSource.ofSourceGroup(
            new Pair<>(
                "HeaderGroup2",
                ImmutableList.of(
                    AppleSource.ofSourcePath(new TestSourcePath("baz.h")),
                    AppleSource.ofSourcePathWithFlags(
                        new Pair<SourcePath, String>(new TestSourcePath("blech.h"), "private"))))));
    arg.frameworks = ImmutableSortedSet.of();
    arg.deps = Optional.absent();
    BuildRule rule = iosLibraryDescription.createBuildRule(params, new BuildRuleResolver(), arg);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule),
        ImmutableSet.of(rule.getBuildTarget()));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXGroup targetGroup =
        project.getMainGroup().getOrCreateChildGroupByName(rule.getFullyQualifiedName());
    PBXGroup sourcesGroup = targetGroup.getOrCreateChildGroupByName("Sources");

    assertThat(sourcesGroup.getChildren(), hasSize(2));

    PBXGroup group1 = (PBXGroup) Iterables.get(sourcesGroup.getChildren(), 0);
    assertEquals("HeaderGroup1", group1.getName());
    assertThat(group1.getChildren(), hasSize(2));
    PBXFileReference fileRefFoo = (PBXFileReference) Iterables.get(group1.getChildren(), 0);
    assertEquals("foo.h", fileRefFoo.getName());
    PBXFileReference fileRefBar = (PBXFileReference) Iterables.get(group1.getChildren(), 1);
    assertEquals("bar.h", fileRefBar.getName());

    PBXGroup group2 = (PBXGroup) Iterables.get(sourcesGroup.getChildren(), 1);
    assertEquals("HeaderGroup2", group2.getName());
    assertThat(group2.getChildren(), hasSize(2));
    PBXFileReference fileRefBaz = (PBXFileReference) Iterables.get(group2.getChildren(), 0);
    assertEquals("baz.h", fileRefBaz.getName());
    PBXFileReference fileRefBlech = (PBXFileReference) Iterables.get(group2.getChildren(), 1);
    assertEquals("blech.h", fileRefBlech.getName());


    PBXTarget target = assertTargetExistsAndReturnTarget(
        project,
        "//foo:lib");
    PBXBuildPhase headersBuildPhase =
      Iterables.find(target.getBuildPhases(), new Predicate<PBXBuildPhase>() {
          @Override
          public boolean apply(PBXBuildPhase input) {
              return input instanceof PBXHeadersBuildPhase;
          }
        });
    PBXBuildFile fooHeaderBuildFile = Iterables.get(headersBuildPhase.getFiles(), 0);
    assertFalse(
        "foo.h should not have settings dictionary",
        fooHeaderBuildFile.getSettings().isPresent());
    PBXBuildFile barHeaderBuildFile = Iterables.get(headersBuildPhase.getFiles(), 1);
    assertTrue(
        "bar.h should have settings dictionary",
        barHeaderBuildFile.getSettings().isPresent());
    NSDictionary barBuildFileSettings = barHeaderBuildFile.getSettings().get();
    NSArray barAttributes = (NSArray) barBuildFileSettings.get("ATTRIBUTES");
    assertArrayEquals(new NSString[]{new NSString("Public")}, barAttributes.getArray());
    PBXBuildFile bazHeaderBuildFile = Iterables.get(headersBuildPhase.getFiles(), 2);
    assertFalse(
        "baz.h should not have settings dictionary",
        bazHeaderBuildFile.getSettings().isPresent());
    PBXBuildFile blechHeaderBuildFile = Iterables.get(headersBuildPhase.getFiles(), 3);
    assertTrue(
        "blech.h should have settings dictionary",
        blechHeaderBuildFile.getSettings().isPresent());
    NSDictionary blechBuildFileSettings = blechHeaderBuildFile.getSettings().get();
    NSArray blechAttributes = (NSArray) blechBuildFileSettings.get("ATTRIBUTES");
    assertArrayEquals(new NSString[]{new NSString("Private")}, blechAttributes.getArray());

    // Test generation of header maps
    List<Path> headerMaps = projectGenerator.getGeneratedHeaderMaps();
    assertThat(headerMaps, hasSize(1));
    Path headerMapFile = headerMaps.get(0);
    assertEquals(
        "_gen/GeneratedProject.xcodeproj/lib-public-headers.hmap",
        headerMapFile.toString());

    byte[] bytes = projectFilesystem.readFileIfItExists(headerMapFile).get().getBytes();
    HeaderMap map = HeaderMap.deserialize(bytes);
    assertEquals(2, map.getNumEntries());
    assertEquals(
        "\n// @gen" + "erated SignedSource<<00000000000000000000000000000000>>\n",
        map.lookup(""));
    assertEquals(
        "bar.h",
        map.lookup("lib/bar.h"));
  }

  @Test
  public void testIosLibraryRule() throws IOException {
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "lib").build())
            .setType(IosLibraryDescription.TYPE)
            .build();
    AppleNativeTargetDescriptionArg arg = iosLibraryDescription.createUnpopulatedConstructorArg();
    arg.configs = ImmutableMap.of(
        "Debug", ImmutableList.<Either<Path, ImmutableMap<String, String>>>of());
    arg.srcs = ImmutableList.of(
        AppleSource.ofSourcePathWithFlags(
            new Pair<SourcePath, String>(new TestSourcePath("foo.m"), "-foo")),
        AppleSource.ofSourcePath(new TestSourcePath("foo.h")),
        AppleSource.ofSourcePath(new TestSourcePath("bar.m")));
    arg.frameworks = ImmutableSortedSet.of();
    arg.deps = Optional.absent();
    BuildRule rule = iosLibraryDescription.createBuildRule(params, new BuildRuleResolver(), arg);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule),
        ImmutableSet.of(rule.getBuildTarget()));

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:lib");
    assertThat(target.isa(), equalTo("PBXNativeTarget"));
    assertThat(target.getProductType(), equalTo(PBXTarget.ProductType.IOS_LIBRARY));

    assertHasConfigurations(target, "Debug");
    assertEquals("Should have exact number of build phases", 2, target.getBuildPhases().size());
    assertHasSingletonSourcesPhaseWithSourcesAndFlags(
        target, ImmutableMap.of(
        "foo.m", Optional.of("-foo"),
        "bar.m", Optional.<String>absent()));

   // check headers
    {
      PBXBuildPhase headersBuildPhase =
          Iterables.find(target.getBuildPhases(), new Predicate<PBXBuildPhase>() {
            @Override
            public boolean apply(PBXBuildPhase input) {
              return input instanceof PBXHeadersBuildPhase;
            }
          });
      PBXBuildFile headerBuildFile = Iterables.getOnlyElement(headersBuildPhase.getFiles());

      String headerBuildFilePath = assertFileRefIsRelativeAndResolvePath(
          headerBuildFile.getFileRef());
      assertEquals(
          projectFilesystem.getRootPath().resolve("foo.h").toAbsolutePath().normalize().toString(),
          headerBuildFilePath);
    }

    // this target should not have an asset catalog build phase
    assertFalse(hasShellScriptPhaseToCompileAssetCatalogs(target));
  }

  @Test
  public void testMacosxFrameworkRule() throws IOException {
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "lib").build())
            .setType(MacosxFrameworkDescription.TYPE)
            .build();
    AppleNativeTargetDescriptionArg arg =
        macosxFrameworkDescription.createUnpopulatedConstructorArg();
    arg.infoPlist = Optional.of(Paths.get("Info.plist"));
    arg.configs = ImmutableMap.of(
        "Debug", ImmutableList.<Either<Path, ImmutableMap<String, String>>>of());
    arg.srcs = ImmutableList.of(
        AppleSource.ofSourcePathWithFlags(
            new Pair<SourcePath, String>(new TestSourcePath("foo.m"), "-foo")),
        AppleSource.ofSourcePath(new TestSourcePath("foo.h")),
        AppleSource.ofSourcePath(new TestSourcePath("bar.m")));
    arg.frameworks = ImmutableSortedSet.of();
    arg.deps = Optional.absent();
    BuildRule rule = macosxFrameworkDescription.createBuildRule(
        params,
        new BuildRuleResolver(),
        arg);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule),
        ImmutableSet.of(rule.getBuildTarget()));

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:lib");
    assertThat(target.isa(), equalTo("PBXNativeTarget"));
    PBXFileReference productReference = target.getProductReference();
    assertEquals("lib.framework", productReference.getName());
    assertEquals(Optional.of("wrapper.framework"), productReference.getExplicitFileType());

    assertHasConfigurations(target, "Debug");
    assertEquals("Should have exact number of build phases", 4, target.getBuildPhases().size());
    assertHasSingletonSourcesPhaseWithSourcesAndFlags(
        target, ImmutableMap.of(
        "foo.m", Optional.of("-foo"),
        "bar.m", Optional.<String>absent()));

   // check headers
    {
      PBXBuildPhase headersBuildPhase =
          Iterables.find(target.getBuildPhases(), new Predicate<PBXBuildPhase>() {
            @Override
            public boolean apply(PBXBuildPhase input) {
              return input instanceof PBXHeadersBuildPhase;
            }
          });
      PBXBuildFile headerBuildFile = Iterables.getOnlyElement(headersBuildPhase.getFiles());

      String headerBuildFilePath = assertFileRefIsRelativeAndResolvePath(
          headerBuildFile.getFileRef());
      assertEquals(
          projectFilesystem.getRootPath().resolve("foo.h").toAbsolutePath().normalize().toString(),
          headerBuildFilePath);
    }
  }

  @Test
  public void testMacosxBinaryRule() throws IOException {
    BuildRule depRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//dep", "dep").build(),
        ImmutableSortedSet.<BuildRule>of(),
        iosLibraryDescription);
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "binary").build())
        .setDeps(ImmutableSortedSet.of(depRule))
        .setType(MacosxBinaryDescription.TYPE)
        .build();

    AppleNativeTargetDescriptionArg arg = macosxBinaryDescription.createUnpopulatedConstructorArg();
    arg.infoPlist = Optional.of(Paths.get("Info.plist"));
    arg.configs = ImmutableMap.of(
        "Debug", ImmutableList.<Either<Path, ImmutableMap<String, String>>>of());
    arg.srcs = ImmutableList.of(
        AppleSource.ofSourcePathWithFlags(
            new Pair<SourcePath, String>(new TestSourcePath("foo.m"), "-foo")),
        AppleSource.ofSourcePath(new TestSourcePath("foo.h")));
    arg.frameworks = ImmutableSortedSet.of(
        "$SDKROOT/SystemFramework.framework",
        "$BUILT_PRODUCTS_DIR/LocalFramework.framework");
    arg.deps = Optional.absent();

    BuildRule rule = macosxBinaryDescription.createBuildRule(params, new BuildRuleResolver(), arg);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule),
        ImmutableSet.of(rule.getBuildTarget()));
    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:binary");
    assertEquals(target.getProductType(), PBXTarget.ProductType.MACOSX_BINARY);
    assertHasConfigurations(target, "Debug");
    assertEquals("Should have exact number of build phases", 5, target.getBuildPhases().size());
    assertHasSingletonSourcesPhaseWithSourcesAndFlags(
        target,
        ImmutableMap.of(
            "foo.m", Optional.of("-foo")));
    ProjectGeneratorTestUtils.assertHasSingletonFrameworksPhaseWithFrameworkEntries(
        target,
        ImmutableList.of(
            "$SDKROOT/SystemFramework.framework",
            "$BUILT_PRODUCTS_DIR/LocalFramework.framework",
            // Propagated library from deps.
            "$BUILT_PRODUCTS_DIR/libdep.a"));
    PBXCopyFilesBuildPhase copyFrameworksBuildPhase =
        ProjectGeneratorTestUtils.getSingletonPhaseByType(target, PBXCopyFilesBuildPhase.class);
    PBXBuildFile frameworkFile = Iterables.getOnlyElement(copyFrameworksBuildPhase.getFiles());
    assertEquals("LocalFramework.framework", frameworkFile.getFileRef().getName());
    assertEquals(copyFrameworksBuildPhase.getDstSubfolderSpec(),
        PBXCopyFilesBuildPhase.Destination.FRAMEWORKS);
  }

  @Test
  public void testIosTestRuleDefaultType() throws IOException {
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "test").build())
            .setType(IosTestDescription.TYPE)
            .build();

    IosTestDescription.Arg arg = iosTestDescription.createUnpopulatedConstructorArg();
    arg.infoPlist = Optional.of(Paths.get("Info.plist"));
    arg.configs = ImmutableMap.of(
        "Debug", ImmutableList.<Either<Path, ImmutableMap<String, String>>>of());
    arg.srcs = ImmutableList.of(AppleSource.ofSourcePathWithFlags(
            new Pair<SourcePath, String>(new TestSourcePath("foo.m"), "-foo")),
        AppleSource.ofSourcePath(new TestSourcePath("foo.h")));
    arg.frameworks = ImmutableSortedSet.of(
        "$SDKROOT/Foo.framework",
        "$DEVELOPER_DIR/XCTest.framework");
    arg.sourceUnderTest = ImmutableSortedSet.of();
    arg.testType = Optional.absent();
    arg.deps = Optional.absent();

    BuildRule rule = iosTestDescription.createBuildRule(params, new BuildRuleResolver(), arg);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule),
        ImmutableSet.of(rule.getBuildTarget()));

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:test");
    assertEquals("PBXNativeTarget", target.isa());
    assertEquals(PBXTarget.ProductType.IOS_TEST_OCTEST, target.getProductType());
    PBXFileReference productReference = target.getProductReference();
    assertEquals("test.octest", productReference.getName());
    assertEquals(Optional.of("wrapper.cfbundle"), productReference.getExplicitFileType());

    assertHasConfigurations(target, "Debug");
    assertEquals("Should have exact number of build phases", 4, target.getBuildPhases().size());
    assertHasSingletonSourcesPhaseWithSourcesAndFlags(
        target, ImmutableMap.of(
        "foo.m", Optional.of("-foo")));
    assertHasSingletonHeadersPhaseWithHeaders(
        target,
        "foo.h");

    ProjectGeneratorTestUtils.assertHasSingletonFrameworksPhaseWithFrameworkEntries(
        target, ImmutableList.of(
            "$DEVELOPER_DIR/XCTest.framework", "$SDKROOT/Foo.framework"));

    // this test does not depend on any asset catalogs, so verify a build phase for them does not
    // exist.
    assertFalse(hasShellScriptPhaseToCompileAssetCatalogs(target));
  }

  @Test
  public void testIosTestRuleXctestType() throws IOException {
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "test").build())
            .setType(IosTestDescription.TYPE)
            .build();

    IosTestDescription.Arg arg = iosTestDescription.createUnpopulatedConstructorArg();
    arg.infoPlist = Optional.of(Paths.get("Info.plist"));
    arg.configs = ImmutableMap.of(
        "Debug", ImmutableList.<Either<Path, ImmutableMap<String, String>>>of());
    arg.srcs = ImmutableList.of(
        AppleSource.ofSourcePathWithFlags(
            new Pair<SourcePath, String>(new TestSourcePath("foo.m"), "-foo")),
        AppleSource.ofSourcePath(new TestSourcePath("foo.h")));
    arg.frameworks = ImmutableSortedSet.of(
        "$SDKROOT/Foo.framework",
        "$DEVELOPER_DIR/XCTest.framework");
    arg.sourceUnderTest = ImmutableSortedSet.of();
    arg.testType = Optional.of("xctest");
    arg.deps = Optional.absent();

    BuildRule rule = iosTestDescription.createBuildRule(params, new BuildRuleResolver(), arg);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule),
        ImmutableSet.of(rule.getBuildTarget()));

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:test");
    assertEquals("PBXNativeTarget", target.isa());
    assertEquals(PBXTarget.ProductType.IOS_TEST_XCTEST, target.getProductType());
    PBXFileReference productReference = target.getProductReference();
    assertEquals("test.xctest", productReference.getName());
    assertEquals(Optional.of("wrapper.cfbundle"), productReference.getExplicitFileType());

    assertHasConfigurations(target, "Debug");
    assertEquals("Should have exact number of build phases", 4, target.getBuildPhases().size());
    assertHasSingletonSourcesPhaseWithSourcesAndFlags(
        target, ImmutableMap.of(
        "foo.m", Optional.of("-foo")));
    assertHasSingletonHeadersPhaseWithHeaders(
        target,
        "foo.h");

    ProjectGeneratorTestUtils.assertHasSingletonFrameworksPhaseWithFrameworkEntries(
        target, ImmutableList.of(
            "$DEVELOPER_DIR/XCTest.framework", "$SDKROOT/Foo.framework"));
  }

  @Test
  public void testIosTestRuleGathersTransitiveFrameworkDependencies() throws IOException {
    BuildRule libraryRule;
    BuildRule testRule;

    {
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "lib").build())
              .setType(IosLibraryDescription.TYPE)
              .build();
      AppleNativeTargetDescriptionArg arg = iosLibraryDescription.createUnpopulatedConstructorArg();
      arg.configs = ImmutableMap.of(
          "Debug", ImmutableList.<Either<Path, ImmutableMap<String, String>>>of());
      arg.srcs = ImmutableList.of(AppleSource.ofSourcePath(new TestSourcePath("foo.m")));
      arg.frameworks = ImmutableSortedSet.of("$SDKROOT/Library.framework");
      arg.deps = Optional.absent();
      libraryRule = iosLibraryDescription.createBuildRule(params, new BuildRuleResolver(), arg);
    }

    {
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "test").build())
          .setDeps(ImmutableSortedSet.of(libraryRule))
          .setType(IosTestDescription.TYPE)
          .build();

      IosTestDescription.Arg arg = iosTestDescription.createUnpopulatedConstructorArg();
      arg.infoPlist = Optional.of(Paths.get("Info.plist"));
      arg.configs = ImmutableMap.of(
          "Debug", ImmutableList.<Either<Path, ImmutableMap<String, String>>>of());
      arg.srcs = ImmutableList.of(AppleSource.ofSourcePath(new TestSourcePath("fooTest.m")));
      arg.frameworks = ImmutableSortedSet.of("$SDKROOT/Test.framework");
      arg.sourceUnderTest = ImmutableSortedSet.of();
      arg.testType = Optional.absent();
      arg.deps = Optional.absent();

      testRule = iosTestDescription.createBuildRule(params, new BuildRuleResolver(), arg);
    }

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(libraryRule, testRule),
        ImmutableSet.of(testRule.getBuildTarget()));

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:test");
    ProjectGeneratorTestUtils.assertHasSingletonFrameworksPhaseWithFrameworkEntries(
        target,
        ImmutableList.of(
            "$BUILT_PRODUCTS_DIR/liblib.a",
            "$SDKROOT/Library.framework",
            "$SDKROOT/Test.framework"));
  }

  @Test
  public void testIosBinaryRule() throws IOException {
    BuildRule depRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//dep", "dep").build(),
        ImmutableSortedSet.<BuildRule>of(),
        iosLibraryDescription);
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "binary").build())
        .setDeps(ImmutableSortedSet.of(depRule))
        .setType(IosBinaryDescription.TYPE)
        .build();
    AppleNativeTargetDescriptionArg arg = iosBinaryDescription.createUnpopulatedConstructorArg();
    arg.infoPlist = Optional.of(Paths.get("Info.plist"));
    arg.configs = ImmutableMap.of(
        "Debug", ImmutableList.<Either<Path, ImmutableMap<String, String>>>of());
    arg.srcs = ImmutableList.of(AppleSource.ofSourcePathWithFlags(
            new Pair<SourcePath, String>(new TestSourcePath("foo.m"), "-foo")),
        AppleSource.ofSourcePath(new TestSourcePath("foo.h")));
    arg.frameworks = ImmutableSortedSet.of("$SDKROOT/Foo.framework");
    arg.deps = Optional.absent();

    BuildRule rule = iosBinaryDescription.createBuildRule(params, new BuildRuleResolver(), arg);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule),
        ImmutableSet.of(rule.getBuildTarget()));
    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:binary");
    assertHasConfigurations(target, "Debug");
    assertEquals(target.getProductType(), PBXTarget.ProductType.IOS_BINARY);
    assertEquals("Should have exact number of build phases", 4, target.getBuildPhases().size());
    assertHasSingletonSourcesPhaseWithSourcesAndFlags(
        target,
        ImmutableMap.of(
            "foo.m", Optional.of("-foo")));
    ProjectGeneratorTestUtils.assertHasSingletonFrameworksPhaseWithFrameworkEntries(
        target,
        ImmutableList.of(
            "$SDKROOT/Foo.framework",
            // Propagated library from deps.
            "$BUILT_PRODUCTS_DIR/libdep.a"));

    // this test does not have a dependency on any asset catalogs, so verify no build phase for them
    // exists.
    assertFalse(hasShellScriptPhaseToCompileAssetCatalogs(target));
  }

  @Test
  public void testIosLibraryRuleWithGenruleDependency() throws IOException {

    BuildRuleResolver buildRuleResolver = new BuildRuleResolver();

    BuildRule genrule = GenruleBuilder.createGenrule(BuildTarget.builder("//foo", "script").build())
        .addSrc(new TestSourcePath("script/input.png").resolve())
        .setBash("echo \"hello world!\"")
        .setOut("helloworld.txt")
        .build();

    buildRuleResolver.addToIndex(genrule.getBuildTarget(), genrule);

    BuildTarget libTarget = BuildTarget.builder("//foo", "lib").build();
    BuildRuleParams libParams = new FakeBuildRuleParamsBuilder(libTarget)
        .setDeps(ImmutableSortedSet.of(genrule))
        .setType(IosLibraryDescription.TYPE)
        .build();
    AppleNativeTargetDescriptionArg arg = iosLibraryDescription.createUnpopulatedConstructorArg();
    arg.configs = ImmutableMap.of(
        "Debug", ImmutableList.<Either<Path, ImmutableMap<String, String>>>of());
    arg.srcs = ImmutableList.of(
        AppleSource.ofSourcePathWithFlags(
            new Pair<SourcePath, String>(new TestSourcePath("foo.m"), "-foo")),
        AppleSource.ofSourcePath(new TestSourcePath("foo.h")));
    arg.frameworks = ImmutableSortedSet.of();
    arg.deps = Optional.absent();
    BuildRule rule = iosLibraryDescription.createBuildRule(libParams, buildRuleResolver, arg);

    buildRuleResolver.addToIndex(libTarget, rule);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        buildRuleResolver, ImmutableSet.of(rule.getBuildTarget()));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    assertThat(project.getTargets(), hasSize(2));
    PBXTarget target = project.getTargets().get(0);
    assertThat(target.getName(), equalTo("//foo:lib"));
    assertThat(target.isa(), equalTo("PBXNativeTarget"));

    PBXShellScriptBuildPhase shellScriptBuildPhase =
        ProjectGeneratorTestUtils.getSingletonPhaseByType(
            target,
            PBXShellScriptBuildPhase.class);

    assertThat(
        Iterables.getOnlyElement(shellScriptBuildPhase.getInputPaths()),
        equalTo(".././script/input.png"));

    assertThat(
        shellScriptBuildPhase.getShellScript(),
        equalTo("/bin/bash -e -c 'echo \"hello world!\"'"));
  }

  @Test
  public void testIosBinaryRuleWithPostBuildScriptDependency() throws IOException {

    BuildRule scriptRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "post_build_script").build(),
        ImmutableSortedSet.<BuildRule>of(),
        iosPostprocessResourcesDescription,
        new Function<IosPostprocessResourcesDescription.Arg,
                     IosPostprocessResourcesDescription.Arg>() {
          @Override
          public IosPostprocessResourcesDescription.Arg apply(
            IosPostprocessResourcesDescription.Arg input) {

            input.cmd = Optional.of("script.sh");
            return input;
          }
        });

    BuildRule resourceRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "resource").build(),
        ImmutableSortedSet.<BuildRule>of(),
        iosResourceDescription,
        new Function<AppleResourceDescriptionArg, AppleResourceDescriptionArg>() {
          @Override
          public AppleResourceDescriptionArg apply(AppleResourceDescriptionArg input) {
            input.files = ImmutableSet.<SourcePath>of(new TestSourcePath("foo.png"));
            return input;
          }
        });

    BuildRule iosBinaryRule = createBuildRuleWithDefaults(
      BuildTarget.builder("//foo", "bin").build(),
      ImmutableSortedSet.of(scriptRule, resourceRule),
      iosBinaryDescription);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
      ImmutableSet.of(iosBinaryRule),
      ImmutableSet.of(iosBinaryRule.getBuildTarget()));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    assertThat(project.getTargets(), hasSize(2));
    PBXTarget target = project.getTargets().get(0);
    assertThat(target.getName(), equalTo("//foo:bin"));
    assertThat(target.isa(), equalTo("PBXNativeTarget"));

    PBXShellScriptBuildPhase shellScriptBuildPhase =
        ProjectGeneratorTestUtils.getSingletonPhaseByType(
            target,
            PBXShellScriptBuildPhase.class);

    assertThat(
        shellScriptBuildPhase.getShellScript(),
        equalTo("/bin/bash -e -c script.sh"));

    // Assert that the post-build script phase comes after resources are copied.
    assertThat(
        target.getBuildPhases().get(1),
        instanceOf(PBXResourcesBuildPhase.class));

    assertThat(
        target.getBuildPhases().get(2),
        instanceOf(PBXShellScriptBuildPhase.class));
  }

  @Test
  public void testCoreDataModelRuleAddsReference() throws IOException {
    BuildRule modelRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "model").build(),
        ImmutableSortedSet.<BuildRule>of(),
        coreDataModelDescription,
        new Function<CoreDataModelDescription.Arg, CoreDataModelDescription.Arg>() {
          @Override
          public CoreDataModelDescription.Arg apply(CoreDataModelDescription.Arg args) {
            args.path = new TestSourcePath("foo.xcdatamodel").asReference();
            return args;
          }
        });

    BuildRule libraryRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "lib").build(),
        ImmutableSortedSet.of(modelRule),
        iosLibraryDescription);


    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(libraryRule),
        ImmutableSet.of(libraryRule.getBuildTarget()));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXGroup targetGroup =
        project.getMainGroup().getOrCreateChildGroupByName(libraryRule.getFullyQualifiedName());
    PBXGroup resourcesGroup = targetGroup.getOrCreateChildGroupByName("Resources");

    assertThat(resourcesGroup.getChildren(), hasSize(1));

    PBXFileReference modelReference = (PBXFileReference) Iterables.get(
        resourcesGroup.getChildren(),
        0);
    assertEquals("foo.xcdatamodel", modelReference.getName());
  }

  @Test
  public void ruleToTargetMapContainsPBXTarget() throws IOException {
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "lib").build())
            .setType(IosLibraryDescription.TYPE)
            .build();
    AppleNativeTargetDescriptionArg arg = iosLibraryDescription.createUnpopulatedConstructorArg();
    arg.configs = ImmutableMap.of(
        "Debug", ImmutableList.<Either<Path, ImmutableMap<String, String>>>of());
    arg.srcs = ImmutableList.of(
        AppleSource.ofSourcePathWithFlags(
            new Pair<SourcePath, String>(new TestSourcePath("foo.m"), "-foo")),
        AppleSource.ofSourcePath(new TestSourcePath("foo.h")),
        AppleSource.ofSourcePath(new TestSourcePath("bar.m")));
    arg.frameworks = ImmutableSortedSet.of();
    arg.deps = Optional.absent();
    BuildRule rule = iosLibraryDescription.createBuildRule(params, new BuildRuleResolver(), arg);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule),
        ImmutableSet.of(rule.getBuildTarget()));

    projectGenerator.createXcodeProjects();

    assertEquals(rule, Iterables.getOnlyElement(
            projectGenerator.getBuildRuleToGeneratedTargetMap().keySet()));

    PBXTarget target = Iterables.getOnlyElement(
        projectGenerator.getBuildRuleToGeneratedTargetMap().values());
    assertHasSingletonSourcesPhaseWithSourcesAndFlags(
        target, ImmutableMap.of(
        "foo.m", Optional.of("-foo"),
        "bar.m", Optional.<String>absent()));
  }

  @Test
  public void shouldDiscoverDependenciesAndTests() throws IOException {
    // Create the following dep tree:
    // FooBin -has-test-> FooBinTest
    // |
    // V
    // FooLib -has-test-> FooLibTest
    // |                  |
    // V                  V
    // BarLib             BazLib -has-test-> BazLibTest
    //
    // Calling generate on FooBin should pull in everything except BazLibTest

    BuildRule barLib = createBuildRuleWithDefaults(
        BuildTarget.builder("//bar", "lib").build(),
        ImmutableSortedSet.<BuildRule>of(),
        iosLibraryDescription);
    BuildRule fooLib = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "lib").build(),
        ImmutableSortedSet.of(barLib),
        iosLibraryDescription);
    BuildRule fooBin = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "bin").build(),
        ImmutableSortedSet.of(fooLib),
        iosBinaryDescription);
    BuildRule bazLib = createBuildRuleWithDefaults(
        BuildTarget.builder("//baz", "lib").build(),
        ImmutableSortedSet.of(fooLib),
        iosLibraryDescription);

    BuildRule bazLibTest = createIosTestRule(
        BuildTarget.builder("//baz", "test").build(),
        ImmutableSortedSet.of(bazLib),
        ImmutableSortedSet.of(bazLib));
    BuildRule fooLibTest = createIosTestRule(
        BuildTarget.builder("//foo", "lib-test").build(),
        ImmutableSortedSet.of(fooLib),
        ImmutableSortedSet.of(fooLib, bazLib));
    BuildRule fooBinTest = createIosTestRule(
        BuildTarget.builder("//foo", "bin-test").build(),
        ImmutableSortedSet.of(fooBin),
        ImmutableSortedSet.of(fooBin));

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(barLib, fooLib, fooBin, bazLib, bazLibTest, fooLibTest, fooBinTest),
        ImmutableSet.of(fooBin.getBuildTarget()));
    projectGenerator.createXcodeProjects();

    assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:bin");
    assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:lib");
    assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//bar:lib");
    assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:bin-test");
    assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:lib-test");
    assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//baz:lib");
  }

  @Test
  public void generatedGidsForTargetsAreStable() throws IOException {
    BuildRule fooLib = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "foo").build(),
        ImmutableSortedSet.<BuildRule>of(),
        iosLibraryDescription);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(fooLib),
        ImmutableSet.of(fooLib.getBuildTarget()));
    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:foo");
    String expectedGID = String.format(
        "%08X%08X%08X", target.isa().hashCode(), target.getName().hashCode(), 0);
    assertEquals(
        "expected GID has correct value (value from which it's derived have not changed)",
        "E66DC04E2245423200000000", expectedGID);
    assertEquals("generated GID is same as expected", expectedGID, target.getGlobalID());
  }

  @Test
  public void resourcesInDependenciesPropagatesToBinariesAndTests() throws IOException {
    BuildRule resourceRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "res").build(),
        ImmutableSortedSet.<BuildRule>of(),
        iosResourceDescription,
        new Function<AppleResourceDescriptionArg, AppleResourceDescriptionArg>() {
          @Override
          public AppleResourceDescriptionArg apply(AppleResourceDescriptionArg input) {
            input.files = ImmutableSet.<SourcePath>of(new TestSourcePath("foo.png"));
            input.dirs = ImmutableSet.of(Paths.get("foodir"));
            return input;
          }
        });

    BuildRule libraryRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "lib").build(),
        ImmutableSortedSet.of(resourceRule),
        iosLibraryDescription);

    BuildRule testRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "test").build(),
        ImmutableSortedSet.of(libraryRule),
        iosTestDescription);

    BuildRule binaryRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "bin").build(),
        ImmutableSortedSet.of(libraryRule),
        iosBinaryDescription);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(resourceRule, libraryRule, testRule, binaryRule),
        ImmutableSet.of(testRule.getBuildTarget(), binaryRule.getBuildTarget()));
    projectGenerator.createXcodeProjects();

    PBXProject generatedProject = projectGenerator.getGeneratedProject();
    PBXTarget testTarget = assertTargetExistsAndReturnTarget(
        generatedProject,
        "//foo:test");
    assertHasSingletonResourcesPhaseWithEntries(testTarget, "foo.png", "foodir");
    PBXTarget binTarget = assertTargetExistsAndReturnTarget(
        generatedProject,
        "//foo:bin");
    assertHasSingletonResourcesPhaseWithEntries(binTarget, "foo.png", "foodir");
  }

  @Test
  public void assetCatalogsInDependenciesPropogatesToBinariesAndTests() throws IOException {
    BuildRule assetCatalogRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "asset_catalog").build(),
        ImmutableSortedSet.<BuildRule>of(),
        new AppleAssetCatalogDescription(),
        new Function<AppleAssetCatalogDescription.Arg, AppleAssetCatalogDescription.Arg>() {
          @Nullable
          @Override
          public AppleAssetCatalogDescription.Arg apply(
              @Nullable AppleAssetCatalogDescription.Arg input) {
            input.dirs = ImmutableSet.of(Paths.get("AssetCatalog.xcassets"));
            return input;
          }
        });

    BuildRule libraryRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "lib").build(),
        ImmutableSortedSet.of(assetCatalogRule),
        iosLibraryDescription);

    BuildRule testRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "test").build(),
        ImmutableSortedSet.of(libraryRule),
        iosTestDescription);

    BuildRule binaryRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "bin").build(),
        ImmutableSortedSet.of(libraryRule),
        iosBinaryDescription);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(assetCatalogRule, libraryRule, testRule, binaryRule),
        ImmutableSet.of(testRule.getBuildTarget(), binaryRule.getBuildTarget()));
    projectGenerator.createXcodeProjects();

    PBXProject generatedProject = projectGenerator.getGeneratedProject();
    PBXTarget testTarget = assertTargetExistsAndReturnTarget(
        generatedProject,
        "//foo:test");
    assertTrue(hasShellScriptPhaseToCompileAssetCatalogs(testTarget));
    PBXTarget binTarget = assertTargetExistsAndReturnTarget(
        generatedProject,
        "//foo:bin");
    assertTrue(hasShellScriptPhaseToCompileAssetCatalogs(binTarget));
  }

  @Test
  public void assetCatalogsBuildPhaseBuildsBothCommonAndBundledAssetCatalogs() throws IOException {
    BuildRule assetCatalog1 = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "asset_catalog1").build(),
        ImmutableSortedSet.<BuildRule>of(),
        new AppleAssetCatalogDescription(),
        new Function<AppleAssetCatalogDescription.Arg, AppleAssetCatalogDescription.Arg>() {
          @Nullable
          @Override
          public AppleAssetCatalogDescription.Arg apply(
              @Nullable AppleAssetCatalogDescription.Arg input) {
            input.dirs = ImmutableSet.of(Paths.get("AssetCatalog1.xcassets"));
            return input;
          }
        });
    BuildRule assetCatalog2 = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "asset_catalog2").build(),
        ImmutableSortedSet.<BuildRule>of(),
        new AppleAssetCatalogDescription(),
        new Function<AppleAssetCatalogDescription.Arg, AppleAssetCatalogDescription.Arg>() {
          @Nullable
          @Override
          public AppleAssetCatalogDescription.Arg apply(
              @Nullable AppleAssetCatalogDescription.Arg input) {
            input.dirs = ImmutableSet.of(Paths.get("AssetCatalog2.xcassets"));
            input.copyToBundles = Optional.of(Boolean.TRUE);
            return input;
          }
        });

    BuildRule libraryRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "lib").build(),
        ImmutableSortedSet.of(assetCatalog1, assetCatalog2),
        iosLibraryDescription);

    BuildRule testRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "test").build(),
        ImmutableSortedSet.of(libraryRule),
        iosTestDescription);

    BuildRule binaryRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "bin").build(),
        ImmutableSortedSet.of(libraryRule),
        iosBinaryDescription);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(assetCatalog1, assetCatalog2, libraryRule, testRule, binaryRule),
        ImmutableSet.of(testRule.getBuildTarget(), binaryRule.getBuildTarget()));
    projectGenerator.createXcodeProjects();

    PBXProject generatedProject = projectGenerator.getGeneratedProject();
    PBXTarget testTarget = assertTargetExistsAndReturnTarget(
        generatedProject,
        "//foo:test");
    assertTrue(hasShellScriptPhaseToCompileCommonAndSplitAssetCatalogs(testTarget));
    PBXTarget binTarget = assertTargetExistsAndReturnTarget(
        generatedProject,
        "//foo:bin");
    assertTrue(hasShellScriptPhaseToCompileCommonAndSplitAssetCatalogs(binTarget));
  }

  /**
   * The project configurations should have named entries corresponding to every existing target
   * configuration for targets in the project.
   */
  @Test
  public void generatedProjectConfigurationListIsUnionOfAllTargetConfigurations()
      throws IOException {
    BuildRule rule1 = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "rule1").build(),
        ImmutableSortedSet.<BuildRule>of(),
        iosLibraryDescription,
        new Function<AppleNativeTargetDescriptionArg, AppleNativeTargetDescriptionArg>() {
          @Override
          public AppleNativeTargetDescriptionArg apply(AppleNativeTargetDescriptionArg input) {
            input.configs = ImmutableMap.of(
                "Conf1", ImmutableList.<Either<Path, ImmutableMap<String, String>>>of(),
                "Conf2", ImmutableList.<Either<Path, ImmutableMap<String, String>>>of());
            return input;
          }
        });

    BuildRule rule2 = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "rule2").build(),
        ImmutableSortedSet.<BuildRule>of(),
        iosLibraryDescription,
        new Function<AppleNativeTargetDescriptionArg, AppleNativeTargetDescriptionArg>() {
          @Override
          public AppleNativeTargetDescriptionArg apply(AppleNativeTargetDescriptionArg input) {
            input.configs = ImmutableMap.of(
                "Conf2", ImmutableList.<Either<Path, ImmutableMap<String, String>>>of(),
                "Conf3", ImmutableList.<Either<Path, ImmutableMap<String, String>>>of());
            return input;
          }
        });

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule1, rule2),
        ImmutableSet.of(rule1.getBuildTarget(), rule2.getBuildTarget()));
    projectGenerator.createXcodeProjects();

    PBXProject generatedProject = projectGenerator.getGeneratedProject();
    Map<String, XCBuildConfiguration> configurations =
        generatedProject.getBuildConfigurationList().getBuildConfigurationsByName().asMap();
    assertThat(configurations, hasKey("Conf1"));
    assertThat(configurations, hasKey("Conf2"));
    assertThat(configurations, hasKey("Conf3"));
  }

  @Test
  public void shouldEmitFilesForBuildSettingPrefixedFrameworks() throws IOException {
    BuildRule rule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "rule").build(),
        ImmutableSortedSet.<BuildRule>of(),
        iosTestDescription,
        new Function<IosTestDescription.Arg, IosTestDescription.Arg>() {
          @Override
          public IosTestDescription.Arg apply(IosTestDescription.Arg input) {
            input.frameworks = ImmutableSortedSet.of(
                "$BUILT_PRODUCTS_DIR/libfoo.a",
                "$SDKROOT/libfoo.a",
                "$SOURCE_ROOT/libfoo.a");
            return input;
          }
        });
    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule),
        ImmutableSet.of(rule.getBuildTarget()));
    projectGenerator.createXcodeProjects();

    PBXProject generatedProject = projectGenerator.getGeneratedProject();
    PBXTarget target = assertTargetExistsAndReturnTarget(generatedProject, "//foo:rule");
    assertHasSingletonFrameworksPhaseWithFrameworkEntries(
        target,
        ImmutableList.of(
            "$BUILT_PRODUCTS_DIR/libfoo.a",
            "$SDKROOT/libfoo.a",
            "$SOURCE_ROOT/libfoo.a"));
  }

  @Test(expected = HumanReadableException.class)
  public void shouldRejectUnknownBuildSettingsInFrameworkEntries() throws IOException {
    BuildRule rule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "rule").build(),
        ImmutableSortedSet.<BuildRule>of(),
        iosTestDescription,
        new Function<IosTestDescription.Arg, IosTestDescription.Arg>() {
          @Override
          public IosTestDescription.Arg apply(IosTestDescription.Arg input) {
            input.frameworks = ImmutableSortedSet.of("$FOOBAR/libfoo.a");
            return input;
          }
        });

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule),
        ImmutableSet.of(rule.getBuildTarget()));
    projectGenerator.createXcodeProjects();
  }

  @Test
  public void targetGidShouldReuseIfNameMatchInExistingProject() throws IOException {
    String projectData =
      "// !$*UTF8*$!\n" +
      "{\n" +
      "  archiveVersion = 1;\n" +
      "  classes = {};\n" +
      "  objectVersion = 46;\n" +
      "  objects = {\n" +
      "    12345 /* libFoo.a */ = {isa = PBXFileReference; explicitFileType = " +
      "      archive.ar; path = libFoo.a; sourceTree = BUILT_PRODUCTS_DIR; };\n" +
      "    ABCDE /* //foo:lib */ = {\n" +
      "      isa = PBXNativeTarget;\n" +
      "      buildConfigurationList = 7CC5FDCE622E7F7B4F76AB38 /* Build configuration list for " +
      "        PBXNativeTarget \"Foo\" */;\n" +
      "      buildPhases = (\n" +
      "      );\n" +
      "      buildRules = (\n" +
      "      );\n" +
      "      dependencies = (\n" +
      "      );\n" +
      "      name = \"//foo:lib\";\n" +
      "      productName = Foo;\n" +
      "      productReference = 12345 /* libFoo.a */;\n" +
      "      productType = \"com.apple.product-type.library.static\";\n" +
      "    };\n" +
      "  };\n" +
      "}";
    projectFilesystem.writeContentsToPath(projectData, OUTPUT_PROJECT_FILE_PATH);
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "lib").build())
            .setType(IosLibraryDescription.TYPE)
            .build();
    AppleNativeTargetDescriptionArg arg = iosLibraryDescription.createUnpopulatedConstructorArg();
    arg.configs = ImmutableMap.of();
    arg.srcs = ImmutableList.of();
    arg.frameworks = ImmutableSortedSet.of();
    arg.deps = Optional.absent();

    BuildRule rule = iosLibraryDescription.createBuildRule(params, new BuildRuleResolver(), arg);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule),
        ImmutableSet.of(rule.getBuildTarget()));

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:lib");
    // Ensure the GID for the target is the same as the one previously on disk.
    assertThat(target.getGlobalID(), equalTo("ABCDE"));
  }

  @Test
  public void generatedSourceTargetGidShouldReuseIfNameMatchInExistingProject() throws IOException {
    String projectData =
      "// !$*UTF8*$!\n" +
      "{\n" +
      "  archiveVersion = 1;\n" +
      "  classes = {};\n" +
      "  objectVersion = 46;\n" +
      "  objects = {\n" +
      "    /* Begin PBXAggregateTarget section */\n" +
      "            93C1B2AA1B49969700000000 /* GeneratedSignedSourceTarget */ = {\n" +
      "                         isa = PBXAggregateTarget;\n" +
      "                         buildConfigurationList = 64D2EE2518E12BBC00773179 /* Build " +
      "configuration list for PBXAggregateTarget \"GeneratedSignedSourceTarget\" */;\n" +
      "                         buildPhases = (\n" +
      "                                 E1F174220000000000000000 /* ShellScript */,\n" +
      "                         );\n" +
      "                         dependencies = (\n" +
      "                         );\n" +
      "                         name = GeneratedSignedSourceTarget;\n" +
      "                         productName = GeneratedSignedSourceTarget;\n" +
      "                 };\n" +
      "    /* End PBXAggregateTarget section */\n" +
      "    };\n" +
      "  };\n" +
      "}";
    projectFilesystem.writeContentsToPath(projectData, OUTPUT_PROJECT_FILE_PATH);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<BuildRule>of(),
        ImmutableSet.<BuildTarget>of());

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "GeneratedSignedSourceTarget");
    // Ensure the GID for the target is the same as the one previously on disk.
    assertThat(target.getGlobalID(), equalTo("93C1B2AA1B49969700000000"));
  }

  @Test
  public void generatedSourceTargetShouldHaveConfigsWithSameNamesAsProjectConfigs()
      throws IOException {
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "lib").build())
            .setType(IosLibraryDescription.TYPE)
            .build();
    AppleNativeTargetDescriptionArg arg = iosLibraryDescription.createUnpopulatedConstructorArg();
    arg.configs = ImmutableMap.of(
        "Debug", ImmutableList.<Either<Path, ImmutableMap<String, String>>>of());
    arg.srcs = ImmutableList.of();
    arg.frameworks = ImmutableSortedSet.of();
    arg.deps = Optional.absent();
    BuildRule rule = iosLibraryDescription.createBuildRule(params, new BuildRuleResolver(), arg);
    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule),
        ImmutableSet.of(rule.getBuildTarget()));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    Set<String> projectConfigurationNames =
      project.getBuildConfigurationList().getBuildConfigurationsByName().asMap().keySet();
    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "GeneratedSignedSourceTarget");
    Set<String> generatedSignedSourceTargetNames =
      target.getBuildConfigurationList().getBuildConfigurationsByName().asMap().keySet();
    assertEquals(ImmutableSet.of("Debug"), projectConfigurationNames);
    assertEquals(projectConfigurationNames, generatedSignedSourceTargetNames);
  }

  @Test
  public void testGeneratedProjectIsNotReadOnlyIfOptionNotSpecified() throws IOException {
    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<BuildRule>of(),
        ImmutableSet.<BuildTarget>of());

    projectGenerator.createXcodeProjects();

    assertTrue(fakeProjectFilesystem.getFileAttributesAtPath(OUTPUT_PROJECT_FILE_PATH).isEmpty());
  }

  @Test
  public void testGeneratedProjectIsReadOnlyIfOptionSpecified() throws IOException {
    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        createPartialGraphFromBuildRules(ImmutableSet.<BuildRule>of()),
        ImmutableSet.<BuildTarget>of(),
        ImmutableSet.of(ProjectGenerator.Option.GENERATE_READ_ONLY_FILES));

    projectGenerator.createXcodeProjects();

    ImmutableSet<PosixFilePermission> permissions =
      ImmutableSet.of(
          PosixFilePermission.OWNER_READ,
          PosixFilePermission.GROUP_READ,
          PosixFilePermission.OTHERS_READ);
    FileAttribute<?> expectedAttribute = PosixFilePermissions.asFileAttribute(permissions);
    // This is lame; Java's PosixFilePermissions class doesn't
    // implement equals() or hashCode() in its FileAttribute anonymous
    // class (http://tinyurl.com/nznhfhy).  So instead of comparing
    // the sets, we have to pull out the attribute and check its value
    // for equality.
    FileAttribute<?> actualAttribute =
      Iterables.getOnlyElement(
          fakeProjectFilesystem.getFileAttributesAtPath(OUTPUT_PROJECT_FILE_PATH));
    assertEquals(
        expectedAttribute.value(),
        actualAttribute.value());
  }

  private ProjectGenerator createProjectGeneratorForCombinedProject(
      BuildRuleResolver resolver, ImmutableSet<BuildTarget> initialBuildTargets) {
    return createProjectGeneratorForCombinedProject(
        createPartialGraphFromBuildRuleResolver(resolver),
        initialBuildTargets);
  }

  private ProjectGenerator createProjectGeneratorForCombinedProject(
      ImmutableSet<BuildRule> rules, ImmutableSet<BuildTarget> initialBuildTargets) {
    return createProjectGeneratorForCombinedProject(
        createPartialGraphFromBuildRules(rules),
        initialBuildTargets);
  }

  private ProjectGenerator createProjectGeneratorForCombinedProject(
      PartialGraph partialGraph, ImmutableSet<BuildTarget> initialBuildTargets) {
    return createProjectGeneratorForCombinedProject(
        partialGraph,
        initialBuildTargets,
        ImmutableSet.<ProjectGenerator.Option>of());
  }

  private ProjectGenerator createProjectGeneratorForCombinedProject(
      PartialGraph partialGraph,
      ImmutableSet<BuildTarget> initialBuildTargets,
      ImmutableSet<ProjectGenerator.Option> projectGeneratorOptions) {
    ImmutableSet<ProjectGenerator.Option> options = ImmutableSet.<ProjectGenerator.Option>builder()
        .addAll(projectGeneratorOptions)
        .addAll(ProjectGenerator.COMBINED_PROJECT_OPTIONS)
        .add(ProjectGenerator.Option.GENERATE_HEADER_MAPS_FOR_LIBRARY_TARGETS)
        .build();

    return new ProjectGenerator(
        partialGraph,
        initialBuildTargets,
        projectFilesystem,
        executionContext,
        OUTPUT_DIRECTORY,
        PROJECT_NAME,
        options);
  }

  private String assertFileRefIsRelativeAndResolvePath(PBXReference fileRef) {
    assert(!fileRef.getPath().startsWith("/"));
    assertEquals(
        "file path should be relative to project directory",
        PBXReference.SourceTree.SOURCE_ROOT,
        fileRef.getSourceTree());
    return projectFilesystem.resolve(OUTPUT_DIRECTORY).resolve(fileRef.getPath())
        .normalize().toString();
  }

  private BuildRule createIosTestRule(
      BuildTarget target,
      ImmutableSortedSet<BuildRule> sourceUnderTest,
      ImmutableSortedSet<BuildRule> deps) {
    BuildRuleParams buildRuleParams = new FakeBuildRuleParamsBuilder(target)
        .setDeps(deps)
        .setType(IosTestDescription.TYPE)
        .build();
    IosTestDescription.Arg arg = iosTestDescription.createUnpopulatedConstructorArg();
    arg.configs = ImmutableMap.of();
    arg.infoPlist = Optional.of(Paths.get("Info.plist"));
    arg.frameworks = ImmutableSortedSet.of();
    arg.srcs = ImmutableList.of();
    arg.sourceUnderTest = sourceUnderTest;
    arg.testType = Optional.absent();
    arg.deps = Optional.absent();
    return iosTestDescription.createBuildRule(buildRuleParams, new BuildRuleResolver(), arg);
  }

  private void assertHasConfigurations(PBXTarget target, String... names) {
    Map<String, XCBuildConfiguration> buildConfigurationMap =
        target.getBuildConfigurationList().getBuildConfigurationsByName().asMap();
    assertEquals(
        "Configuration list has expected number of entries",
        names.length, buildConfigurationMap.size());

    for (String name : names) {
      XCBuildConfiguration configuration = buildConfigurationMap.get(name);

      assertNotNull("Configuration entry exists", configuration);
      assertEquals("Configuration name is same as key", name, configuration.getName());
      assertTrue(
          "Configuration has xcconfig file",
          configuration.getBaseConfigurationReference().getPath().endsWith(".xcconfig"));
    }
  }

  private void assertHasSingletonSourcesPhaseWithSourcesAndFlags(
      PBXTarget target,
      ImmutableMap<String, Optional<String>> sourcesAndFlags) {

    PBXSourcesBuildPhase sourcesBuildPhase =
        ProjectGeneratorTestUtils.getSingletonPhaseByType(target, PBXSourcesBuildPhase.class);

    assertEquals(
        "Sources build phase should have correct number of sources",
        sourcesAndFlags.size(), sourcesBuildPhase.getFiles().size());

    // map keys to absolute paths
    ImmutableMap.Builder<String, Optional<String>> absolutePathFlagMapBuilder =
        ImmutableMap.builder();
    for (Map.Entry<String, Optional<String>> name : sourcesAndFlags.entrySet()) {
      absolutePathFlagMapBuilder.put(
          projectFilesystem.getRootPath().resolve(name.getKey()).toAbsolutePath()
              .normalize().toString(),
          name.getValue());
    }
    ImmutableMap<String, Optional<String>> absolutePathFlagMap = absolutePathFlagMapBuilder.build();

    for (PBXBuildFile file : sourcesBuildPhase.getFiles()) {
      String filePath = assertFileRefIsRelativeAndResolvePath(file.getFileRef());
      Optional<String> flags = absolutePathFlagMap.get(filePath);
      assertNotNull("Source file is expected", flags);
      if (flags.isPresent()) {
        assertTrue("Build file should have settings dictionary", file.getSettings().isPresent());

        NSDictionary buildFileSettings = file.getSettings().get();
        NSString compilerFlags = (NSString) buildFileSettings.get("COMPILER_FLAGS");

        assertNotNull("Build file settings should have COMPILER_FLAGS entry", compilerFlags);
        assertEquals(
            "Build file settings should be expected value",
            flags.get(), compilerFlags.getContent());
      } else {
        assertFalse(
            "Build file should not have settings dictionary", file.getSettings().isPresent());
      }
    }
  }

  private void assertHasSingletonHeadersPhaseWithHeaders(
      PBXTarget target,
      String... headers) {

    PBXHeadersBuildPhase headersBuildPhase =
        ProjectGeneratorTestUtils.getSingletonPhaseByType(target, PBXHeadersBuildPhase.class);

    assertEquals(
        "Headers build phase should have correct number of headers",
        headers.length, headersBuildPhase.getFiles().size());

    // map keys to absolute paths
    ImmutableSet.Builder<String> expectedHeadersSetBuilder = ImmutableSet.builder();
    for (String header : headers) {
      expectedHeadersSetBuilder.add(
          projectFilesystem.getRootPath().resolve(header).toAbsolutePath()
              .normalize().toString());
    }
    ImmutableSet<String> expectedHeadersSet = expectedHeadersSetBuilder.build();

    for (PBXBuildFile file : headersBuildPhase.getFiles()) {
      String header = assertFileRefIsRelativeAndResolvePath(file.getFileRef());
      assertTrue(
          "Header should be in list of expected headers: " + header,
          expectedHeadersSet.contains(header));
    }
  }

  private void assertHasSingletonResourcesPhaseWithEntries(PBXTarget target, String... resources) {
    PBXResourcesBuildPhase buildPhase =
        ProjectGeneratorTestUtils.getSingletonPhaseByType(target, PBXResourcesBuildPhase.class);
    assertEquals("Resources phase should have right number of elements",
        resources.length, buildPhase.getFiles().size());

    ImmutableSet.Builder<String> expectedResourceSetBuilder = ImmutableSet.builder();
    for (String resource : resources) {
      expectedResourceSetBuilder.add(
          projectFilesystem.getRootPath().resolve(resource).toAbsolutePath()
              .normalize().toString());
    }
    ImmutableSet<String> expectedResourceSet = expectedResourceSetBuilder.build();

    for (PBXBuildFile file : buildPhase.getFiles()) {
      String source = assertFileRefIsRelativeAndResolvePath(file.getFileRef());
      assertTrue(
          "Resource should be in list of expected resources: " + source,
          expectedResourceSet.contains(source));
    }
  }

  private boolean hasShellScriptPhaseToCompileAssetCatalogs(PBXTarget target) {
    boolean found = false;
    for (PBXBuildPhase phase : target.getBuildPhases()) {
      if (phase.getClass().equals(PBXShellScriptBuildPhase.class)) {
        PBXShellScriptBuildPhase shellScriptBuildPhase = (PBXShellScriptBuildPhase) phase;
        if (shellScriptBuildPhase.getShellScript().contains("compile_asset_catalogs")) {
          found = true;
        }
      }
    }

    return found;
  }

  private boolean hasShellScriptPhaseToCompileCommonAndSplitAssetCatalogs(PBXTarget target) {
    PBXShellScriptBuildPhase assetCatalogBuildPhase = null;
    for (PBXBuildPhase phase : target.getBuildPhases()) {
      if (phase.getClass().equals(PBXShellScriptBuildPhase.class)) {
        PBXShellScriptBuildPhase shellScriptBuildPhase = (PBXShellScriptBuildPhase) phase;
        if (shellScriptBuildPhase.getShellScript().contains("compile_asset_catalogs")) {
          assetCatalogBuildPhase = shellScriptBuildPhase;
        }
      }
    }

    assertNotNull(assetCatalogBuildPhase);

    boolean foundCommonAssetCatalogCompileCommand = false;
    boolean foundSplitAssetCatalogCompileCommand = false;
    String[] lines = assetCatalogBuildPhase.getShellScript().split("\\n");
    for (String line : lines) {
      if (line.contains("compile_asset_catalogs")) {
        if (line.contains(" -b ")) {
          foundSplitAssetCatalogCompileCommand = true;
        } else {
          // There can be only one
          assertFalse(foundCommonAssetCatalogCompileCommand);
          foundCommonAssetCatalogCompileCommand = true;
        }
      }
    }

    return foundCommonAssetCatalogCompileCommand && foundSplitAssetCatalogCompileCommand;
  }
}
