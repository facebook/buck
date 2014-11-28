/*
 * Copyright 2012-present Facebook, Inc.
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

import static com.facebook.buck.apple.xcode.Matchers.isTargetWithName;
import static com.facebook.buck.apple.xcode.ProjectGeneratorTestUtils.assertHasSingletonFrameworksPhaseWithFrameworkEntries;
import static com.facebook.buck.apple.xcode.ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.apple.AppleBinaryBuilder;
import com.facebook.buck.apple.AppleBundleBuilder;
import com.facebook.buck.apple.AppleBundleExtension;
import com.facebook.buck.apple.AppleLibraryBuilder;
import com.facebook.buck.apple.AppleLibraryDescription;
import com.facebook.buck.apple.XcodeProjectConfigBuilder;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class SeparatedProjectsGeneratorTest {
  private final ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
  private final ExecutionContext executionContext = TestExecutionContext.newInstance();
  private final BuildRuleResolver buildRuleResolver = new BuildRuleResolver();
  private final SourcePathResolver sourcePathResolver = new SourcePathResolver(buildRuleResolver);

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Test(expected = HumanReadableException.class)
  public void errorsIfNotPassingInXcodeConfigRules() throws IOException {
    BuildTarget target = BuildTarget.builder("//foo", "thing").build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(target)
        .build();

    SeparatedProjectsGenerator generator = new SeparatedProjectsGenerator(
        projectFilesystem,
        TargetGraphFactory.newInstance(ImmutableSet.<TargetNode<?>>of(node)),
        executionContext,
        buildRuleResolver,
        sourcePathResolver,
        ImmutableSet.of(target),
        ImmutableSet.<ProjectGenerator.Option>of());
    generator.generateProjects();
  }

  @Test
  public void errorsIfPassingInNonexistentRule() throws IOException {
    BuildTarget target = BuildTarget.builder("//foo", "thing").build();
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage("target not found");

    SeparatedProjectsGenerator generator = new SeparatedProjectsGenerator(
        projectFilesystem,
        TargetGraphFactory.newInstance(ImmutableSet.<TargetNode<?>>of()),
        executionContext,
        buildRuleResolver,
        sourcePathResolver,
        ImmutableSet.of(target),
        ImmutableSet.<ProjectGenerator.Option>of());
    generator.generateProjects();
  }

  @Test
  public void generatesProjectFilesInCorrectLocations() throws IOException {
    BuildTarget libraryTarget = BuildTarget.builder("//foo/bar", "somelib").build();
    TargetNode<?> libraryNode = AppleLibraryBuilder
        .createBuilder(libraryTarget)
        .build();

    BuildTarget configTarget = BuildTarget.builder("//foo/bar", "project").build();
    TargetNode<?> configNode = XcodeProjectConfigBuilder
        .createBuilder(configTarget)
        .setProjectName("fooproject")
        .setRules(ImmutableSortedSet.of(libraryTarget))
        .build();

    SeparatedProjectsGenerator generator = new SeparatedProjectsGenerator(
        projectFilesystem,
        TargetGraphFactory.newInstance(ImmutableSet.of(libraryNode, configNode)),
        executionContext,
        buildRuleResolver,
        sourcePathResolver,
        ImmutableSet.of(configTarget),
        ImmutableSet.<ProjectGenerator.Option>of());
    generator.generateProjects();

    assertTrue(
        "Project was generated alongside Buck file",
        projectFilesystem.exists(Paths.get("foo/bar/fooproject.xcodeproj/project.pbxproj")));

    assertFalse(
        "No workspace is generated",
        projectFilesystem.exists(
            Paths.get("foo/bar/fooproject.xcworkspace/contents.xcworkspacedata")));

    assertFalse(
        "No scheme is generated",
        projectFilesystem.exists(
            Paths.get("foo/bar/fooproject.xcodeproj/xcshareddata/xcschemes/Scheme.xcscheme")));
  }

  @Test
  public void generatesOnlyReferencedTargets() throws IOException {
    BuildTarget depTarget = BuildTarget.builder("//elsewhere", "somedep").build();
    TargetNode<?> depNode = AppleLibraryBuilder
        .createBuilder(depTarget)
        .build();

    BuildTarget target = BuildTarget.builder("//foo/bar", "somelib").build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(target)
        .setDeps(Optional.of(ImmutableSortedSet.of(depTarget)))
        .build();

    BuildTarget configTarget = BuildTarget.builder("//foo/bar", "project").build();
    TargetNode<?> configNode = XcodeProjectConfigBuilder
        .createBuilder(configTarget)
        .setProjectName("fooproject")
        .setRules(ImmutableSortedSet.of(target))
        .build();

    SeparatedProjectsGenerator generator = new SeparatedProjectsGenerator(
        projectFilesystem,
        TargetGraphFactory.newInstance(ImmutableSet.of(depNode, node, configNode)),
        executionContext,
        buildRuleResolver,
        sourcePathResolver,
        ImmutableSet.of(configTarget),
        ImmutableSet.<ProjectGenerator.Option>of());
    generator.generateProjects();

    PBXProject project = getGeneratedProjectOfConfigRule(generator, configTarget);

    assertThat("Has only one targets", project.getTargets(), hasSize(1));
    assertThat(
        "The target is the named target",
        project.getTargets(),
        hasItem(isTargetWithName("somelib")));
  }

  @Test
  public void generatedBinariesLinksLibraryDependencies() throws IOException {
    BuildTarget depTarget = BuildTarget.builder("//elsewhere", "somedep").build();
    TargetNode<?> depNode = AppleLibraryBuilder
        .createBuilder(depTarget)
        .build();

    BuildTarget dynamicLibraryTarget = BuildTarget.builder("//dep", "dynamic").setFlavor(
        AppleLibraryDescription.DYNAMIC_LIBRARY).build();
    TargetNode<?> dynamicLibraryNode = AppleLibraryBuilder
        .createBuilder(dynamicLibraryTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(depTarget)))
        .build();

    BuildTarget target = BuildTarget.builder("//foo", "bin").build();
    TargetNode<?> node = AppleBundleBuilder
        .createBuilder(target)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.FRAMEWORK))
        .setBinary(dynamicLibraryTarget)
        .build();

    BuildTarget configTarget = BuildTarget.builder("//foo/bar", "project").build();
    TargetNode<?> configNode = XcodeProjectConfigBuilder
        .createBuilder(configTarget)
        .setProjectName("fooproject")
        .setRules(ImmutableSortedSet.of(target))
        .build();

    SeparatedProjectsGenerator generator = new SeparatedProjectsGenerator(
        projectFilesystem,
        TargetGraphFactory.newInstance(
            ImmutableSet.of(depNode, dynamicLibraryNode, node, configNode)),
        executionContext,
        buildRuleResolver,
        sourcePathResolver,
        ImmutableSet.of(configTarget),
        ImmutableSet.<ProjectGenerator.Option>of());
    generator.generateProjects();

    PBXProject project = getGeneratedProjectOfConfigRule(generator, configTarget);
    PBXTarget pbxTarget = assertTargetExistsAndReturnTarget(project, "bin");
    assertHasSingletonFrameworksPhaseWithFrameworkEntries(
        pbxTarget,
        ImmutableList.of(
            "$BUILT_PRODUCTS_DIR/F4XWK3DTMV3WQZLSMU5HG33NMVSGK4A/libsomedep.a"));
  }

  /** Tests that project and target level configs are set in the generated project correctly */
  @Test
  public void generatedProjectsSetsInlineConfigsCorrectly() throws IOException {
    BuildTarget target1 = BuildTarget.builder("//foo", "rule1").build();
    TargetNode<?> node1 = AppleLibraryBuilder
        .createBuilder(target1)
        .setConfigs(Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    ImmutableMap.of(
                        "TARGET_FLAG1", "t1",
                        "TARGET_FLAG2", "t2"))))
        .build();

    BuildTarget target2 = BuildTarget.builder("//foo", "rule2").build();
    TargetNode<?> node2 = AppleLibraryBuilder
        .createBuilder(target2)
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    ImmutableMap.of(
                        "TARGET_FLAG3", "t3",
                        "TARGET_FLAG4", "t4"))))
        .build();

    BuildTarget configTarget = BuildTarget.builder("//foo", "project").build();
    TargetNode<?> configNode = XcodeProjectConfigBuilder
        .createBuilder(configTarget)
        .setProjectName("fooproject")
        .setRules(ImmutableSortedSet.of(target1, target2))
        .build();

    SeparatedProjectsGenerator generator = new SeparatedProjectsGenerator(
        projectFilesystem,
        TargetGraphFactory.newInstance(ImmutableSet.of(node1, node2, configNode)),
        executionContext,
        buildRuleResolver,
        sourcePathResolver,
        ImmutableSet.of(configTarget),
        ImmutableSet.<ProjectGenerator.Option>of());
    generator.generateProjects();

    PBXProject project = getGeneratedProjectOfConfigRule(generator, configTarget);

    // not looking that the config files are set correctly, since they are covered by
    // the other test: generatedProjectsReferencesXcconfigFilesDirectly

    {
      PBXTarget target =
          ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(project, "rule1");
      ImmutableMap<String, String> settings = ProjectGeneratorTestUtils.getBuildSettings(
          projectFilesystem, target1, target, "Debug");
      assertEquals("t1", settings.get("TARGET_FLAG1"));
      assertEquals("t2", settings.get("TARGET_FLAG2"));
      assertFalse(settings.containsKey("TARGET_FLAG3"));
      assertFalse(settings.containsKey("TARGET_FLAG4"));
    }

    {
      PBXTarget target =
          ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(project, "rule2");
      ImmutableMap<String, String> settings = ProjectGeneratorTestUtils.getBuildSettings(
          projectFilesystem, target1, target, "Debug");
      assertEquals("t3", settings.get("TARGET_FLAG3"));
      assertEquals("t4", settings.get("TARGET_FLAG4"));
      assertFalse(settings.containsKey("TARGET_FLAG1"));
      assertFalse(settings.containsKey("TARGET_FLAG2"));
    }

  }

  @Test
  public void generatedTargetsShouldUseShortNames() throws IOException {
    BuildTarget libraryTarget = BuildTarget.builder("//foo", "library").build();
    TargetNode<?> libraryNode = AppleLibraryBuilder
        .createBuilder(libraryTarget)
        .build();

    BuildTarget binaryDepTarget = BuildTarget.builder("//foo", "binarybin").setFlavor(
        AppleLibraryDescription.DYNAMIC_LIBRARY).build();
    TargetNode<?> binaryDepNode = AppleBinaryBuilder
        .createBuilder(binaryDepTarget)
        .build();

    BuildTarget binaryTarget = BuildTarget.builder("//foo", "binary").build();
    TargetNode<?> binaryNode = AppleBundleBuilder
        .createBuilder(binaryTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.APP))
        .setBinary(binaryDepTarget)
        .build();

    BuildTarget nativeTarget = BuildTarget.builder("//foo", "native").build();
    TargetNode<?> nativeNode = AppleLibraryBuilder
        .createBuilder(nativeTarget)
        .build();

    BuildTarget configTarget = BuildTarget.builder("//foo", "project").build();
    TargetNode<?> configNode = XcodeProjectConfigBuilder
        .createBuilder(configTarget)
        .setProjectName("fooproject")
        .setRules(ImmutableSortedSet.of(libraryTarget, binaryTarget, nativeTarget))
        .build();

    SeparatedProjectsGenerator generator = new SeparatedProjectsGenerator(
        projectFilesystem,
        TargetGraphFactory.newInstance(
            ImmutableSet.of(libraryNode, binaryDepNode, binaryNode, nativeNode, configNode)),
        executionContext,
        buildRuleResolver,
        sourcePathResolver,
        ImmutableSet.of(configTarget),
        ImmutableSet.<ProjectGenerator.Option>of());
    generator.generateProjects();

    PBXProject project = getGeneratedProjectOfConfigRule(generator, configTarget);
    assertTargetExistsAndReturnTarget(project, "library");
    assertTargetExistsAndReturnTarget(project, "binary");
    assertTargetExistsAndReturnTarget(project, "native");
  }

  @Test
  public void shouldReturnListOfGeneratedProjects() throws IOException {
    BuildTarget fooTarget1 = BuildTarget.builder("//foo", "rule1").build();
    TargetNode<?> fooNode1 = AppleLibraryBuilder
        .createBuilder(fooTarget1)
        .build();

    BuildTarget fooConfigTarget = BuildTarget.builder("//foo", "project").build();
    TargetNode<?> fooConfigNode = XcodeProjectConfigBuilder
        .createBuilder(fooConfigTarget)
        .setProjectName("fooproject")
        .setRules(ImmutableSortedSet.of(fooTarget1))
        .build();

    BuildTarget barTarget2 = BuildTarget.builder("//bar", "rule2").build();
    TargetNode<?> barNode2 = AppleLibraryBuilder
        .createBuilder(barTarget2)
        .build();

    BuildTarget barConfigTarget = BuildTarget.builder("//bar", "project").build();
    TargetNode<?> barConfigNode = XcodeProjectConfigBuilder
        .createBuilder(barConfigTarget)
        .setProjectName("barproject")
        .setRules(ImmutableSortedSet.of(barTarget2))
        .build();

    SeparatedProjectsGenerator generator = new SeparatedProjectsGenerator(
        projectFilesystem,
        TargetGraphFactory.newInstance(
            ImmutableSet.of(fooNode1, fooConfigNode, barNode2, barConfigNode)),
        executionContext,
        buildRuleResolver,
        sourcePathResolver,
        ImmutableSet.of(fooConfigTarget, barConfigTarget),
        ImmutableSet.<ProjectGenerator.Option>of());

    ImmutableSet<Path> paths = generator.generateProjects();

    assertEquals(
        ImmutableSet.of(
            Paths.get("foo/fooproject.xcodeproj"),
            Paths.get("bar/barproject.xcodeproj")),
        paths);
  }

  private static PBXProject getGeneratedProjectOfConfigRule(
      SeparatedProjectsGenerator generator,
      BuildTarget target) {

    ImmutableMap<BuildTarget, ProjectGenerator> projectGeneratorMap =
        generator.getProjectGenerators();
    assertNotNull(
        "should have called SeparatedProjectsGenerator.generateProjects()",
        projectGeneratorMap);
    ProjectGenerator innerGenerator = projectGeneratorMap.get(target);
    assertNotNull("should have generated project from config rule: " + target, innerGenerator);
    return innerGenerator.getGeneratedProject();
  }
}
