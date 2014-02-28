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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.apple.IosBinaryDescription;
import com.facebook.buck.apple.IosLibraryDescription;
import com.facebook.buck.apple.XcodeProjectConfigDescription;
import com.facebook.buck.apple.xcode.xcodeproj.PBXAggregateTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;

public class SeparatedProjectsGeneratorTest {
  private final ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
  private final ExecutionContext executionContext = TestExecutionContext.newInstance();
  private final IosLibraryDescription iosLibraryDescription = new IosLibraryDescription();
  private final IosBinaryDescription iosBinaryDescription = new IosBinaryDescription();
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

    PBXProject project = Preconditions.checkNotNull(generator.getProjectGenerators())
        .get(configRule.getBuildTarget())
        .getGeneratedProject();

    assertThat("Has only two targets", project.getTargets(), hasSize(2));
    assertThat(
        "One of the targets is the named target",
        project.getTargets(),
        hasItem(isTargetWithName("//foo/bar:somelib")));
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

    PBXProject project = Preconditions.checkNotNull(generator.getProjectGenerators())
        .get(configRule.getBuildTarget())
        .getGeneratedProject();
    PBXTarget target = assertTargetExistsAndReturnTarget(project, "//foo:bin");
    assertHasSingletonFrameworksPhaseWithFrameworkEntries(
        target,
        ImmutableList.of("$BUILT_PRODUCTS_DIR/libsomedep.a"));
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
}
