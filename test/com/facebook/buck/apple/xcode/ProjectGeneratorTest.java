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

import static com.facebook.buck.apple.xcode.ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget;
import static com.facebook.buck.apple.xcode.ProjectGeneratorTestUtils.createBuildRuleWithDefaults;
import static com.facebook.buck.apple.xcode.ProjectGeneratorTestUtils.createPartialGraphFromBuildRuleResolver;
import static com.facebook.buck.apple.xcode.ProjectGeneratorTestUtils.createPartialGraphFromBuildRules;
import static org.hamcrest.CoreMatchers.either;
import static org.hamcrest.CoreMatchers.endsWith;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.Matchers.containsString;
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
import com.facebook.buck.apple.AppleResourceDescriptionArg;
import com.facebook.buck.apple.IosBinaryDescription;
import com.facebook.buck.apple.IosLibraryDescription;
import com.facebook.buck.apple.IosResourceDescription;
import com.facebook.buck.apple.IosTestDescription;
import com.facebook.buck.apple.XcodeNativeDescription;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildFile;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXContainerItemProxy;
import com.facebook.buck.apple.xcode.xcodeproj.PBXFileReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXGroup;
import com.facebook.buck.apple.xcode.xcodeproj.PBXHeadersBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXResourcesBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXShellScriptBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXSourcesBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTargetDependency;
import com.facebook.buck.apple.xcode.xcodeproj.XCBuildConfiguration;
import com.facebook.buck.codegen.SourceSigner;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DescribedRule;
import com.facebook.buck.rules.FakeAbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.FakeBuildRuleParams;
import com.facebook.buck.rules.FileSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.coercer.AppleSource;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.rules.coercer.Pair;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

public class ProjectGeneratorTest {

  private static final Path OUTPUT_DIRECTORY = Paths.get("_gen");
  private static final String PROJECT_NAME = "GeneratedProject";
  private static final String PROJECT_CONTAINER = PROJECT_NAME + ".xcodeproj";
  private static final Path OUTPUT_PROJECT_BUNDLE_PATH =
      OUTPUT_DIRECTORY.resolve(PROJECT_CONTAINER);
  private static final Path OUTPUT_PROJECT_FILE_PATH =
      OUTPUT_PROJECT_BUNDLE_PATH.resolve("project.pbxproj");

  private ProjectFilesystem projectFilesystem;
  private ExecutionContext executionContext;
  private XcodeNativeDescription xcodeNativeDescription;
  private IosLibraryDescription iosLibraryDescription;
  private IosTestDescription iosTestDescription;
  private IosBinaryDescription iosBinaryDescription;
  private IosResourceDescription iosResourceDescription;

  @Before
  public void setUp() {
    projectFilesystem = new FakeProjectFilesystem();
    executionContext = TestExecutionContext.newInstance();
    xcodeNativeDescription = new XcodeNativeDescription();
    iosLibraryDescription = new IosLibraryDescription();
    iosTestDescription = new IosTestDescription();
    iosBinaryDescription = new IosBinaryDescription();
    iosResourceDescription = new IosResourceDescription();
  }

  @Test
  public void testProjectStructureForEmptyProject() throws IOException {
    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<BuildRule>of(),
        ImmutableSet.<BuildTarget>of());

    Path outputWorkspaceBundlePath = OUTPUT_DIRECTORY.resolve(PROJECT_NAME + ".xcworkspace");
    Path outputWorkspaceFilePath = outputWorkspaceBundlePath.resolve("contents.xcworkspacedata");

    Path outputSchemeFolderPath = OUTPUT_PROJECT_BUNDLE_PATH.resolve(
        Paths.get("xcshareddata", "xcschemes"));
    Path outputSchemePath = outputSchemeFolderPath.resolve("Scheme.xcscheme");

    projectGenerator.createXcodeProjects();

    Optional<String> pbxproj = projectFilesystem.readFileIfItExists(OUTPUT_PROJECT_FILE_PATH);
    assertTrue(pbxproj.isPresent());

    Optional<String> xcworkspacedata =
        projectFilesystem.readFileIfItExists(outputWorkspaceFilePath);
    assertTrue(xcworkspacedata.isPresent());

    Optional<String> xcscheme = projectFilesystem.readFileIfItExists(outputSchemePath);
    assertTrue(xcscheme.isPresent());
  }

  @Test
  public void testSchemeGeneration() throws IOException {
    BuildRule rootRule = createBuildRuleWithDefaults(
        new BuildTarget("//foo", "root"),
        ImmutableSortedSet.<BuildRule>of(),
        iosLibraryDescription);
    BuildRule leftRule = createBuildRuleWithDefaults(
        new BuildTarget("//foo", "left"),
        ImmutableSortedSet.of(rootRule),
        iosLibraryDescription);
    BuildRule rightRule = createBuildRuleWithDefaults(
        new BuildTarget("//foo", "right"),
        ImmutableSortedSet.of(rootRule),
        iosLibraryDescription);
    BuildRule childRule = createBuildRuleWithDefaults(
        new BuildTarget("//foo", "child"),
        ImmutableSortedSet.of(leftRule, rightRule),
        iosLibraryDescription);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rootRule, leftRule, rightRule, childRule),
        ImmutableSet.of(childRule.getBuildTarget()));

    // Generate the project.
    projectGenerator.createXcodeProjects();

    // Verify the scheme.
    PBXProject project = projectGenerator.getGeneratedProject();
    Map<String, String> targetNameToGid = Maps.newHashMap();
    for (PBXTarget target : project.getTargets()) {
      targetNameToGid.put(target.getName(), target.getGlobalID());
    }

    XCScheme scheme = Preconditions.checkNotNull(projectGenerator.getGeneratedScheme());
    List<String> actualOrdering = Lists.newArrayList();
    for (XCScheme.BuildActionEntry entry : scheme.getBuildAction()) {
      actualOrdering.add(entry.getBlueprintIdentifier());
      assertEquals(PROJECT_CONTAINER, entry.getContainerRelativePath());
    }

    List<String> expectedOrdering1 = ImmutableList.of(
        targetNameToGid.get("//foo:root"),
        targetNameToGid.get("//foo:left"),
        targetNameToGid.get("//foo:right"),
        targetNameToGid.get("//foo:child"));
    List<String> expectedOrdering2 = ImmutableList.of(
        targetNameToGid.get("//foo:root"),
        targetNameToGid.get("//foo:right"),
        targetNameToGid.get("//foo:left"),
        targetNameToGid.get("//foo:child"));
    assertThat(actualOrdering, either(equalTo(expectedOrdering1)).or(equalTo(expectedOrdering2)));
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
    BuildRuleParams params = new FakeBuildRuleParams(
        new BuildTarget("//foo", "lib"), ImmutableSortedSet.<BuildRule>of());
    IosLibraryDescription.Arg arg = iosLibraryDescription.createUnpopulatedConstructorArg();
    arg.configs = ImmutableMap.of(
        "Debug", ImmutableList.<Either<Path, ImmutableMap<String, String>>>of());
    arg.headers = ImmutableList.of();
    arg.srcs = ImmutableList.of(
        AppleSource.ofSourceGroup(
            new Pair<>(
                "Group1",
                ImmutableList.of(
                    AppleSource.ofSourcePath(new FileSourcePath("foo.m")),
                    AppleSource.ofSourcePathWithFlags(
                        new Pair<SourcePath, String>(new FileSourcePath("bar.m"), "-Wall"))))),
        AppleSource.ofSourceGroup(
            new Pair<>(
                "Group2",
                ImmutableList.of(
                    AppleSource.ofSourcePath(new FileSourcePath("baz.m")),
                    AppleSource.ofSourcePathWithFlags(
                        new Pair<SourcePath, String>(new FileSourcePath("blech.m"), "-fobjc-arc"))))
        ));
    arg.frameworks = ImmutableSortedSet.of();
    BuildRule rule = new DescribedRule(
        IosLibraryDescription.TYPE,
        iosLibraryDescription.createBuildable(params, arg), params);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule),
        ImmutableSet.of(rule.getBuildTarget()));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXGroup targetGroup =
        project.getMainGroup().getOrCreateChildGroupByName(rule.getFullyQualifiedName());
    PBXGroup sourcesGroup = targetGroup.getOrCreateChildGroupByName("Sources");

    assertThat(sourcesGroup.getChildren(), hasSize(2));

    PBXGroup group1 = (PBXGroup)Iterables.get(sourcesGroup.getChildren(), 0);
    assertEquals("Group1", group1.getName());
    assertThat(group1.getChildren(), hasSize(2));
    PBXFileReference fileRefFoo = (PBXFileReference)Iterables.get(group1.getChildren(), 0);
    assertEquals("foo.m", fileRefFoo.getName());
    PBXFileReference fileRefBar = (PBXFileReference)Iterables.get(group1.getChildren(), 1);
    assertEquals("bar.m", fileRefBar.getName());

    PBXGroup group2 = (PBXGroup)Iterables.get(sourcesGroup.getChildren(), 1);
    assertEquals("Group2", group2.getName());
    assertThat(group2.getChildren(), hasSize(2));
    PBXFileReference fileRefBaz = (PBXFileReference)Iterables.get(group2.getChildren(), 0);
    assertEquals("baz.m", fileRefBaz.getName());
    PBXFileReference fileRefBlech = (PBXFileReference)Iterables.get(group2.getChildren(), 1);
    assertEquals("blech.m", fileRefBlech.getName());
  }

  @Test
  public void testLibraryHeaderGroups() throws IOException {
    BuildRuleParams params = new FakeBuildRuleParams(
        new BuildTarget("//foo", "lib"), ImmutableSortedSet.<BuildRule>of());
    IosLibraryDescription.Arg arg = iosLibraryDescription.createUnpopulatedConstructorArg();
    arg.configs = ImmutableMap.of(
        "Debug", ImmutableList.<Either<Path, ImmutableMap<String, String>>>of());
    arg.srcs = ImmutableList.of();
    arg.headers = ImmutableList.of(
        AppleSource.ofSourceGroup(
            new Pair<>(
                "HeaderGroup1",
                ImmutableList.of(
                    AppleSource.ofSourcePath(new FileSourcePath("foo.h")),
                    AppleSource.ofSourcePathWithFlags(
                        new Pair<SourcePath, String>(new FileSourcePath("bar.h"), "public"))))),
        AppleSource.ofSourceGroup(
            new Pair<>(
                "HeaderGroup2",
                ImmutableList.of(
                    AppleSource.ofSourcePath(new FileSourcePath("baz.h")),
                    AppleSource.ofSourcePathWithFlags(
                        new Pair<SourcePath, String>(new FileSourcePath("blech.h"), "private"))))
        ));
    arg.frameworks = ImmutableSortedSet.of();
    BuildRule rule = new DescribedRule(
        IosLibraryDescription.TYPE,
        iosLibraryDescription.createBuildable(params, arg), params);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule),
        ImmutableSet.of(rule.getBuildTarget()));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXGroup targetGroup =
        project.getMainGroup().getOrCreateChildGroupByName(rule.getFullyQualifiedName());
    PBXGroup headersGroup = targetGroup.getOrCreateChildGroupByName("Headers");

    assertThat(headersGroup.getChildren(), hasSize(2));

    PBXGroup group1 = (PBXGroup)Iterables.get(headersGroup.getChildren(), 0);
    assertEquals("HeaderGroup1", group1.getName());
    assertThat(group1.getChildren(), hasSize(2));
    PBXFileReference fileRefFoo = (PBXFileReference)Iterables.get(group1.getChildren(), 0);
    assertEquals("foo.h", fileRefFoo.getName());
    PBXFileReference fileRefBar = (PBXFileReference)Iterables.get(group1.getChildren(), 1);
    assertEquals("bar.h", fileRefBar.getName());

    PBXGroup group2 = (PBXGroup)Iterables.get(headersGroup.getChildren(), 1);
    assertEquals("HeaderGroup2", group2.getName());
    assertThat(group2.getChildren(), hasSize(2));
    PBXFileReference fileRefBaz = (PBXFileReference)Iterables.get(group2.getChildren(), 0);
    assertEquals("baz.h", fileRefBaz.getName());
    PBXFileReference fileRefBlech = (PBXFileReference)Iterables.get(group2.getChildren(), 1);
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
  }

  @Test
  public void testXcodeNativeRule() throws IOException {
    BuildRule rule = createBuildRuleWithDefaults(
        new BuildTarget("//foo", "rule"),
        ImmutableSortedSet.<BuildRule>of(),
        xcodeNativeDescription,
        new Function<XcodeNativeDescription.Arg, XcodeNativeDescription.Arg>() {
          @Override
          public XcodeNativeDescription.Arg apply(XcodeNativeDescription.Arg input) {
            input.product = "libfoo.a";
            input.targetGid = "00DEADBEEF";
            input.projectContainerPath = new FileSourcePath("foo.xcodeproj");
            return input;
          }
        });
    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule),
        ImmutableSet.of(rule.getBuildTarget()));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    assertThat(project.getTargets(), hasSize(2));
    PBXTarget target = project.getTargets().get(0);
    assertThat(target.getName(), equalTo("//foo:rule"));
    assertThat(target.isa(), equalTo("PBXAggregateTarget"));
    assertThat(target.getDependencies(), hasSize(1));
    PBXTargetDependency dependency = target.getDependencies().get(0);
    PBXContainerItemProxy proxy = dependency.getTargetProxy();
    String containerPath = assertFileRefIsRelativeAndResolvePath(proxy.getContainerPortal());
    assertThat(containerPath, endsWith("foo.xcodeproj"));
    assertThat(proxy.getRemoteGlobalIDString(), equalTo("00DEADBEEF"));

    verifyGeneratedSignedSourceTarget(project.getTargets().get(1));

    PBXGroup projectReferenceGroup =
        project.getMainGroup().getOrCreateChildGroupByName("Project References");
    assertThat(projectReferenceGroup.getChildren(), hasSize(1));
    assertThat(
        projectReferenceGroup.getChildren(), hasItem(sameInstance(proxy.getContainerPortal())));
  }

  @Test
  public void testIosLibraryRule() throws IOException {

    BuildRuleParams params = new FakeBuildRuleParams(
        new BuildTarget("//foo", "lib"), ImmutableSortedSet.<BuildRule>of());
    IosLibraryDescription.Arg arg = iosLibraryDescription.createUnpopulatedConstructorArg();
    arg.configs = ImmutableMap.of(
        "Debug", ImmutableList.<Either<Path, ImmutableMap<String, String>>>of());
    arg.headers = ImmutableList.of(AppleSource.ofSourcePath(new FileSourcePath("foo.h")));
    arg.srcs = ImmutableList.of(
        AppleSource.ofSourcePathWithFlags(
            new Pair<SourcePath, String>(new FileSourcePath("foo.m"), "-foo")),
        AppleSource.ofSourcePath(new FileSourcePath("bar.m")));
    arg.frameworks = ImmutableSortedSet.of();
    BuildRule rule = new DescribedRule(
        IosLibraryDescription.TYPE,
        iosLibraryDescription.createBuildable(params, arg), params);

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
  }

  @Test
  public void testIosTestRule() throws IOException {
    BuildRuleParams params = new FakeBuildRuleParams(
        new BuildTarget("//foo", "test"), ImmutableSortedSet.<BuildRule>of());

    IosTestDescription.Arg arg = iosTestDescription.createUnpopulatedConstructorArg();
    arg.infoPlist = Paths.get("Info.plist");
    arg.configs = ImmutableMap.of(
        "Debug", ImmutableList.<Either<Path, ImmutableMap<String, String>>>of());
    arg.headers = ImmutableList.of(AppleSource.ofSourcePath(new FileSourcePath("foo.h")));
    arg.srcs = ImmutableList.of(AppleSource.ofSourcePathWithFlags(
        new Pair<SourcePath, String>(new FileSourcePath("foo.m"), "-foo")));
    arg.frameworks = ImmutableSortedSet.of("$SDKROOT/Foo.framework");
    arg.sourceUnderTest = ImmutableSortedSet.of();

    BuildRule rule = new DescribedRule(
        IosTestDescription.TYPE,
        iosTestDescription.createBuildable(params, arg), params);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule),
        ImmutableSet.of(rule.getBuildTarget()));

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:test");
    assertEquals("PBXNativeTarget", target.isa());
    assertEquals(PBXTarget.ProductType.IOS_TEST, target.getProductType());

    assertHasConfigurations(target, "Debug");
    assertEquals("Should have exact number of build phases", 3, target.getBuildPhases().size());
    assertHasSingletonSourcesPhaseWithSourcesAndFlags(
        target, ImmutableMap.of(
        "foo.m", Optional.of("-foo")));

    ProjectGeneratorTestUtils.assertHasSingletonFrameworksPhaseWithFrameworkEntries(
        target, ImmutableList.of(
        "$SDKROOT/Foo.framework"));
  }

  @Test
  public void testIosBinaryRule() throws IOException {
    BuildRule depRule = createBuildRuleWithDefaults(
        new BuildTarget("//dep", "dep"),
        ImmutableSortedSet.<BuildRule>of(),
        iosLibraryDescription);
    BuildRuleParams params = new FakeBuildRuleParams(
        new BuildTarget("//foo", "binary"), ImmutableSortedSet.of(depRule));

    IosBinaryDescription.Arg arg = iosBinaryDescription.createUnpopulatedConstructorArg();
    arg.infoPlist = Paths.get("Info.plist");
    arg.configs = ImmutableMap.of(
        "Debug", ImmutableList.<Either<Path, ImmutableMap<String, String>>>of());
    arg.headers = ImmutableList.of(AppleSource.ofSourcePath(new FileSourcePath("foo.h")));
    arg.srcs = ImmutableList.of(AppleSource.ofSourcePathWithFlags(
        new Pair<SourcePath, String>(new FileSourcePath("foo.m"), "-foo")));
    arg.frameworks = ImmutableSortedSet.of("$SDKROOT/Foo.framework");

    BuildRule rule = new DescribedRule(
        IosBinaryDescription.TYPE,
        iosBinaryDescription.createBuildable(params, arg), params);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule),
        ImmutableSet.of(rule.getBuildTarget()));
    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:binary");
    assertHasConfigurations(target, "Debug");
    assertEquals("Should have exact number of build phases", 3, target.getBuildPhases().size());
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
  }

  @Test
  public void testIosLibraryRuleWithGenruleDependency() throws IOException {

    BuildRuleResolver buildRuleResolver = new BuildRuleResolver();
    AbstractBuildRuleBuilderParams genruleParams = new FakeAbstractBuildRuleBuilderParams();
    Genrule.Builder builder = Genrule.newGenruleBuilder(genruleParams);
    builder.setBuildTarget(new BuildTarget("//foo", "script"));
    builder.setBash(Optional.of("echo \"hello world!\""));
    builder.setOut("helloworld.txt");

    Genrule genrule = buildRuleResolver.buildAndAddToIndex(builder);

    BuildTarget libTarget = new BuildTarget("//foo", "lib");
    BuildRuleParams libParams = new FakeBuildRuleParams(libTarget,
                                                        ImmutableSortedSet.<BuildRule>of(genrule));
    IosLibraryDescription.Arg arg = iosLibraryDescription.createUnpopulatedConstructorArg();
    arg.configs = ImmutableMap.of(
        "Debug", ImmutableList.<Either<Path, ImmutableMap<String, String>>>of());
    arg.headers = ImmutableList.of(AppleSource.ofSourcePath(new FileSourcePath("foo.h")));
    arg.srcs = ImmutableList.of(AppleSource.ofSourcePathWithFlags(
        new Pair<SourcePath, String>(new FileSourcePath("foo.m"), "-foo")));
    arg.frameworks = ImmutableSortedSet.of();
    BuildRule rule = new DescribedRule(
        IosLibraryDescription.TYPE,
        iosLibraryDescription.createBuildable(libParams, arg), libParams);

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
        shellScriptBuildPhase.getShellScript(),
        equalTo("/bin/bash -e -c 'echo \"hello world!\"'"));
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
        new BuildTarget("//bar", "lib"),
        ImmutableSortedSet.<BuildRule>of(),
        iosLibraryDescription);
    BuildRule fooLib = createBuildRuleWithDefaults(
        new BuildTarget("//foo", "lib"),
        ImmutableSortedSet.of(barLib),
        iosLibraryDescription);
    BuildRule fooBin = createBuildRuleWithDefaults(
        new BuildTarget("//foo", "bin"),
        ImmutableSortedSet.of(fooLib),
        iosBinaryDescription);
    BuildRule bazLib = createBuildRuleWithDefaults(
        new BuildTarget("//baz", "lib"),
        ImmutableSortedSet.of(fooLib),
        iosLibraryDescription);

    BuildRule bazLibTest = createIosTestRule(
        new BuildTarget("//baz", "test"),
        ImmutableSortedSet.of(bazLib),
        ImmutableSortedSet.of(bazLib));
    BuildRule fooLibTest = createIosTestRule(
        new BuildTarget("//foo", "lib-test"),
        ImmutableSortedSet.of(fooLib),
        ImmutableSortedSet.of(fooLib, bazLib));
    BuildRule fooBinTest = createIosTestRule(
        new BuildTarget("//foo", "bin-test"),
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
        new BuildTarget("//foo", "foo"),
        ImmutableSortedSet.<BuildRule>of(),
        xcodeNativeDescription);

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
        "93C1B2AA2245423200000000", expectedGID);
    assertEquals("generated GID is same as expected", expectedGID, target.getGlobalID());
  }

  @Test
  public void resourcesInDependenciesPropagatesToBinariesAndTests() throws IOException {
    BuildRule resourceRule = createBuildRuleWithDefaults(
        new BuildTarget("//foo", "res"),
        ImmutableSortedSet.<BuildRule>of(),
        iosResourceDescription,
        new Function<AppleResourceDescriptionArg, AppleResourceDescriptionArg>() {
          @Override
          public AppleResourceDescriptionArg apply(AppleResourceDescriptionArg input) {
            input.files = ImmutableSet.<SourcePath>of(new FileSourcePath("foo.png"));
            input.dirs = ImmutableSet.of(Paths.get("foodir"));
            return input;
          }
        });

    BuildRule libraryRule = createBuildRuleWithDefaults(
        new BuildTarget("//foo", "lib"),
        ImmutableSortedSet.of(resourceRule),
        iosLibraryDescription);

    BuildRule testRule = createBuildRuleWithDefaults(
        new BuildTarget("//foo", "test"),
        ImmutableSortedSet.of(libraryRule),
        iosTestDescription);

    BuildRule binaryRule = createBuildRuleWithDefaults(
        new BuildTarget("//foo", "bin"),
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

  /**
   * The project configurations should have named entries corresponding to every existing target
   * configuration for targets in the project.
   */
  @Test
  public void generatedProjectConfigurationListIsUnionOfAllTargetConfigurations()
      throws IOException {
    BuildRule rule1 = createBuildRuleWithDefaults(
        new BuildTarget("//foo", "rule1"),
        ImmutableSortedSet.<BuildRule>of(),
        iosLibraryDescription,
        new Function<IosLibraryDescription.Arg, IosLibraryDescription.Arg>() {
          @Override
          public IosLibraryDescription.Arg apply(IosLibraryDescription.Arg input) {
            input.configs = ImmutableMap.of(
                "Conf1", ImmutableList.<Either<Path, ImmutableMap<String, String>>>of(),
                "Conf2", ImmutableList.<Either<Path, ImmutableMap<String, String>>>of());
            return input;
          }
        });

    BuildRule rule2 = createBuildRuleWithDefaults(
        new BuildTarget("//foo", "rule2"),
        ImmutableSortedSet.<BuildRule>of(),
        iosLibraryDescription,
        new Function<IosLibraryDescription.Arg, IosLibraryDescription.Arg>() {
          @Override
          public IosLibraryDescription.Arg apply(IosLibraryDescription.Arg input) {
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
    return new ProjectGenerator(
        partialGraph,
        initialBuildTargets,
        projectFilesystem,
        executionContext,
        OUTPUT_DIRECTORY,
        PROJECT_NAME,
        ProjectGenerator.COMBINED_PROJECT_OPTIONS);
  }

  private String assertFileRefIsRelativeAndResolvePath(PBXReference fileRef) {
    assert(!fileRef.getPath().startsWith("/"));
    assertEquals(
        "file path should be relative to project directory",
        PBXFileReference.SourceTree.SOURCE_ROOT,
        fileRef.getSourceTree()
    );
    return projectFilesystem.resolve(OUTPUT_DIRECTORY).resolve(fileRef.getPath())
        .normalize().toString();
  }

  private BuildRule createIosTestRule(
      BuildTarget target,
      ImmutableSortedSet<BuildRule> sourceUnderTest,
      ImmutableSortedSet<BuildRule> deps) {
    BuildRuleParams buildRuleParams = new FakeBuildRuleParams(target, deps);
    IosTestDescription.Arg arg = iosTestDescription.createUnpopulatedConstructorArg();
    arg.configs = ImmutableMap.of();
    arg.infoPlist = Paths.get("Info.plist");
    arg.frameworks = ImmutableSortedSet.of();
    arg.headers = ImmutableList.of();
    arg.srcs = ImmutableList.of();
    arg.sourceUnderTest = sourceUnderTest;
    return new DescribedRule(
        iosTestDescription.getBuildRuleType(),
        iosTestDescription.createBuildable(buildRuleParams, arg),
        buildRuleParams);
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

  private void verifyGeneratedSignedSourceTarget(PBXTarget target) {
    Iterable<PBXShellScriptBuildPhase> shellSteps = Iterables.filter(
        target.getBuildPhases(), PBXShellScriptBuildPhase.class);
    assertEquals(1, Iterables.size(shellSteps));
    PBXShellScriptBuildPhase generatedScriptPhase = Iterables.get(shellSteps, 0);
    assertThat(
        generatedScriptPhase.getShellScript(),
        containsString(SourceSigner.SIGNED_SOURCE_PLACEHOLDER));
  }
}
