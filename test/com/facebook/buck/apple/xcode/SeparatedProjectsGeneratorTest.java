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
import static com.facebook.buck.apple.xcode.ProjectGeneratorTestUtils.createBuildRuleWithDefaults;
import static com.facebook.buck.apple.xcode.ProjectGeneratorTestUtils.createPartialGraphFromBuildRules;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.isA;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.dd.plist.NSString;
import com.facebook.buck.apple.IosBinaryDescription;
import com.facebook.buck.apple.IosLibraryDescription;
import com.facebook.buck.apple.IosTestDescription;
import com.facebook.buck.apple.XcodeProjectConfigDescription;
import com.facebook.buck.apple.xcode.xcodeproj.PBXAggregateTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.apple.xcode.xcodeproj.XCBuildConfiguration;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class SeparatedProjectsGeneratorTest {
  private final ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
  private final ExecutionContext executionContext = TestExecutionContext.newInstance();
  private final IosLibraryDescription iosLibraryDescription = new IosLibraryDescription();
  private final IosBinaryDescription iosBinaryDescription = new IosBinaryDescription();
  private final IosTestDescription iosTestDescription = new IosTestDescription();
  private final XcodeProjectConfigDescription xcodeProjectConfigDescription =
      new XcodeProjectConfigDescription();

  @Test(expected = HumanReadableException.class)
  public void errorsIfNotPassingInXcodeConfigRules() throws IOException {
    BuildRule rule = createBuildRuleWithDefaults(
        new BuildTarget("//foo", "thing"),
        ImmutableSortedSet.<BuildRule>of(),
        iosLibraryDescription);
    PartialGraph partialGraph = createPartialGraphFromBuildRules(
        ImmutableSet.of(rule));

    SeparatedProjectsGenerator generator = new SeparatedProjectsGenerator(
        projectFilesystem,
        partialGraph,
        executionContext,
        ImmutableSet.of(rule.getBuildTarget()));
    generator.generateProjects();
  }

  @Test
  public void generatesProjectFilesInCorrectLocations() throws IOException {
    BuildRule rule = createBuildRuleWithDefaults(
        new BuildTarget("//foo/bar", "somelib"),
        ImmutableSortedSet.<BuildRule>of(),
        iosLibraryDescription);
    BuildRule configRule = createXcodeProjectConfigRule(
        "//foo/bar",
        "fooproject",
        ImmutableSet.of(rule));
    PartialGraph partialGraph = createPartialGraphFromBuildRules(
        ImmutableSet.of(rule, configRule));
    SeparatedProjectsGenerator generator = new SeparatedProjectsGenerator(
        projectFilesystem,
        partialGraph,
        executionContext,
        ImmutableSet.of(configRule.getBuildTarget()));
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
    BuildRule depRule = createBuildRuleWithDefaults(
        new BuildTarget("//elsewhere", "somedep"),
        ImmutableSortedSet.<BuildRule>of(),
        iosLibraryDescription);
    BuildRule rule = createBuildRuleWithDefaults(
        new BuildTarget("//foo/bar", "somelib"),
        ImmutableSortedSet.of(depRule),
        iosLibraryDescription);
    BuildRule configRule = createXcodeProjectConfigRule(
        "//foo/bar",
        "fooproject",
        ImmutableSet.of(rule));
    PartialGraph partialGraph = createPartialGraphFromBuildRules(
        ImmutableSet.of(rule, configRule));
    SeparatedProjectsGenerator generator = new SeparatedProjectsGenerator(
        projectFilesystem,
        partialGraph,
        executionContext,
        ImmutableSet.of(configRule.getBuildTarget()));
    generator.generateProjects();

    PBXProject project = getGeneratedProjectOfConfigRule(generator, configRule);

    assertThat("Has only two targets", project.getTargets(), hasSize(2));
    assertThat(
        "One of the targets is the named target",
        project.getTargets(),
        hasItem(isTargetWithName("somelib")));
    assertThat(
        "Only other target is signed-source target",
        project.getTargets(),
        hasItem(isA(PBXAggregateTarget.class)));
  }

  @Test
  public void generatedBinariesLinksLibraryDependencies() throws IOException {
    BuildRule depRule = createBuildRuleWithDefaults(
        new BuildTarget("//elsewhere", "somedep"),
        ImmutableSortedSet.<BuildRule>of(),
        iosLibraryDescription);
    BuildRule rule = createBuildRuleWithDefaults(
        new BuildTarget("//foo", "bin"),
        ImmutableSortedSet.of(depRule),
        iosBinaryDescription);
    BuildRule configRule = createXcodeProjectConfigRule(
        "//foo/bar",
        "fooproject",
        ImmutableSet.of(rule));
    PartialGraph partialGraph = createPartialGraphFromBuildRules(
        ImmutableSet.of(rule, configRule));
    SeparatedProjectsGenerator generator = new SeparatedProjectsGenerator(
        projectFilesystem,
        partialGraph,
        executionContext,
        ImmutableSet.of(configRule.getBuildTarget()));
    generator.generateProjects();

    PBXProject project = getGeneratedProjectOfConfigRule(generator, configRule);
    PBXTarget target = assertTargetExistsAndReturnTarget(project, "bin");
    assertHasSingletonFrameworksPhaseWithFrameworkEntries(
        target,
        ImmutableList.of("$BUILT_PRODUCTS_DIR/libsomedep.a"));
  }

  @Test
  public void generatedProjectsReferencesXcconfigFilesDirectly() throws IOException {
    BuildRule rule = createBuildRuleWithDefaults(
        new BuildTarget("//foo", "rule"),
        ImmutableSortedSet.<BuildRule>of(),
        iosLibraryDescription,
        new Function<IosLibraryDescription.Arg, IosLibraryDescription.Arg>() {
          @Override
          public IosLibraryDescription.Arg apply(IosLibraryDescription.Arg input) {
            input.configs = ImmutableMap.of("Debug", ImmutableList.of(
                Either.<Path, ImmutableMap<String, String>>ofLeft(Paths.get("project.xcconfig")),
                Either.<Path, ImmutableMap<String, String>>ofLeft(Paths.get("target.xcconfig"))));
            return input;
          }
        });

    BuildRule configRule = createXcodeProjectConfigRule(
        "//foo",
        "fooproject",
        ImmutableSet.of(rule));

    SeparatedProjectsGenerator generator = new SeparatedProjectsGenerator(
        projectFilesystem,
        createPartialGraphFromBuildRules(ImmutableSet.of(configRule, rule)),
        executionContext,
        ImmutableSet.of(configRule.getBuildTarget()));
    generator.generateProjects();

    PBXProject project = getGeneratedProjectOfConfigRule(generator, configRule);

    XCBuildConfiguration projectLevelConfig =
        project.getBuildConfigurationList().getBuildConfigurationsByName().asMap().get("Debug");
    assertNotNull("should have project level Debug config", projectLevelConfig);
    assertNotNull(
        "project level Debug config should reference xcconfig file",
        projectLevelConfig.getBaseConfigurationReference());
    assertEquals(
        "Project level config file should be set correctly",
        "../project.xcconfig", // reference is relative from project directory
        projectLevelConfig.getBaseConfigurationReference().getPath());

    XCBuildConfiguration targetLevelConfig =
        ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(project, "rule")
            .getBuildConfigurationList().getBuildConfigurationsByName().asMap().get("Debug");
    assertNotNull("should have target level Debug config", targetLevelConfig);
    assertNotNull(
        "project level Debug config should reference xcconfig file",
        targetLevelConfig.getBaseConfigurationReference());
    assertEquals(
        "Target level config file should be set correctly",
        "../target.xcconfig",
        targetLevelConfig.getBaseConfigurationReference().getPath());
  }

  /** Tests that project and target level configs are set in the generated project correctly */
  @Test
  public void generatedProjectsSetsInlineConfigsCorrectly() throws IOException {
    BuildRule rule1 = createBuildRuleWithDefaults(
        new BuildTarget("//foo", "rule1"),
        ImmutableSortedSet.<BuildRule>of(),
        iosLibraryDescription,
        new Function<IosLibraryDescription.Arg, IosLibraryDescription.Arg>() {
          @Override
          public IosLibraryDescription.Arg apply(IosLibraryDescription.Arg input) {
            input.configs = ImmutableMap.of("Debug", ImmutableList.of(
                Either.<Path, ImmutableMap<String, String>>ofLeft(Paths.get("project.xcconfig")),
                Either.<Path, ImmutableMap<String, String>>ofRight(
                    ImmutableMap.of(
                        "PROJECT_FLAG1", "p1",
                        "PROJECT_FLAG2", "p2")),
                Either.<Path, ImmutableMap<String, String>>ofLeft(Paths.get("target.xcconfig")),
                Either.<Path, ImmutableMap<String, String>>ofRight(
                    ImmutableMap.of(
                        "TARGET_FLAG1", "t1",
                        "TARGET_FLAG2", "t2"))));
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
            input.configs = ImmutableMap.of("Debug", ImmutableList.of(
                Either.<Path, ImmutableMap<String, String>>ofLeft(Paths.get("project.xcconfig")),
                Either.<Path, ImmutableMap<String, String>>ofRight(
                    ImmutableMap.of(
                        "PROJECT_FLAG1", "p1",
                        "PROJECT_FLAG2", "p2")),
                Either.<Path, ImmutableMap<String, String>>ofLeft(Paths.get("target.xcconfig")),
                Either.<Path, ImmutableMap<String, String>>ofRight(
                    ImmutableMap.of(
                        "TARGET_FLAG3", "t3",
                        "TARGET_FLAG4", "t4"))));
            return input;
          }
        });

    BuildRule configRule = createXcodeProjectConfigRule(
        "//foo",
        "fooproject",
        ImmutableSet.of(rule1, rule2));

    SeparatedProjectsGenerator generator = new SeparatedProjectsGenerator(
        projectFilesystem,
        createPartialGraphFromBuildRules(ImmutableSet.of(configRule, rule1, rule2)),
        executionContext,
        ImmutableSet.of(configRule.getBuildTarget()));
    generator.generateProjects();

    PBXProject project = getGeneratedProjectOfConfigRule(generator, configRule);

    // not looking that the config files are set correctly, since they are covered by
    // the other test: generatedProjectsReferencesXcconfigFilesDirectly

    {
      XCBuildConfiguration projectLevelConfig =
          project.getBuildConfigurationList().getBuildConfigurationsByName().asMap().get("Debug");

      assertNotNull("should have project level Debug config", projectLevelConfig);
      assertEquals(2, projectLevelConfig.getBuildSettings().count());
      assertEquals(new NSString("p1"), projectLevelConfig.getBuildSettings().get("PROJECT_FLAG1"));
      assertEquals(new NSString("p2"), projectLevelConfig.getBuildSettings().get("PROJECT_FLAG2"));
    }

    {
      XCBuildConfiguration targetLevelConfig =
          ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(project, "rule1")
              .getBuildConfigurationList().getBuildConfigurationsByName().asMap().get("Debug");
      assertNotNull("should have target level Debug config", targetLevelConfig);
      assertEquals(new NSString("t1"), targetLevelConfig.getBuildSettings().get("TARGET_FLAG1"));
      assertEquals(new NSString("t2"), targetLevelConfig.getBuildSettings().get("TARGET_FLAG2"));
      assertFalse(targetLevelConfig.getBuildSettings().containsKey("TARGET_FLAG3"));
      assertFalse(targetLevelConfig.getBuildSettings().containsKey("TARGET_FLAG4"));
    }

    {
      XCBuildConfiguration targetLevelConfig =
          ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(project, "rule2")
              .getBuildConfigurationList().getBuildConfigurationsByName().asMap().get("Debug");
      assertNotNull("should have target level Debug config", targetLevelConfig);
      assertEquals(new NSString("t3"), targetLevelConfig.getBuildSettings().get("TARGET_FLAG3"));
      assertEquals(new NSString("t4"), targetLevelConfig.getBuildSettings().get("TARGET_FLAG4"));
      assertFalse(targetLevelConfig.getBuildSettings().containsKey("TARGET_FLAG1"));
      assertFalse(targetLevelConfig.getBuildSettings().containsKey("TARGET_FLAG2"));
    }

  }

  @Test(expected = HumanReadableException.class)
  public void errorIfXcconfigHasIncorrectPatternOfLayers() throws IOException {
    BuildRule rule = createBuildRuleWithDefaults(
        new BuildTarget("//foo", "rule"),
        ImmutableSortedSet.<BuildRule>of(),
        iosLibraryDescription,
        new Function<IosLibraryDescription.Arg, IosLibraryDescription.Arg>() {
          @Override
          public IosLibraryDescription.Arg apply(IosLibraryDescription.Arg input) {
            input.configs = ImmutableMap.of("Debug", ImmutableList.of(
                // this only has one layer, accepted layers is 2 or 4
                Either.<Path, ImmutableMap<String, String>>ofLeft(Paths.get("target.xcconfig"))));
            return input;
          }
        });

    BuildRule configRule = createXcodeProjectConfigRule(
        "//foo",
        "fooproject",
        ImmutableSet.of(rule));

    SeparatedProjectsGenerator generator = new SeparatedProjectsGenerator(
        projectFilesystem,
        createPartialGraphFromBuildRules(ImmutableSet.of(configRule, rule)),
        executionContext,
        ImmutableSet.of(configRule.getBuildTarget()));
    generator.generateProjects();
  }

  @Test
  public void generatedTargetsShouldUseShortNames() throws IOException {
    BuildRule libraryRule = createBuildRuleWithDefaults(
        new BuildTarget("//foo", "library"),
        ImmutableSortedSet.<BuildRule>of(),
        iosLibraryDescription);

    BuildRule binaryRule = createBuildRuleWithDefaults(
        new BuildTarget("//foo", "binary"),
        ImmutableSortedSet.<BuildRule>of(),
        iosBinaryDescription);

    BuildRule testRule = createBuildRuleWithDefaults(
        new BuildTarget("//foo", "test"),
        ImmutableSortedSet.<BuildRule>of(),
        iosTestDescription);

    BuildRule nativeRule = createBuildRuleWithDefaults(
        new BuildTarget("//foo", "native"),
        ImmutableSortedSet.<BuildRule>of(),
        iosTestDescription);

    BuildRule configRule = createXcodeProjectConfigRule(
        "//foo",
        "fooproject",
        ImmutableSet.of(libraryRule, binaryRule, testRule, nativeRule));

    SeparatedProjectsGenerator generator = new SeparatedProjectsGenerator(
        projectFilesystem,
        createPartialGraphFromBuildRules(
            ImmutableSet.of(configRule, libraryRule, binaryRule, testRule, nativeRule)),
        executionContext,
        ImmutableSet.of(configRule.getBuildTarget()));
    generator.generateProjects();

    PBXProject project = getGeneratedProjectOfConfigRule(generator, configRule);
    assertTargetExistsAndReturnTarget(project, "library");
    assertTargetExistsAndReturnTarget(project, "binary");
    assertTargetExistsAndReturnTarget(project, "test");
    assertTargetExistsAndReturnTarget(project, "native");
  }

  private BuildRule createXcodeProjectConfigRule(
      String baseName,
      final String projectName,
      final ImmutableSet<BuildRule> buildRules) {
    return createBuildRuleWithDefaults(
        new BuildTarget(baseName, "project"),
        ImmutableSortedSet.<BuildRule>of(),
        xcodeProjectConfigDescription,
        new Function<XcodeProjectConfigDescription.Arg, XcodeProjectConfigDescription.Arg>() {
          @Override
          public XcodeProjectConfigDescription.Arg apply(XcodeProjectConfigDescription.Arg input) {
            input.projectName = projectName;
            input.rules = buildRules;
            return input;
          }
        });
  }

  private static PBXProject getGeneratedProjectOfConfigRule(
      SeparatedProjectsGenerator generator,
      BuildRule rule) {

    ImmutableMap<BuildTarget, ProjectGenerator> projectGeneratorMap =
        generator.getProjectGenerators();
    assertNotNull(
        "should have called SeparatedProjectsGenerator.generateProjects()",
        projectGeneratorMap);
    ProjectGenerator innerGenerator = projectGeneratorMap.get(rule.getBuildTarget());
    assertNotNull(
        "should have generated project from config rule: " + rule.getBuildTarget(),
        innerGenerator);
    return innerGenerator.getGeneratedProject();
  }
}
