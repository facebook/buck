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

import static org.hamcrest.CoreMatchers.either;
import static org.hamcrest.CoreMatchers.endsWith;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.hamcrest.collection.IsMapContaining.hasKey;
import static org.hamcrest.xml.HasXPath.hasXPath;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.dd.plist.NSDictionary;
import com.dd.plist.NSString;
import com.facebook.buck.apple.IosLibraryDescription;
import com.facebook.buck.apple.XcodeNativeDescription;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildFile;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXContainerItemProxy;
import com.facebook.buck.apple.xcode.xcodeproj.PBXFileReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXGroup;
import com.facebook.buck.apple.xcode.xcodeproj.PBXHeadersBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.apple.xcode.xcodeproj.PBXSourcesBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTargetDependency;
import com.facebook.buck.apple.xcode.xcodeproj.XCBuildConfiguration;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.parser.PartialGraphFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DescribedRule;
import com.facebook.buck.rules.FakeBuildRuleParams;
import com.facebook.buck.rules.FileSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.rules.coercer.Pair;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.RuleMap;
import com.facebook.buck.util.ProjectFilesystem;
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
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class ProjectGeneratorTest {

  private static final Path OUTPUT_DIRECTORY = Paths.get("_gen");
  private static final String PROJECT_NAME = "GeneratedProject";
  private static final String PROJECT_CONTAINER = PROJECT_NAME + ".xcodeproj";

  private ProjectFilesystem projectFilesystem;
  private XcodeNativeDescription xcodeNativeDescription;
  private IosLibraryDescription iosLibraryDescription;

  @Before
  public void setUp() {
    projectFilesystem = new FakeProjectFilesystem();
    xcodeNativeDescription = new XcodeNativeDescription();
    iosLibraryDescription = new IosLibraryDescription();
  }

  @Test
  public void testProjectStructureForEmptyProject() throws IOException {
    BuildRuleResolver buildRuleResolver = new BuildRuleResolver();
    ProjectGenerator projectGenerator = createProjectGenerator(
        buildRuleResolver, ImmutableList.<BuildTarget>of());

    Path outputProjectBundlePath = OUTPUT_DIRECTORY.resolve(PROJECT_CONTAINER);
    Path outputProjectFilePath = outputProjectBundlePath.resolve("project.pbxproj");

    Path outputWorkspaceBundlePath = OUTPUT_DIRECTORY.resolve(PROJECT_NAME + ".xcworkspace");
    Path outputWorkspaceFilePath = outputWorkspaceBundlePath.resolve("contents.xcworkspacedata");

    Path outputSchemeFolderPath = outputProjectBundlePath.resolve(
        Paths.get("xcshareddata", "xcschemes"));
    Path outputSchemePath = outputSchemeFolderPath.resolve("Scheme.xcscheme");

    projectGenerator.createXcodeProjects();

    Optional<String> pbxproj = projectFilesystem.readFileIfItExists(outputProjectFilePath);
    assertTrue(pbxproj.isPresent());

    Optional<String> xcworkspacedata = projectFilesystem.readFileIfItExists(outputWorkspaceFilePath);
    assertTrue(xcworkspacedata.isPresent());

    Optional<String> xcscheme = projectFilesystem.readFileIfItExists(outputSchemePath);
    assertTrue(xcscheme.isPresent());
  }

  @Test
  public void testSchemeGeneration() throws IOException {
    BuildRule rootRule = createXcodeNativeRule(
        new BuildTarget("//foo", "root"),
        ImmutableSortedSet.<BuildRule>of());
    BuildRule leftRule = createXcodeNativeRule(
        new BuildTarget("//foo", "left"),
        ImmutableSortedSet.of(rootRule));
    BuildRule rightRule = createXcodeNativeRule(
        new BuildTarget("//foo", "right"),
        ImmutableSortedSet.of(rootRule));
    BuildRule childRule = createXcodeNativeRule(
        new BuildTarget("//foo", "child"),
        ImmutableSortedSet.of(leftRule, rightRule));

    BuildRuleResolver buildRuleResolver = new BuildRuleResolver(ImmutableSet.of(
        rootRule, leftRule, rightRule, childRule));

    ProjectGenerator projectGenerator = createProjectGenerator(
        buildRuleResolver, ImmutableList.of(childRule.getBuildTarget()));

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
    BuildRuleResolver buildRuleResolver = new BuildRuleResolver();
    ProjectGenerator projectGenerator = createProjectGenerator(
        buildRuleResolver, ImmutableList.<BuildTarget>of());
    projectGenerator.createXcodeProjects();

    Document workspace = projectGenerator.getGeneratedWorkspace();
    assertThat(workspace, hasXPath("/Workspace[@version = \"1.0\"]"));
    assertThat(workspace,
        hasXPath("/Workspace/FileRef/@location", equalTo("container:" + PROJECT_CONTAINER)));
  }

  @Test
  public void testXcodeNativeRule() throws IOException {
    BuildRule rule = createXcodeNativeRule(
        new BuildTarget("//foo", "rule"),
        ImmutableSortedSet.<BuildRule>of());
    BuildRuleResolver buildRuleResolver = new BuildRuleResolver(ImmutableSet.of(rule));
    ProjectGenerator projectGenerator = createProjectGenerator(
        buildRuleResolver, ImmutableList.of(rule.getBuildTarget()));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    assertThat(project.getTargets(), hasSize(1));
    PBXTarget target = project.getTargets().get(0);
    assertThat(target.getName(), equalTo("//foo:rule"));
    assertThat(target.isa(), equalTo("PBXAggregateTarget"));
    assertThat(target.getDependencies(), hasSize(1));
    PBXTargetDependency dependency = target.getDependencies().get(0);
    PBXContainerItemProxy proxy = dependency.getTargetProxy();
    assertThat(
        proxy.getContainerPortal().getSourceTree(),
        equalTo(PBXFileReference.SourceTree.ABSOLUTE));
    assertThat(proxy.getContainerPortal().getPath(), endsWith("foo.xcodeproj"));
    assertThat(proxy.getRemoteGlobalIDString(), equalTo("00DEADBEEF"));

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
    arg.headers = ImmutableSortedSet.of((SourcePath) new FileSourcePath("foo.h"));
    arg.srcs = ImmutableList.of(Either.<SourcePath, Pair<SourcePath, String>>ofRight(
        new Pair<SourcePath, String>(new FileSourcePath("foo.m"), "-foo")));
    arg.frameworks = ImmutableSortedSet.of();
    BuildRule rule = new DescribedRule(
        IosLibraryDescription.TYPE,
        iosLibraryDescription.createBuildable(params, arg), params);
    BuildRuleResolver buildRuleResolver = new BuildRuleResolver(ImmutableSet.of(rule));

    ProjectGenerator projectGenerator = createProjectGenerator(
        buildRuleResolver, ImmutableList.of(rule.getBuildTarget()));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    assertThat(project.getTargets(), hasSize(1));
    PBXTarget target = project.getTargets().get(0);
    assertThat(target.isa(), equalTo("PBXNativeTarget"));
    assertThat(target.getProductType(), equalTo(PBXTarget.ProductType.IOS_LIBRARY));

    // check configuration
    {
      Map<String, XCBuildConfiguration> buildConfigurationMap =
          target.getBuildConfigurationList().getBuildConfigurationsByName().asMap();
      assertThat(buildConfigurationMap, hasKey("Debug"));

      XCBuildConfiguration configuration = buildConfigurationMap.get("Debug");
      assertEquals("Debug", configuration.getName());
      assertThat(configuration.getBaseConfigurationReference().getPath(), endsWith(".xcconfig"));
    }

    Collection<PBXBuildPhase> buildPhases = target.getBuildPhases();
    assertThat(buildPhases, hasSize(2));

    // check sources
    {
      PBXBuildPhase sourcesBuildPhase =
          Iterables.find(buildPhases, new Predicate<PBXBuildPhase>() {
            @Override
            public boolean apply(PBXBuildPhase input) {
              return input instanceof PBXSourcesBuildPhase;
            }
          });
      PBXBuildFile sourceBuildFile = Iterables.getOnlyElement(sourcesBuildPhase.getFiles());
      NSDictionary flags = sourceBuildFile.getSettings().get();
      assertEquals(flags.count(), 1);
      assertTrue(flags.containsKey("COMPILER_FLAGS"));
      assertEquals("-foo", ((NSString) flags.get("COMPILER_FLAGS")).getContent());

      assertEquals(
          PBXFileReference.SourceTree.ABSOLUTE,
          sourceBuildFile.getFileRef().getSourceTree());
      assertEquals(
          projectFilesystem.getRootPath().resolve("foo.m").toAbsolutePath().toString(),
          sourceBuildFile.getFileRef().getPath());
    }

    // check headers
    {
      PBXBuildPhase headersBuildPhase =
          Iterables.find(buildPhases, new Predicate<PBXBuildPhase>() {
            @Override
            public boolean apply(PBXBuildPhase input) {
              return input instanceof PBXHeadersBuildPhase;
            }
          });
      PBXBuildFile headerBuildFile = Iterables.getOnlyElement(headersBuildPhase.getFiles());

      assertEquals(
          PBXFileReference.SourceTree.ABSOLUTE,
          headerBuildFile.getFileRef().getSourceTree());
      assertEquals(
          projectFilesystem.getRootPath().resolve("foo.h").toAbsolutePath().toString(),
          headerBuildFile.getFileRef().getPath());
    }
  }

  private ProjectGenerator createProjectGenerator(
      BuildRuleResolver buildRuleResolver, ImmutableList<BuildTarget> initialBuildTargets) {
    PartialGraph partialGraph = PartialGraphFactory.newInstance(
        RuleMap.createGraphFromBuildRules(buildRuleResolver), ImmutableList.<BuildTarget>of());
    return new ProjectGenerator(
        partialGraph,
        initialBuildTargets,
        projectFilesystem,
        OUTPUT_DIRECTORY,
        PROJECT_NAME);
  }

  /**
   * Create a xcode_native rule. Useful for testing the rule-type agnostic parts of the project
   * generator, as this is the simplest rule that the project generator handles.
   */
  private BuildRule createXcodeNativeRule(
      BuildTarget target, ImmutableSortedSet<BuildRule> deps) {
    BuildRuleParams buildRuleParams = new FakeBuildRuleParams(target, deps);
    XcodeNativeDescription.Arg arg = xcodeNativeDescription.createUnpopulatedConstructorArg();
    arg.projectContainerPath = new FileSourcePath("foo.xcodeproj");
    arg.targetGid = "00DEADBEEF";
    arg.product = "libfoo.a";
    return new DescribedRule(
        XcodeNativeDescription.TYPE,
        xcodeNativeDescription.createBuildable(buildRuleParams, arg),
        buildRuleParams);
  }
}
