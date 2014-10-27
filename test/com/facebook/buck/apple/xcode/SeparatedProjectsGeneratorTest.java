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
import static com.facebook.buck.apple.xcode.ProjectGeneratorTestUtils.createAppleBundleBuildRule;
import static com.facebook.buck.apple.xcode.ProjectGeneratorTestUtils.createBuildRuleWithDefaults;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.dd.plist.NSString;
import com.facebook.buck.apple.AppleBinaryDescription;
import com.facebook.buck.apple.AppleBundleDescription;
import com.facebook.buck.apple.AppleBundleExtension;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleLibraryDescription;
import com.facebook.buck.apple.AppleNativeTargetDescriptionArg;
import com.facebook.buck.apple.XcodeProjectConfigDescription;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.apple.xcode.xcodeproj.XCBuildConfiguration;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.coercer.XcodeRuleConfiguration;
import com.facebook.buck.rules.coercer.XcodeRuleConfigurationLayer;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.RuleMap;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Function;
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
  private final AppleConfig appleConfig = new AppleConfig(new FakeBuckConfig());
  private final AppleLibraryDescription appleLibraryDescription = new AppleLibraryDescription(
      appleConfig);
  private final AppleBinaryDescription appleBinaryDescription = new AppleBinaryDescription(
      appleConfig);
  private final AppleBundleDescription appleBundleDescription = new AppleBundleDescription();
  private final XcodeProjectConfigDescription xcodeProjectConfigDescription =
      new XcodeProjectConfigDescription();

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Test(expected = HumanReadableException.class)
  public void errorsIfNotPassingInXcodeConfigRules() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildRule rule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "thing").build(),
        ImmutableSortedSet.<BuildRule>of(),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(rule);

    SeparatedProjectsGenerator generator = new SeparatedProjectsGenerator(
        new SourcePathResolver(resolver),
        projectFilesystem,
        RuleMap.createGraphFromBuildRules(resolver),
        executionContext,
        ImmutableSet.of(rule.getBuildTarget()),
        ImmutableSet.<ProjectGenerator.Option>of());
    generator.generateProjects();
  }

  @Test
  public void errorsIfPassingInNonexistentRule() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildRule rule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "thing").build(),
        ImmutableSortedSet.<BuildRule>of(),
        appleLibraryDescription,
        resolver);
    expectedException.expect(HumanReadableException.class);
    expectedException.expectMessage("target not found");

    SeparatedProjectsGenerator generator = new SeparatedProjectsGenerator(
        new SourcePathResolver(resolver),
        projectFilesystem,
        RuleMap.createGraphFromBuildRules(resolver),
        executionContext,
        ImmutableSet.of(rule.getBuildTarget()),
        ImmutableSet.<ProjectGenerator.Option>of());
    generator.generateProjects();
  }

  @Test
  public void generatesProjectFilesInCorrectLocations() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildRule rule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo/bar", "somelib").build(),
        ImmutableSortedSet.<BuildRule>of(),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(rule);

    BuildRule configRule = createXcodeProjectConfigRule(
        "//foo/bar",
        "fooproject",
        resolver,
        ImmutableSortedSet.of(rule.getBuildTarget()));
    resolver.addToIndex(configRule);

    SeparatedProjectsGenerator generator = new SeparatedProjectsGenerator(
        new SourcePathResolver(resolver),
        projectFilesystem,
        RuleMap.createGraphFromBuildRules(resolver),
        executionContext,
        ImmutableSet.of(configRule.getBuildTarget()),
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
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildRule depRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//elsewhere", "somedep").build(),
        ImmutableSortedSet.<BuildRule>of(),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(depRule);

    BuildRule rule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo/bar", "somelib").build(),
        ImmutableSortedSet.of(depRule),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(rule);

    BuildRule configRule = createXcodeProjectConfigRule(
        "//foo/bar",
        "fooproject",
        resolver,
        ImmutableSortedSet.of(rule.getBuildTarget()));
    resolver.addToIndex(configRule);

    SeparatedProjectsGenerator generator = new SeparatedProjectsGenerator(
        new SourcePathResolver(resolver),
        projectFilesystem,
        RuleMap.createGraphFromBuildRules(resolver),
        executionContext,
        ImmutableSet.of(configRule.getBuildTarget()),
        ImmutableSet.<ProjectGenerator.Option>of());
    generator.generateProjects();

    PBXProject project = getGeneratedProjectOfConfigRule(generator, configRule);

    assertThat("Has only one targets", project.getTargets(), hasSize(1));
    assertThat(
        "The target is the named target",
        project.getTargets(),
        hasItem(isTargetWithName("somelib")));
  }

  @Test
  public void generatedBinariesLinksLibraryDependencies() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildRule depRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//elsewhere", "somedep").build(),
        ImmutableSortedSet.<BuildRule>of(),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(depRule);

    BuildRule dynamicLibraryDep = createBuildRuleWithDefaults(
        BuildTarget.builder("//dep", "dynamic").setFlavor(
            AppleLibraryDescription.DYNAMIC_LIBRARY).build(),
        ImmutableSortedSet.of(depRule),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(dynamicLibraryDep);

    BuildRule rule = createAppleBundleBuildRule(
        BuildTarget.builder("//foo", "bin").build(),
        resolver,
        appleBundleDescription,
        dynamicLibraryDep,
        AppleBundleExtension.FRAMEWORK);
    resolver.addToIndex(rule);

    BuildRule configRule = createXcodeProjectConfigRule(
        "//foo/bar",
        "fooproject",
        resolver,
        ImmutableSortedSet.of(rule.getBuildTarget()));
    resolver.addToIndex(configRule);

    SeparatedProjectsGenerator generator = new SeparatedProjectsGenerator(
        new SourcePathResolver(resolver),
        projectFilesystem,
        RuleMap.createGraphFromBuildRules(resolver),
        executionContext,
        ImmutableSet.of(configRule.getBuildTarget()),
        ImmutableSet.<ProjectGenerator.Option>of());
    generator.generateProjects();

    PBXProject project = getGeneratedProjectOfConfigRule(generator, configRule);
    PBXTarget target = assertTargetExistsAndReturnTarget(project, "bin");
    assertHasSingletonFrameworksPhaseWithFrameworkEntries(
        target,
        ImmutableList.of("$BUILT_PRODUCTS_DIR/libsomedep.a"));
  }

  @Test
  public void generatedProjectsReferencesXcconfigFilesDirectly() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildRule rule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "rule").build(),
        resolver,
        ImmutableSortedSet.<BuildRule>of(),
        appleLibraryDescription,
        new Function<AppleNativeTargetDescriptionArg, AppleNativeTargetDescriptionArg>() {
          @Override
          public AppleNativeTargetDescriptionArg apply(AppleNativeTargetDescriptionArg input) {
            input.configs = Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    new XcodeRuleConfiguration(
                        ImmutableList.of(
                            new XcodeRuleConfigurationLayer(
                                new PathSourcePath(Paths.get("project.xcconfig"))),
                            new XcodeRuleConfigurationLayer(
                                new PathSourcePath(Paths.get("target.xcconfig")))))));
            return input;
          }
        });
    resolver.addToIndex(rule);

    BuildRule configRule = createXcodeProjectConfigRule(
        "//foo",
        "fooproject",
        resolver,
        ImmutableSortedSet.of(rule.getBuildTarget()));
    resolver.addToIndex(configRule);

    SeparatedProjectsGenerator generator = new SeparatedProjectsGenerator(
        new SourcePathResolver(resolver),
        projectFilesystem,
        RuleMap.createGraphFromBuildRules(resolver),
        executionContext,
        ImmutableSet.of(configRule.getBuildTarget()),
        ImmutableSet.<ProjectGenerator.Option>of());
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
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildRule rule1 = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "rule1").build(),
        resolver,
        ImmutableSortedSet.<BuildRule>of(),
        appleLibraryDescription,
        new Function<AppleNativeTargetDescriptionArg, AppleNativeTargetDescriptionArg>() {
          @Override
          public AppleNativeTargetDescriptionArg apply(AppleNativeTargetDescriptionArg input) {
            input.configs = Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    new XcodeRuleConfiguration(
                        ImmutableList.of(
                            new XcodeRuleConfigurationLayer(
                                new PathSourcePath(Paths.get("project.xcconfig"))),
                            new XcodeRuleConfigurationLayer(ImmutableMap.of(
                                "PROJECT_FLAG1", "p1",
                                "PROJECT_FLAG2", "p2")),
                            new XcodeRuleConfigurationLayer(
                                new PathSourcePath(Paths.get("target.xcconfig"))),
                            new XcodeRuleConfigurationLayer(
                                ImmutableMap.of(
                                    "TARGET_FLAG1", "t1",
                                    "TARGET_FLAG2", "t2"))))));
            return input;
          }
        });
    resolver.addToIndex(rule1);

    BuildRule rule2 = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "rule2").build(),
        resolver,
        ImmutableSortedSet.<BuildRule>of(),
        appleLibraryDescription,
        new Function<AppleNativeTargetDescriptionArg, AppleNativeTargetDescriptionArg>() {
          @Override
          public AppleNativeTargetDescriptionArg apply(AppleNativeTargetDescriptionArg input) {
            input.configs = Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    new XcodeRuleConfiguration(
                        ImmutableList.of(
                            new XcodeRuleConfigurationLayer(
                                new PathSourcePath(Paths.get("project.xcconfig"))),
                            new XcodeRuleConfigurationLayer(ImmutableMap.of(
                                "PROJECT_FLAG1", "p1",
                                "PROJECT_FLAG2", "p2")),
                            new XcodeRuleConfigurationLayer(
                                new PathSourcePath(Paths.get("target.xcconfig"))),
                            new XcodeRuleConfigurationLayer(
                                ImmutableMap.of(
                                    "TARGET_FLAG3", "t3",
                                    "TARGET_FLAG4", "t4"))))));
            return input;
          }
        });
    resolver.addToIndex(rule2);

    BuildRule configRule = createXcodeProjectConfigRule(
        "//foo",
        "fooproject",
        resolver,
        ImmutableSortedSet.of(rule1.getBuildTarget(), rule2.getBuildTarget()));
    resolver.addToIndex(configRule);

    SeparatedProjectsGenerator generator = new SeparatedProjectsGenerator(
        new SourcePathResolver(resolver),
        projectFilesystem,
        RuleMap.createGraphFromBuildRules(resolver),
        executionContext,
        ImmutableSet.of(configRule.getBuildTarget()),
        ImmutableSet.<ProjectGenerator.Option>of());
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
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildRule rule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "rule").build(),
        resolver,
        ImmutableSortedSet.<BuildRule>of(),
        appleLibraryDescription,
        new Function<AppleNativeTargetDescriptionArg, AppleNativeTargetDescriptionArg>() {
          @Override
          public AppleNativeTargetDescriptionArg apply(AppleNativeTargetDescriptionArg input) {
            // this only has one layer, accepted layers is 2 or 4
            input.configs = Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    new XcodeRuleConfiguration(
                        ImmutableList.of(
                            new XcodeRuleConfigurationLayer(
                                new PathSourcePath(Paths.get("target.xcconfig")))))));
            return input;
          }
        });
    resolver.addToIndex(rule);

    BuildRule configRule = createXcodeProjectConfigRule(
        "//foo",
        "fooproject",
        resolver,
        ImmutableSortedSet.of(rule.getBuildTarget()));
    resolver.addToIndex(configRule);

    SeparatedProjectsGenerator generator = new SeparatedProjectsGenerator(
        new SourcePathResolver(resolver),
        projectFilesystem,
        RuleMap.createGraphFromBuildRules(resolver),
        executionContext,
        ImmutableSet.of(configRule.getBuildTarget()),
        ImmutableSet.<ProjectGenerator.Option>of());
    generator.generateProjects();
  }

  @Test
  public void generatedTargetsShouldUseShortNames() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildRule libraryRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "library").build(),
        ImmutableSortedSet.<BuildRule>of(),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(libraryRule);

    BuildRule binaryDep = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "binarybin").setFlavor(
            AppleLibraryDescription.DYNAMIC_LIBRARY).build(),
        ImmutableSortedSet.<BuildRule>of(),
        appleBinaryDescription,
        resolver);
    resolver.addToIndex(binaryDep);

    BuildRule binaryRule = createAppleBundleBuildRule(
        BuildTarget.builder("//foo", "binary").build(),
        resolver,
        appleBundleDescription,
        binaryDep,
        AppleBundleExtension.APP);
    resolver.addToIndex(binaryRule);

    BuildRule nativeRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "native").build(),
        ImmutableSortedSet.<BuildRule>of(),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(nativeRule);

    BuildRule configRule = createXcodeProjectConfigRule(
        "//foo",
        "fooproject",
        resolver,
        ImmutableSortedSet.of(
            libraryRule.getBuildTarget(),
            binaryRule.getBuildTarget(),
            nativeRule.getBuildTarget()));
    resolver.addToIndex(configRule);

    SeparatedProjectsGenerator generator = new SeparatedProjectsGenerator(
        new SourcePathResolver(resolver),
        projectFilesystem,
        RuleMap.createGraphFromBuildRules(resolver),
        executionContext,
        ImmutableSet.of(configRule.getBuildTarget()),
        ImmutableSet.<ProjectGenerator.Option>of());
    generator.generateProjects();

    PBXProject project = getGeneratedProjectOfConfigRule(generator, configRule);
    assertTargetExistsAndReturnTarget(project, "library");
    assertTargetExistsAndReturnTarget(project, "binary");
    assertTargetExistsAndReturnTarget(project, "native");
  }

  @Test
  public void shouldReturnListOfGeneratedProjects() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildRule fooRule1 = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "rule1").build(),
        ImmutableSortedSet.<BuildRule>of(),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(fooRule1);

    BuildRule fooConfigRule = createXcodeProjectConfigRule(
        "//foo",
        "fooproject",
        resolver,
        ImmutableSortedSet.of(fooRule1.getBuildTarget()));
    resolver.addToIndex(fooConfigRule);

    BuildRule barRule2 = createBuildRuleWithDefaults(
        BuildTarget.builder("//bar", "rule2").build(),
        ImmutableSortedSet.<BuildRule>of(),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(barRule2);

    BuildRule barConfigRule = createXcodeProjectConfigRule(
        "//bar",
        "barproject",
        resolver,
        ImmutableSortedSet.of(barRule2.getBuildTarget()));
    resolver.addToIndex(barConfigRule);

    SeparatedProjectsGenerator generator = new SeparatedProjectsGenerator(
        new SourcePathResolver(resolver),
        projectFilesystem,
        RuleMap.createGraphFromBuildRules(resolver),
        executionContext,
        ImmutableSet.of(fooConfigRule.getBuildTarget(), barConfigRule.getBuildTarget()),
        ImmutableSet.<ProjectGenerator.Option>of());

    ImmutableSet<Path> paths = generator.generateProjects();

    assertEquals(
        ImmutableSet.of(
            Paths.get("foo/fooproject.xcodeproj"),
            Paths.get("bar/barproject.xcodeproj")),
        paths);
  }

  private BuildRule createXcodeProjectConfigRule(
      String baseName,
      final String projectName,
      BuildRuleResolver resolver,
      final ImmutableSortedSet<BuildTarget> buildRules) {
    return createBuildRuleWithDefaults(
        BuildTarget.builder(baseName, "project").build(),
        resolver,
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
