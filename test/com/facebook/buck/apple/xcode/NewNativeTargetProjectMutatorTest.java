/*
 * Copyright 2014-present Facebook, Inc.
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
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.hamcrest.collection.IsEmptyIterable.emptyIterable;
import static org.hamcrest.core.IsNot.not;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSString;
import com.facebook.buck.apple.GroupedSource;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildFile;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXFileReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXGroup;
import com.facebook.buck.apple.xcode.xcodeproj.PBXHeadersBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;

import org.junit.Before;
import org.junit.Test;

import java.nio.file.Paths;

public class NewNativeTargetProjectMutatorTest {
  private PBXProject generatedProject;
  private PathRelativizer pathRelativizer;
  private SourcePathResolver sourcePathResolver;

  @Before
  public void setUp() {
    BuildRuleResolver buildRuleResolver = new BuildRuleResolver();
    generatedProject = new PBXProject("TestProject");
    sourcePathResolver = new SourcePathResolver(buildRuleResolver);
    pathRelativizer = new PathRelativizer(
        Paths.get("/test/project/root/"),
        Paths.get("/test/project/root/_output"),
        sourcePathResolver);
  }

  @Test
  public void shouldCreateTargetAndTargetGroup() {
    BuildTarget testBuildTarget = BuildTarget.builder("//foo", "test").build();
    NewNativeTargetProjectMutator mutator = new NewNativeTargetProjectMutator(
        pathRelativizer,
        sourcePathResolver,
        testBuildTarget);
    mutator
        .setTargetName("TestTarget")
        .setProduct(
            PBXTarget.ProductType.BUNDLE,
            "TestTargetProduct",
            Paths.get("TestTargetProduct.bundle"))
        .buildTargetAndAddToProject(generatedProject);

    assertTargetExistsAndReturnTarget(generatedProject, "TestTarget");
    assertHasTargetGroupWithName(generatedProject, "TestTarget");
  }

  @Test
  public void testSourceGroups() {
    BuildTarget testBuildTarget = BuildTarget.builder("//foo", "lib").build();
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults(testBuildTarget);

    SourcePath foo = new TestSourcePath("foo.m");
    SourcePath bar = new TestSourcePath("bar.m");
    SourcePath baz = new TestSourcePath("baz.m");
    Iterable<GroupedSource> sources = ImmutableList.of(
        GroupedSource.ofSourceGroup(
            "Group1",
            ImmutableList.of(
                GroupedSource.ofSourcePath(foo),
                GroupedSource.ofSourcePath(bar))),
        GroupedSource.ofSourceGroup(
            "Group2",
            ImmutableList.of(
                GroupedSource.ofSourcePath(baz))));
    ImmutableMap<SourcePath, String> sourceFlags = ImmutableMap.of(bar, "-Wall");
    mutator.setSources(sources, sourceFlags);
    NewNativeTargetProjectMutator.Result result = mutator.buildTargetAndAddToProject(
        generatedProject);

    PBXGroup sourcesGroup = result.targetGroup.getOrCreateChildGroupByName("Sources");

    PBXGroup group1 = (PBXGroup) Iterables.get(sourcesGroup.getChildren(), 0);
    assertEquals("Group1", group1.getName());
    assertThat(group1.getChildren(), hasSize(2));
    PBXFileReference fileRefFoo = (PBXFileReference) Iterables.get(group1.getChildren(), 0);
    assertEquals("foo.m", fileRefFoo.getName());
    PBXFileReference fileRefBar = (PBXFileReference) Iterables.get(group1.getChildren(), 1);
    assertEquals("bar.m", fileRefBar.getName());

    PBXGroup group2 = (PBXGroup) Iterables.get(sourcesGroup.getChildren(), 1);
    assertEquals("Group2", group2.getName());
    assertThat(group2.getChildren(), hasSize(1));
    PBXFileReference fileRefBaz = (PBXFileReference) Iterables.get(group2.getChildren(), 0);
    assertEquals("baz.m", fileRefBaz.getName());
  }

  @Test
  public void testLibraryHeaderGroups() {
    BuildTarget testBuildTarget = BuildTarget.builder("//foo", "lib").build();
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults(testBuildTarget);

    SourcePath foo = new TestSourcePath("foo.h");
    SourcePath bar = new TestSourcePath("bar.h");
    SourcePath baz = new TestSourcePath("baz.h");
    Iterable<GroupedSource> sources = ImmutableList.of(
        GroupedSource.ofSourceGroup(
            "HeaderGroup1",
            ImmutableList.of(
                GroupedSource.ofSourcePath(foo),
                GroupedSource.ofSourcePath(bar))),
        GroupedSource.ofSourceGroup(
            "HeaderGroup2",
            ImmutableList.of(
                GroupedSource.ofSourcePath(baz))));
    ImmutableMap<SourcePath, String> sourceFlags = ImmutableMap.of(
        bar, "public",
        baz, "private");
    mutator.setSources(sources, sourceFlags);
    NewNativeTargetProjectMutator.Result result = mutator.buildTargetAndAddToProject(
        generatedProject);

    PBXGroup sourcesGroup = result.targetGroup.getOrCreateChildGroupByName("Sources");

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
    assertThat(group2.getChildren(), hasSize(1));
    PBXFileReference fileRefBaz = (PBXFileReference) Iterables.get(group2.getChildren(), 0);
    assertEquals("baz.h", fileRefBaz.getName());

    PBXBuildPhase headersBuildPhase =
        Iterables.find(result.target.getBuildPhases(), new Predicate<PBXBuildPhase>() {
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
    assertTrue(
        "baz.h should have settings dictionary",
        bazHeaderBuildFile.getSettings().isPresent());
    NSDictionary blechBuildFileSettings = bazHeaderBuildFile.getSettings().get();
    NSArray blechAttributes = (NSArray) blechBuildFileSettings.get("ATTRIBUTES");
    assertArrayEquals(new NSString[]{new NSString("Private")}, blechAttributes.getArray());
  }

  @Test
  public void testSuppressCopyHeaderOption() {
    Iterable<GroupedSource> sources = ImmutableList.of(
        GroupedSource.ofSourcePath(new TestSourcePath("foo.h")));

    {
      BuildTarget testBuildTarget = BuildTarget.builder("//foo", "lib").build();
      NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults(testBuildTarget);
      mutator
          .setShouldGenerateCopyHeadersPhase(false)
          .setSources(sources, ImmutableMap.<SourcePath, String>of());
      NewNativeTargetProjectMutator.Result result = mutator.buildTargetAndAddToProject(
          generatedProject);

      assertThat(
          "copy headers phase should be omitted",
          Iterables.filter(result.target.getBuildPhases(), PBXHeadersBuildPhase.class),
          emptyIterable());
    }

    {
      BuildTarget testBuildTarget = BuildTarget.builder("//foo", "lib").build();
      NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults(testBuildTarget);
      mutator
          .setShouldGenerateCopyHeadersPhase(true)
          .setSources(sources, ImmutableMap.<SourcePath, String>of());
      NewNativeTargetProjectMutator.Result result = mutator.buildTargetAndAddToProject(
          generatedProject);
      assertThat(
          "copy headers phase should be generated",
          Iterables.filter(result.target.getBuildPhases(), PBXHeadersBuildPhase.class),
          not(emptyIterable()));
    }
  }

  private NewNativeTargetProjectMutator mutatorWithCommonDefaults(BuildTarget target) {
    NewNativeTargetProjectMutator mutator = new NewNativeTargetProjectMutator(
        pathRelativizer,
        sourcePathResolver,
        target);
    mutator
        .setTargetName("TestTarget")
        .setProduct(
            PBXTarget.ProductType.BUNDLE,
            "TestTargetProduct",
            Paths.get("TestTargetProduct.bundle"));
    return mutator;
  }

  private static void assertHasTargetGroupWithName(PBXProject project, final String name) {
    assertThat(
        "Should contain a target group named: " + name,
        Iterables.filter(
            project.getMainGroup().getChildren(), new Predicate<PBXReference>() {
              @Override
              public boolean apply(PBXReference input) {
                return input.getName().equals(name);
              }
            }),
        not(emptyIterable()));
  }

}
