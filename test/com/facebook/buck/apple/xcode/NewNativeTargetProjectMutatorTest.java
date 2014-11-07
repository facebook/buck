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

import static com.facebook.buck.apple.xcode.ProjectGeneratorTestUtils.assertHasSingletonFrameworksPhaseWithFrameworkEntries;
import static com.facebook.buck.apple.xcode.ProjectGeneratorTestUtils.assertHasSingletonPhaseWithEntries;
import static com.facebook.buck.apple.xcode.ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget;
import static com.facebook.buck.apple.xcode.ProjectGeneratorTestUtils.createDescriptionArgWithDefaults;
import static com.facebook.buck.apple.xcode.ProjectGeneratorTestUtils.getSingletonPhaseByType;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.hamcrest.collection.IsEmptyIterable.emptyIterable;
import static org.hamcrest.core.IsNot.not;
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
import com.facebook.buck.apple.AppleResourceDescription;
import com.facebook.buck.apple.GroupedSource;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildFile;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXFileReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXGroup;
import com.facebook.buck.apple.xcode.xcodeproj.PBXHeadersBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXResourcesBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXShellScriptBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import org.junit.Before;
import org.junit.Test;

import java.nio.file.Paths;

public class NewNativeTargetProjectMutatorTest {
  private ExecutionContext executionContext;
  private BuildRuleResolver buildRuleResolver;
  private PBXProject generatedProject;
  private PathRelativizer pathRelativizer;
  private SourcePathResolver sourcePathResolver;

  @Before
  public void setUp() {
    executionContext = TestExecutionContext.newInstance();
    buildRuleResolver = new BuildRuleResolver();
    generatedProject = new PBXProject("TestProject");
    sourcePathResolver = new SourcePathResolver(buildRuleResolver);
    pathRelativizer = new PathRelativizer(
        Paths.get("/test/project/root/"),
        Paths.get("/test/project/root/_output"),
        sourcePathResolver);
  }

  @Test
  public void shouldCreateTargetAndTargetGroup() throws NoSuchBuildTargetException {
    BuildTarget testBuildTarget = BuildTarget.builder("//foo", "test").build();
    NewNativeTargetProjectMutator mutator = new NewNativeTargetProjectMutator(
        TargetGraphFactory.newInstance(ImmutableSet.<TargetNode<?>>of()),
        executionContext,
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
  public void testSourceGroups() throws NoSuchBuildTargetException {
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
  public void testLibraryHeaderGroups() throws NoSuchBuildTargetException {
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
  public void testSuppressCopyHeaderOption() throws NoSuchBuildTargetException {
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

  @Test
  public void testFrameworkBuildPhase() throws NoSuchBuildTargetException {
    BuildTarget testBuildTarget = BuildTarget.builder("//foo", "binary").build();
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults(testBuildTarget);
    mutator.setFrameworks(ImmutableSet.of("$SDKROOT/Foo.framework"));
    mutator.setArchives(ImmutableSet.of(
        new PBXFileReference(
            "libdep.a",
            "libdep.a",
            PBXReference.SourceTree.BUILT_PRODUCTS_DIR)));
    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject);
    assertHasSingletonFrameworksPhaseWithFrameworkEntries(
        result.target,
        ImmutableList.of(
            "$SDKROOT/Foo.framework",
            "$BUILT_PRODUCTS_DIR/libdep.a"));
  }

  @Test
  public void testResourcesBuildPhase() throws NoSuchBuildTargetException {
    BuildTarget testBuildTarget = BuildTarget.builder("//foo", "binary").build();
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults(testBuildTarget);

    AppleResourceDescription appleResourceDescription = new AppleResourceDescription();
    AppleResourceDescription.Arg arg = createDescriptionArgWithDefaults(appleResourceDescription);
    arg.files = ImmutableSet.<SourcePath>of(new TestSourcePath("foo.png"));

    mutator.setResources(ImmutableSet.of(arg));
    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject);

    assertHasSingletonPhaseWithEntries(
        result.target,
        PBXResourcesBuildPhase.class,
        ImmutableList.of("$SOURCE_ROOT/../foo.png"));
  }

  @Test
  public void assetCatalogsBuildPhaseBuildsBothCommonAndBundledAssetCatalogs()
      throws NoSuchBuildTargetException {
    AppleAssetCatalogDescription.Arg arg1 = new AppleAssetCatalogDescription.Arg();
    arg1.dirs = ImmutableSet.of(Paths.get("AssetCatalog1.xcassets"));
    arg1.copyToBundles = Optional.of(false);

    AppleAssetCatalogDescription.Arg arg2 = new AppleAssetCatalogDescription.Arg();
    arg2.dirs = ImmutableSet.of(Paths.get("AssetCatalog2.xcassets"));
    arg2.copyToBundles = Optional.of(true);

    BuildTarget testBuildTarget = BuildTarget.builder("//foo", "binary").build();
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults(testBuildTarget);
    mutator.setAssetCatalogs(
        Paths.get("compile_asset_catalogs"),
        ImmutableSet.of(arg1, arg2));
    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject);
    assertTrue(hasShellScriptPhaseToCompileCommonAndSplitAssetCatalogs(result.target));
  }

  @Test
  public void testScriptBuildPhase() throws NoSuchBuildTargetException{
    BuildTarget testBuildTarget = BuildTarget.builder("//foo", "library").build();
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults(testBuildTarget);

    TargetNode<?> genruleNode = GenruleBuilder
        .newGenruleBuilder(BuildTarget.builder("//foo", "script").build())
        .setSrcs(ImmutableList.<SourcePath>of(new TestSourcePath("script/input.png")))
        .setBash("echo \"hello world!\"")
        .setOut("helloworld.txt")
        .build();

    mutator.setPostBuildRunScriptPhases(ImmutableList.<TargetNode<?>>of(genruleNode));
    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject);

    PBXShellScriptBuildPhase phase =
        getSingletonPhaseByType(result.target, PBXShellScriptBuildPhase.class);
    assertEquals(
        "Should set input paths correctly",
        "../script/input.png",
        Iterables.getOnlyElement(phase.getInputPaths()));
    assertEquals(
        "should set script correctly",
        "/bin/bash -e -c 'echo \"hello world!\"'",
        phase.getShellScript());
  }

  private NewNativeTargetProjectMutator mutatorWithCommonDefaults(BuildTarget target) {
    NewNativeTargetProjectMutator mutator = new NewNativeTargetProjectMutator(
        TargetGraphFactory.newInstance(ImmutableSet.<TargetNode<?>>of()),
        executionContext,
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
          assertFalse("should have only one invocation", foundCommonAssetCatalogCompileCommand);
          foundCommonAssetCatalogCompileCommand = true;
        }
      }
    }

    return foundCommonAssetCatalogCompileCommand && foundSplitAssetCatalogCompileCommand;
  }
}
