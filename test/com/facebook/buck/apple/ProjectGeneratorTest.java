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

package com.facebook.buck.apple;

import static com.facebook.buck.apple.ProjectGeneratorTestUtils.assertHasSingletonCopyFilesPhaseWithFileEntries;
import static com.facebook.buck.apple.ProjectGeneratorTestUtils.assertHasSingletonFrameworksPhaseWithFrameworkEntries;
import static com.facebook.buck.apple.ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget;
import static com.facebook.buck.apple.ProjectGeneratorTestUtils.getSingletonPhaseByType;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.endsWith;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.dd.plist.NSDictionary;
import com.dd.plist.NSString;
import com.facebook.buck.apple.clang.HeaderMap;
import com.facebook.buck.apple.xcode.xcodeproj.CopyFilePhaseDestinationSpec;
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
import com.facebook.buck.apple.xcode.xcodeproj.PBXVariantGroup;
import com.facebook.buck.apple.xcode.xcodeproj.ProductType;
import com.facebook.buck.apple.xcode.xcodeproj.SourceTreePath;
import com.facebook.buck.apple.xcode.xcodeproj.XCBuildConfiguration;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.js.IosReactNativeLibraryBuilder;
import com.facebook.buck.js.ReactNativeBuckConfig;
import com.facebook.buck.js.ReactNativeFlavors;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.buck.shell.ExportFileBuilder;
import com.facebook.buck.shell.ExportFileDescription;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.AllExistingProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.timing.SettableFakeClock;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.io.ByteStreams;

import org.hamcrest.FeatureMatcher;
import org.hamcrest.Matcher;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Iterator;
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

  private SettableFakeClock clock;
  private ProjectFilesystem projectFilesystem;
  private FakeProjectFilesystem fakeProjectFilesystem;

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Before
  public void setUp() throws IOException {
    clock = new SettableFakeClock(0, 0);
    fakeProjectFilesystem = new FakeProjectFilesystem(clock);
    projectFilesystem = fakeProjectFilesystem;

    // Add support files needed by project generation to fake filesystem.
    projectFilesystem.writeContentsToPath(
        "",
        Paths.get(ProjectGenerator.PATH_TO_ASSET_CATALOG_BUILD_PHASE_SCRIPT));
    projectFilesystem.writeContentsToPath(
        "",
        Paths.get(ProjectGenerator.PATH_TO_ASSET_CATALOG_COMPILER));

    // Add files and directories used to test resources.
    projectFilesystem.createParentDirs(Paths.get("foodir", "foo.png"));
    projectFilesystem.writeContentsToPath(
        "",
        Paths.get("foodir", "foo.png"));
    projectFilesystem.writeContentsToPath(
        "",
        Paths.get("bar.png"));
    fakeProjectFilesystem.touch(Paths.get("Base.lproj", "Bar.storyboard"));
  }

  @Test
  public void testProjectStructureForEmptyProject() throws IOException {
    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of());

    projectGenerator.createXcodeProjects();

    Optional<String> pbxproj = projectFilesystem.readFileIfItExists(OUTPUT_PROJECT_FILE_PATH);
    assertTrue(pbxproj.isPresent());
  }

  @Test
  public void testCreateDirectoryStructure() throws IOException {
    BuildTarget buildTarget1 = BuildTarget.builder("//foo/bar", "target1").build();
    TargetNode<?> node1 = AppleLibraryBuilder.createBuilder(buildTarget1).build();

    BuildTarget buildTarget2 = BuildTarget.builder("//foo/foo", "target2").build();
    TargetNode<?> node2 = AppleLibraryBuilder.createBuilder(buildTarget2).build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(node1, node2),
        ImmutableSet.of(
            ProjectGenerator.Option.CREATE_DIRECTORY_STRUCTURE,
            ProjectGenerator.Option.USE_SHORT_NAMES_FOR_TARGETS));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXGroup mainGroup = project.getMainGroup();

    PBXGroup groupFoo = null;
    for (PBXReference reference : mainGroup.getChildren()) {
      if (reference instanceof PBXGroup && "foo".equals(reference.getName())) {
        groupFoo = (PBXGroup) reference;
      }
    }
    assertNotNull("Project should have a group called foo", groupFoo);

    assertEquals("foo", groupFoo.getName());
    assertThat(groupFoo.getChildren(), hasSize(2));

    PBXGroup groupFooBar = (PBXGroup) Iterables.get(groupFoo.getChildren(), 0);
    assertEquals("bar", groupFooBar.getName());
    assertThat(groupFooBar.getChildren(), hasSize(1));

    PBXGroup groupFooFoo = (PBXGroup) Iterables.get(groupFoo.getChildren(), 1);
    assertEquals("foo", groupFooFoo.getName());
    assertThat(groupFooFoo.getChildren(), hasSize(1));

    PBXGroup groupFooBarTarget1 = (PBXGroup) Iterables.get(groupFooBar.getChildren(), 0);
    assertEquals("target1", groupFooBarTarget1.getName());

    PBXGroup groupFooFooTarget2 = (PBXGroup) Iterables.get(groupFooFoo.getChildren(), 0);
    assertEquals("target2", groupFooFooTarget2.getName());
  }

  @Test
  public void shouldNotCreateHeaderSymlinkTreesWhenTheyAreDisabled() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .setUseBuckHeaderMaps(Optional.of(false))
        .setHeaders(
            ImmutableSortedSet.<SourcePath>of(
                new TestSourcePath("HeaderGroup1/foo.h")))
        .setExportedHeaders(
            ImmutableSortedSet.<SourcePath>of(
                new TestSourcePath("HeaderGroup1/bar.h")))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(node));

    projectGenerator.createXcodeProjects();

    // No header map should be generated
    List<Path> headerSymlinkTrees = projectGenerator.getGeneratedHeaderSymlinkTrees();
    assertThat(headerSymlinkTrees, hasSize(0));
  }

  @Test
  public void testLibraryHeaderGroupsWithHeaderSymlinkTrees() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .setSrcs(Optional.of(ImmutableList.<SourceWithFlags>of()))
        .setHeaders(
            ImmutableSortedSet.<SourcePath>of(
                new TestSourcePath("HeaderGroup1/foo.h"),
                new TestSourcePath("HeaderGroup2/baz.h")))
        .setExportedHeaders(
            ImmutableSortedSet.<SourcePath>of(
                new TestSourcePath("HeaderGroup1/bar.h")))
        .setUseBuckHeaderMaps(Optional.of(true))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(node));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXGroup targetGroup =
        project.getMainGroup().getOrCreateChildGroupByName(buildTarget.getFullyQualifiedName());
    PBXGroup sourcesGroup = targetGroup.getOrCreateChildGroupByName("Sources");

    assertThat(sourcesGroup.getChildren(), hasSize(2));

    PBXGroup group1 = (PBXGroup) Iterables.get(sourcesGroup.getChildren(), 0);
    assertEquals("HeaderGroup1", group1.getName());
    assertThat(group1.getChildren(), hasSize(2));
    PBXFileReference fileRefFoo = (PBXFileReference) Iterables.get(group1.getChildren(), 0);
    assertEquals("bar.h", fileRefFoo.getName());
    PBXFileReference fileRefBar = (PBXFileReference) Iterables.get(group1.getChildren(), 1);
    assertEquals("foo.h", fileRefBar.getName());

    PBXGroup group2 = (PBXGroup) Iterables.get(sourcesGroup.getChildren(), 1);
    assertEquals("HeaderGroup2", group2.getName());
    assertThat(group2.getChildren(), hasSize(1));
    PBXFileReference fileRefBaz = (PBXFileReference) Iterables.get(group2.getChildren(), 0);
    assertEquals("baz.h", fileRefBaz.getName());

    // There should be no PBXHeadersBuildPhase in the 'Buck header map mode'.
    PBXTarget target = assertTargetExistsAndReturnTarget(project, "//foo:lib");
    assertEquals(
        Optional.<PBXBuildPhase>absent(),
        Iterables.tryFind(
            target.getBuildPhases(), new Predicate<PBXBuildPhase>() {
              @Override
              public boolean apply(PBXBuildPhase input) {
                return input instanceof PBXHeadersBuildPhase;
              }
            }));

    List<Path> headerSymlinkTrees = projectGenerator.getGeneratedHeaderSymlinkTrees();
    assertThat(headerSymlinkTrees, hasSize(2));

    assertEquals(
        "buck-out/gen/foo/lib-public-header-symlink-tree",
        headerSymlinkTrees.get(0).toString());
    assertThatHeaderSymlinkTreeContains(
        Paths.get("buck-out/gen/foo/lib-public-header-symlink-tree"),
        ImmutableMap.of("lib/bar.h", "HeaderGroup1/bar.h"));

    assertEquals(
        "buck-out/gen/foo/lib-private-header-symlink-tree",
        headerSymlinkTrees.get(1).toString());
    assertThatHeaderSymlinkTreeContains(
        Paths.get("buck-out/gen/foo/lib-private-header-symlink-tree"),
        ImmutableMap.<String, String>builder()
            .put("lib/foo.h", "HeaderGroup1/foo.h")
            .put("lib/baz.h", "HeaderGroup2/baz.h")
            .put("foo.h", "HeaderGroup1/foo.h")
            .put("bar.h", "HeaderGroup1/bar.h")
            .put("baz.h", "HeaderGroup2/baz.h")
            .build());
  }

  @Test
  public void testLibraryHeaderGroupsWithMappedHeaders() throws IOException {
    BuildTarget privateGeneratedTarget = BuildTarget.builder("//foo", "generated1.h").build();
    BuildTarget publicGeneratedTarget = BuildTarget.builder("//foo", "generated2.h").build();

    TargetNode<?> privateGeneratedNode =
        ExportFileBuilder.newExportFileBuilder(privateGeneratedTarget).build();
    TargetNode<?> publicGeneratedNode =
        ExportFileBuilder.newExportFileBuilder(publicGeneratedTarget).build();

    BuildTarget buildTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .setSrcs(Optional.of(ImmutableList.<SourceWithFlags>of()))
        .setHeaders(
            ImmutableMap.<String, SourcePath>of(
                "any/name.h", new TestSourcePath("HeaderGroup1/foo.h"),
                "different/name.h", new TestSourcePath("HeaderGroup2/baz.h"),
                "one/more/name.h", new BuildTargetSourcePath(privateGeneratedTarget)))
        .setExportedHeaders(
            ImmutableMap.<String, SourcePath>of(
                "yet/another/name.h", new TestSourcePath("HeaderGroup1/bar.h"),
                "and/one/more.h", new BuildTargetSourcePath(publicGeneratedTarget)))
        .setUseBuckHeaderMaps(Optional.of(true))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(node, privateGeneratedNode, publicGeneratedNode));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXGroup targetGroup =
        project.getMainGroup().getOrCreateChildGroupByName(buildTarget.getFullyQualifiedName());
    PBXGroup sourcesGroup = targetGroup.getOrCreateChildGroupByName("Sources");

    assertThat(sourcesGroup.getChildren(), hasSize(3));

    PBXGroup group1 = (PBXGroup) Iterables.get(sourcesGroup.getChildren(), 0);
    assertEquals("HeaderGroup1", group1.getName());
    assertThat(group1.getChildren(), hasSize(2));
    PBXFileReference fileRefFoo = (PBXFileReference) Iterables.get(group1.getChildren(), 0);
    assertEquals("bar.h", fileRefFoo.getName());
    PBXFileReference fileRefBar = (PBXFileReference) Iterables.get(group1.getChildren(), 1);
    assertEquals("foo.h", fileRefBar.getName());

    PBXGroup group2 = (PBXGroup) Iterables.get(sourcesGroup.getChildren(), 1);
    assertEquals("HeaderGroup2", group2.getName());
    assertThat(group2.getChildren(), hasSize(1));
    PBXFileReference fileRefBaz = (PBXFileReference) Iterables.get(group2.getChildren(), 0);
    assertEquals("baz.h", fileRefBaz.getName());

    PBXGroup group3 = (PBXGroup) Iterables.get(sourcesGroup.getChildren(), 2);
    assertEquals("foo", group3.getName());
    assertThat(group3.getChildren(), hasSize(2));
    PBXFileReference fileRefGenerated1 = (PBXFileReference) Iterables.get(group3.getChildren(), 0);
    assertEquals("generated1.h", fileRefGenerated1.getName());
    PBXFileReference fileRefGenerated2 = (PBXFileReference) Iterables.get(group3.getChildren(), 1);
    assertEquals("generated2.h", fileRefGenerated2.getName());

    // There should be no PBXHeadersBuildPhase in the 'Buck header map mode'.
    PBXTarget target = assertTargetExistsAndReturnTarget(project, "//foo:lib");
    assertEquals(
        Optional.<PBXBuildPhase>absent(),
        Iterables.tryFind(
            target.getBuildPhases(), new Predicate<PBXBuildPhase>() {
              @Override
              public boolean apply(PBXBuildPhase input) {
                return input instanceof PBXHeadersBuildPhase;
              }
            }));

    List<Path> headerSymlinkTrees = projectGenerator.getGeneratedHeaderSymlinkTrees();
    assertThat(headerSymlinkTrees, hasSize(2));

    assertEquals(
        "buck-out/gen/foo/lib-public-header-symlink-tree",
        headerSymlinkTrees.get(0).toString());
    assertThatHeaderSymlinkTreeContains(
        Paths.get("buck-out/gen/foo/lib-public-header-symlink-tree"),
        ImmutableMap.of(
            "yet/another/name.h", "HeaderGroup1/bar.h",
            "and/one/more.h", "foo/generated2.h"));

    assertEquals(
        "buck-out/gen/foo/lib-private-header-symlink-tree",
        headerSymlinkTrees.get(1).toString());
    assertThatHeaderSymlinkTreeContains(
        Paths.get("buck-out/gen/foo/lib-private-header-symlink-tree"),
        ImmutableMap.of(
            "any/name.h", "HeaderGroup1/foo.h",
            "different/name.h", "HeaderGroup2/baz.h",
            "one/more/name.h", "foo/generated1.h"));
  }

  @Test
  public void testHeaderSymlinkTreesAreRegeneratedWhenKeyChanges() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .setSrcs(Optional.of(ImmutableList.<SourceWithFlags>of()))
        .setHeaders(
            ImmutableMap.<String, SourcePath>of(
                "key.h", new TestSourcePath("value.h")))
        .setUseBuckHeaderMaps(Optional.of(true))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(node));

    projectGenerator.createXcodeProjects();

    List<Path> headerSymlinkTrees = projectGenerator.getGeneratedHeaderSymlinkTrees();
    assertThat(headerSymlinkTrees, hasSize(2));

    assertEquals(
        "buck-out/gen/foo/lib-private-header-symlink-tree",
        headerSymlinkTrees.get(1).toString());
    assertThatHeaderSymlinkTreeContains(
        Paths.get("buck-out/gen/foo/lib-private-header-symlink-tree"),
        ImmutableMap.of("key.h", "value.h"));

    node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .setSrcs(Optional.of(ImmutableList.<SourceWithFlags>of()))
        .setHeaders(
            ImmutableMap.<String, SourcePath>of(
                "new-key.h", new TestSourcePath("value.h")))
        .setUseBuckHeaderMaps(Optional.of(true))
        .build();

    projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(node));

    projectGenerator.createXcodeProjects();

    headerSymlinkTrees = projectGenerator.getGeneratedHeaderSymlinkTrees();
    assertThat(headerSymlinkTrees, hasSize(2));

    assertEquals(
        "buck-out/gen/foo/lib-private-header-symlink-tree",
        headerSymlinkTrees.get(1).toString());
    assertFalse(
        projectFilesystem.isSymLink(
            Paths.get(
                "buck-out/gen/foo/lib-private-header-symlink-tree/key.h")));
    assertThatHeaderSymlinkTreeContains(
        Paths.get("buck-out/gen/foo/lib-private-header-symlink-tree"),
        ImmutableMap.of("new-key.h", "value.h"));
  }

  @Test
  public void testHeaderSymlinkTreesAreRegeneratedWhenValueChanges() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .setSrcs(Optional.of(ImmutableList.<SourceWithFlags>of()))
        .setHeaders(
            ImmutableMap.<String, SourcePath>of(
                "key.h", new TestSourcePath("value.h")))
        .setUseBuckHeaderMaps(Optional.of(true))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(node));

    projectGenerator.createXcodeProjects();

    List<Path> headerSymlinkTrees = projectGenerator.getGeneratedHeaderSymlinkTrees();
    assertThat(headerSymlinkTrees, hasSize(2));

    assertEquals(
        "buck-out/gen/foo/lib-private-header-symlink-tree",
        headerSymlinkTrees.get(1).toString());
    assertThatHeaderSymlinkTreeContains(
        Paths.get("buck-out/gen/foo/lib-private-header-symlink-tree"),
        ImmutableMap.of("key.h", "value.h"));

    node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .setSrcs(Optional.of(ImmutableList.<SourceWithFlags>of()))
        .setHeaders(
            ImmutableMap.<String, SourcePath>of(
                "key.h", new TestSourcePath("new-value.h")))
        .setUseBuckHeaderMaps(Optional.of(true))
        .build();

    projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(node));

    projectGenerator.createXcodeProjects();

    headerSymlinkTrees = projectGenerator.getGeneratedHeaderSymlinkTrees();
    assertThat(headerSymlinkTrees, hasSize(2));

    assertEquals(
        "buck-out/gen/foo/lib-private-header-symlink-tree",
        headerSymlinkTrees.get(1).toString());
    assertThatHeaderSymlinkTreeContains(
        Paths.get("buck-out/gen/foo/lib-private-header-symlink-tree"),
        ImmutableMap.of("key.h", "new-value.h"));
  }

  @Test
  public void testHeaderSymlinkTreesWithHeadersVisibleForTesting() throws IOException {
    BuildTarget libraryTarget = BuildTarget.builder("//foo", "lib").build();
    BuildTarget testTarget = BuildTarget.builder("//foo", "test").build();

    TargetNode<?> libraryNode = AppleLibraryBuilder
        .createBuilder(libraryTarget)
        .setSrcs(
            Optional.of(
                ImmutableList.of(
                    SourceWithFlags.of(
                        new TestSourcePath("foo.h"),
                        ImmutableList.of("public")),
                    SourceWithFlags.of(
                        new TestSourcePath("bar.h")))))
        .setTests(Optional.of(ImmutableSortedSet.of(testTarget)))
        .setUseBuckHeaderMaps(Optional.of(true))
        .build();

    TargetNode<?> testNode = AppleTestBuilder
        .createBuilder(testTarget)
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Default",
                    ImmutableMap.<String, String>of())))
        .setUseBuckHeaderMaps(Optional.of(true))
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setDeps(Optional.of(ImmutableSortedSet.of(libraryTarget)))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(libraryNode, testNode));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXTarget testPBXTarget = assertTargetExistsAndReturnTarget(project, "//foo:test");

    ImmutableMap<String, String> buildSettings =
        getBuildSettings(testTarget, testPBXTarget, "Default");

    assertEquals(
        "test binary should use header symlink trees for both public and non-public headers " +
            "of the tested library in HEADER_SEARCH_PATHS",
        "$(inherited) " +
            "../buck-out/gen/foo/test-private-header-symlink-tree/.tree.hmap " +
            "../buck-out/gen/foo/test-public-header-symlink-tree/.tree.hmap " +
            "../buck-out/gen/foo/lib-public-header-symlink-tree/.tree.hmap " +
            "../buck-out/gen/foo/lib-private-header-symlink-tree/.tree.hmap",
        buildSettings.get("HEADER_SEARCH_PATHS"));
    assertEquals(
        "USER_HEADER_SEARCH_PATHS should not be set",
        null,
        buildSettings.get("USER_HEADER_SEARCH_PATHS"));
  }

  @Test
  public void testHeaderSymlinkTreesWithTestsAndLibraryBundles() throws IOException {
    BuildTarget libraryTarget = BuildTarget.builder("//foo", "lib").build();
    BuildTarget bundleTarget = BuildTarget.builder("//foo", "bundle").build();
    BuildTarget testTarget = BuildTarget.builder("//foo", "test").build();

    TargetNode<?> libraryNode = AppleLibraryBuilder
        .createBuilder(libraryTarget)
        .setSrcs(
            Optional.of(
                ImmutableList.of(
                    SourceWithFlags.of(
                        new TestSourcePath("foo.h"),
                        ImmutableList.of("public")),
                    SourceWithFlags.of(
                        new TestSourcePath("bar.h")))))
        .setUseBuckHeaderMaps(Optional.of(true))
        .build();

    TargetNode<?> bundleNode = AppleBundleBuilder
        .createBuilder(bundleTarget)
        .setBinary(libraryTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.FRAMEWORK))
        .setTests(Optional.of(ImmutableSortedSet.of(testTarget)))
        .build();

    TargetNode<?> testNode = AppleTestBuilder
        .createBuilder(testTarget)
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Default",
                    ImmutableMap.<String, String>of())))
        .setUseBuckHeaderMaps(Optional.of(true))
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setDeps(Optional.of(ImmutableSortedSet.of(bundleTarget)))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(libraryNode, bundleNode, testNode));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXTarget testPBXTarget = assertTargetExistsAndReturnTarget(project, "//foo:test");

    ImmutableMap<String, String> buildSettings =
        getBuildSettings(testTarget, testPBXTarget, "Default");

    assertEquals(
        "test binary should use header symlink trees for both public and non-public headers " +
            "of the tested library in HEADER_SEARCH_PATHS",
        "$(inherited) " +
            "../buck-out/gen/foo/test-private-header-symlink-tree/.tree.hmap " +
            "../buck-out/gen/foo/test-public-header-symlink-tree/.tree.hmap " +
            "../buck-out/gen/foo/lib-public-header-symlink-tree/.tree.hmap " +
            "../buck-out/gen/foo/lib-private-header-symlink-tree/.tree.hmap",
        buildSettings.get("HEADER_SEARCH_PATHS"));
    assertEquals(
        "USER_HEADER_SEARCH_PATHS should not be set",
        null,
        buildSettings.get("USER_HEADER_SEARCH_PATHS"));
  }

  @Test
  public void testHeaderSymlinkTreesWithTestsAndBinaryBundles() throws IOException {
    BuildTarget binaryTarget = BuildTarget.builder("//foo", "bin").build();
    BuildTarget bundleTarget = BuildTarget.builder("//foo", "bundle").build();
    BuildTarget testTarget = BuildTarget.builder("//foo", "test").build();

    TargetNode<?> binaryNode = AppleBinaryBuilder
        .createBuilder(binaryTarget)
        .setSrcs(
            Optional.of(
                ImmutableList.of(
                    SourceWithFlags.of(
                        new TestSourcePath("foo.h"),
                        ImmutableList.of("public")),
                    SourceWithFlags.of(
                        new TestSourcePath("bar.h")))))
        .setUseBuckHeaderMaps(Optional.of(true))
        .build();

    TargetNode<?> bundleNode = AppleBundleBuilder
        .createBuilder(bundleTarget)
        .setBinary(binaryTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.APP))
        .setTests(Optional.of(ImmutableSortedSet.of(testTarget)))
        .build();

    TargetNode<?> testNode = AppleTestBuilder
        .createBuilder(testTarget)
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Default",
                    ImmutableMap.<String, String>of())))
        .setUseBuckHeaderMaps(Optional.of(true))
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setDeps(Optional.of(ImmutableSortedSet.of(bundleTarget)))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(binaryNode, bundleNode, testNode));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXTarget testPBXTarget = assertTargetExistsAndReturnTarget(project, "//foo:test");

    ImmutableMap<String, String> buildSettings =
        getBuildSettings(testTarget, testPBXTarget, "Default");

    assertEquals(
        "test binary should use header symlink trees for both public and non-public headers " +
            "of the tested binary in HEADER_SEARCH_PATHS",
        "$(inherited) " +
            "../buck-out/gen/foo/test-private-header-symlink-tree/.tree.hmap " +
            "../buck-out/gen/foo/test-public-header-symlink-tree/.tree.hmap " +
            "../buck-out/gen/foo/bin-public-header-symlink-tree/.tree.hmap " +
            "../buck-out/gen/foo/bin-private-header-symlink-tree/.tree.hmap",
        buildSettings.get("HEADER_SEARCH_PATHS"));
    assertEquals(
        "USER_HEADER_SEARCH_PATHS should not be set",
        null,
        buildSettings.get("USER_HEADER_SEARCH_PATHS"));
  }

  private void assertThatHeaderSymlinkTreeContains(Path root, ImmutableMap<String, String> content)
      throws IOException {
    for (Map.Entry<String, String> entry : content.entrySet()) {
      Path link = root.resolve(Paths.get(entry.getKey()));
      Path target = Paths.get(entry.getValue()).toAbsolutePath();
      assertTrue(projectFilesystem.isSymLink(link));
      assertEquals(
          target,
          projectFilesystem.readSymLink(link));
    }

    // Check the tree's header map.
    byte[] headerMapBytes;
    try (InputStream headerMapInputStream =
             projectFilesystem.newFileInputStream(root.resolve(".tree.hmap"))) {
      headerMapBytes = ByteStreams.toByteArray(headerMapInputStream);
    }
    HeaderMap headerMap = HeaderMap.deserialize(headerMapBytes);
    assertNotNull(headerMap);
    assertThat(headerMap.getNumEntries(), equalTo(content.size()));
    for (String key : content.keySet()) {
      assertThat(
          headerMap.lookup(key),
          equalTo(projectFilesystem.resolve(root).resolve(key).toString()));
    }
  }

  @Test
  public void testAppleLibraryRule() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    ImmutableMap.<String, String>of())))
        .setSrcs(
            Optional.of(
                ImmutableList.of(
                    SourceWithFlags.of(
                        new TestSourcePath("foo.m"), ImmutableList.of("-foo")),
                    SourceWithFlags.of(new TestSourcePath("bar.m")))))
        .setExtraXcodeSources(
            Optional.of(
                ImmutableList.<SourcePath>of(
                    new TestSourcePath("libsomething.a"))))
        .setHeaders(
            ImmutableSortedSet.<SourcePath>of(new TestSourcePath("foo.h")))
        .setUseBuckHeaderMaps(Optional.of(false))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(node));

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:lib");
    assertThat(target.isa(), equalTo("PBXNativeTarget"));
    assertThat(target.getProductType(), equalTo(ProductType.STATIC_LIBRARY));

    assertHasConfigurations(target, "Debug");
    assertEquals("Should have exact number of build phases", 2, target.getBuildPhases().size());
    assertHasSingletonSourcesPhaseWithSourcesAndFlags(
        target, ImmutableMap.of(
            "foo.m", Optional.of("-foo"),
            "bar.m", Optional.<String>absent(),
            "libsomething.a", Optional.<String>absent()));

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
  public void testAppleLibraryConfiguresOutputPaths() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    ImmutableMap.<String, String>of())))
        .setHeaderPathPrefix(Optional.of("MyHeaderPathPrefix"))
        .setPrefixHeader(Optional.<SourcePath>of(new TestSourcePath("Foo/Foo-Prefix.pch")))
        .setUseBuckHeaderMaps(Optional.of(false))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(node),
        ImmutableSet.<ProjectGenerator.Option>of());

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:lib");
    assertThat(target.isa(), equalTo("PBXNativeTarget"));
    assertThat(target.getProductType(), equalTo(ProductType.STATIC_LIBRARY));

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals(
        "../Foo/Foo-Prefix.pch",
        settings.get("GCC_PREFIX_HEADER"));
    assertEquals(
        "$SYMROOT/$CONFIGURATION$EFFECTIVE_PLATFORM_NAME",
        settings.get("BUILT_PRODUCTS_DIR"));
    assertEquals(
        "$BUILT_PRODUCTS_DIR",
        settings.get("CONFIGURATION_BUILD_DIR"));
    assertEquals(
        "F4XWM33PHJWGSYQ/Headers/MyHeaderPathPrefix",
        settings.get("PUBLIC_HEADERS_FOLDER_PATH"));
  }

  @Test
  public void testAppleFrameworkConfiguresPublicHeaderPaths() throws IOException {
    BuildTarget libTarget = BuildTarget.builder("//foo", "lib")
        .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
        .build();
    TargetNode<?> libNode = AppleLibraryBuilder
        .createBuilder(libTarget)
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    ImmutableMap.<String, String>of())))
        .setHeaderPathPrefix(Optional.of("MyHeaderPathPrefix"))
        .setUseBuckHeaderMaps(Optional.of(false))
        .build();

    BuildTarget frameworkTarget = BuildTarget.builder("//foo", "bundle").build();
    TargetNode<?> frameworkNode = AppleBundleBuilder
        .createBuilder(frameworkTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.FRAMEWORK))
        .setBinary(libTarget)
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(libNode, frameworkNode),
        ImmutableSet.<ProjectGenerator.Option>of());

    projectGenerator.createXcodeProjects();

    PBXTarget frameworkPbxTarget = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:bundle");
    assertEquals(frameworkPbxTarget.getProductType(), ProductType.FRAMEWORK);
    assertThat(frameworkPbxTarget.isa(), equalTo("PBXNativeTarget"));
    PBXFileReference frameworkProductReference = frameworkPbxTarget.getProductReference();
    assertEquals("bundle.framework", frameworkProductReference.getName());
    assertEquals(Optional.of("wrapper.framework"), frameworkProductReference.getExplicitFileType());

    ImmutableMap<String, String> settings = getBuildSettings(
        frameworkTarget, frameworkPbxTarget, "Debug");
    assertEquals("framework", settings.get("WRAPPER_EXTENSION"));
    assertEquals(
        "$SYMROOT/$CONFIGURATION$EFFECTIVE_PLATFORM_NAME",
        settings.get("BUILT_PRODUCTS_DIR"));
    assertEquals(
        "$BUILT_PRODUCTS_DIR",
        settings.get("CONFIGURATION_BUILD_DIR"));
    assertEquals(
        "F4XWM33PHJRHK3TENRSQ/Headers/MyHeaderPathPrefix",
        settings.get("PUBLIC_HEADERS_FOLDER_PATH"));
  }

  @Test
  public void testAppleLibraryConfiguresSharedLibraryOutputPaths() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder("//hi", "lib")
        .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
        .build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    ImmutableMap.<String, String>of())))
        .setHeaderPathPrefix(Optional.of("MyHeaderPathPrefix"))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(node),
        ImmutableSet.<ProjectGenerator.Option>of());

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//hi:lib#shared");
    assertThat(target.isa(), equalTo("PBXNativeTarget"));
    assertThat(target.getProductType(), equalTo(ProductType.DYNAMIC_LIBRARY));

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals(
        "$SYMROOT/$CONFIGURATION$EFFECTIVE_PLATFORM_NAME",
        settings.get("BUILT_PRODUCTS_DIR"));
    assertEquals(
        "$BUILT_PRODUCTS_DIR",
        settings.get("CONFIGURATION_BUILD_DIR"));
    assertEquals(
        "F4XWQ2J2NRUWEI3TNBQXEZLE/Headers/MyHeaderPathPrefix",
        settings.get("PUBLIC_HEADERS_FOLDER_PATH"));
  }

  @Test
  public void testAppleLibraryDoesntOverrideHeaderOutputPath() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    ImmutableMap.of("PUBLIC_HEADERS_FOLDER_PATH", "FooHeaders"))))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(node),
        ImmutableSet.<ProjectGenerator.Option>of());

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:lib");
    assertThat(target.isa(), equalTo("PBXNativeTarget"));
    assertThat(target.getProductType(), equalTo(ProductType.STATIC_LIBRARY));

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals(
        "$SYMROOT/$CONFIGURATION$EFFECTIVE_PLATFORM_NAME",
        settings.get("BUILT_PRODUCTS_DIR"));
    assertEquals(
        "$BUILT_PRODUCTS_DIR",
        settings.get("CONFIGURATION_BUILD_DIR"));
    assertEquals(
        "FooHeaders",
        settings.get("PUBLIC_HEADERS_FOLDER_PATH"));
  }

  @Test
  public void testAppleLibraryCompilerAndPreprocessorFlags() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    ImmutableMap.<String, String>of())))
        .setCompilerFlags(Optional.of(ImmutableList.of("-fhello")))
        .setPreprocessorFlags(Optional.of(ImmutableList.of("-fworld")))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(node),
        ImmutableSet.<ProjectGenerator.Option>of());

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:lib");

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals("$(inherited) -fhello -fworld", settings.get("OTHER_CFLAGS"));
  }

  @Test
  public void testAppleLibraryCompilerAndPreprocessorFlagsDontPropagate() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    ImmutableMap.<String, String>of())))
        .setCompilerFlags(Optional.of(ImmutableList.of("-fhello")))
        .setPreprocessorFlags(Optional.of(ImmutableList.of("-fworld")))
        .build();

    BuildTarget dependentBuildTarget = BuildTarget.builder("//foo", "bin").build();
    TargetNode<?> dependentNode = AppleBinaryBuilder
        .createBuilder(dependentBuildTarget)
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    ImmutableMap.<String, String>of())))
        .setDeps(Optional.of(ImmutableSortedSet.of(buildTarget)))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(node, dependentNode),
        ImmutableSet.<ProjectGenerator.Option>of());

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:bin");

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals("$(inherited) ", settings.get("OTHER_CFLAGS"));
  }

  @Test
  public void testAppleLibraryExportedPreprocessorFlags() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    ImmutableMap.<String, String>of())))
        .setExportedPreprocessorFlags(Optional.of(ImmutableList.of("-DHELLO")))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(node),
        ImmutableSet.<ProjectGenerator.Option>of());

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:lib");

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals("$(inherited) -DHELLO", settings.get("OTHER_CFLAGS"));
  }

  @Test
  public void testAppleLibraryExportedPreprocessorFlagsPropagate() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    ImmutableMap.<String, String>of())))
        .setExportedPreprocessorFlags(Optional.of(ImmutableList.of("-DHELLO")))
        .build();

    BuildTarget dependentBuildTarget = BuildTarget.builder("//foo", "bin").build();
    TargetNode<?> dependentNode = AppleBinaryBuilder
        .createBuilder(dependentBuildTarget)
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    ImmutableMap.<String, String>of())))
        .setDeps(Optional.of(ImmutableSortedSet.of(buildTarget)))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(node, dependentNode),
        ImmutableSet.<ProjectGenerator.Option>of());

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:bin");

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals("$(inherited) -DHELLO", settings.get("OTHER_CFLAGS"));
  }

  @Test
  public void testAppleLibraryLinkerFlags() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    ImmutableMap.<String, String>of())))
        .setLinkerFlags(Optional.of(ImmutableList.of("-lhello")))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(node),
        ImmutableSet.<ProjectGenerator.Option>of());

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:lib");

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals("$(inherited) -Xlinker -lhello", settings.get("OTHER_LDFLAGS"));
  }

  @Test
  public void testAppleLibraryLinkerFlagsDontPropagate() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    ImmutableMap.<String, String>of())))
        .setLinkerFlags(Optional.of(ImmutableList.of("-lhello")))
        .build();

    BuildTarget dependentBuildTarget = BuildTarget.builder("//foo", "bin").build();
    TargetNode<?> dependentNode = AppleBinaryBuilder
        .createBuilder(dependentBuildTarget)
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    ImmutableMap.<String, String>of())))
        .setDeps(Optional.of(ImmutableSortedSet.of(buildTarget)))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(node, dependentNode),
        ImmutableSet.<ProjectGenerator.Option>of());

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:bin");

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals("$(inherited) ", settings.get("OTHER_LDFLAGS"));
  }

  @Test
  public void testAppleLibraryExportedLinkerFlags() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    ImmutableMap.<String, String>of())))
        .setExportedLinkerFlags(Optional.of(ImmutableList.of("-lhello")))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(node),
        ImmutableSet.<ProjectGenerator.Option>of());

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:lib");

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals("$(inherited) -Xlinker -lhello", settings.get("OTHER_LDFLAGS"));
  }

  @Test
  public void testAppleLibraryExportedLinkerFlagsPropagate() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    ImmutableMap.<String, String>of())))
        .setExportedLinkerFlags(Optional.of(ImmutableList.of("-lhello")))
        .build();

    BuildTarget dependentBuildTarget = BuildTarget.builder("//foo", "bin").build();
    TargetNode<?> dependentNode = AppleBinaryBuilder
        .createBuilder(dependentBuildTarget)
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    ImmutableMap.<String, String>of())))
        .setDeps(Optional.of(ImmutableSortedSet.of(buildTarget)))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(node, dependentNode),
        ImmutableSet.<ProjectGenerator.Option>of());

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:bin");

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals("$(inherited) -Xlinker -lhello", settings.get("OTHER_LDFLAGS"));
  }

  @Test
  public void testConfigurationSerializationWithoutExistingXcconfig() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    ImmutableMap.of("CUSTOM_SETTING", "VALUE"))))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(node),
        ImmutableSet.<ProjectGenerator.Option>of());

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:lib");
    assertThat(target.isa(), equalTo("PBXNativeTarget"));
    assertThat(target.getProductType(), equalTo(ProductType.STATIC_LIBRARY));

    assertHasConfigurations(target, "Debug");
    XCBuildConfiguration configuration = target
        .getBuildConfigurationList().getBuildConfigurationsByName().asMap().get("Debug");
    assertEquals(configuration.getBuildSettings().count(), 0);

    PBXFileReference xcconfigReference = configuration.getBaseConfigurationReference();
    assertEquals(xcconfigReference.getPath(), "../buck-out/gen/foo/lib-Debug.xcconfig");

    ImmutableMap<String, String> settings = getBuildSettings(
        buildTarget, target, "Debug");
    assertEquals(
        "$SYMROOT/$CONFIGURATION$EFFECTIVE_PLATFORM_NAME",
        settings.get("BUILT_PRODUCTS_DIR"));
    assertEquals(
        "$BUILT_PRODUCTS_DIR",
        settings.get("CONFIGURATION_BUILD_DIR"));
    assertEquals(
        "VALUE",
        settings.get("CUSTOM_SETTING"));
  }

  @Test
  public void testAppleLibraryDependentsSearchHeadersAndLibraries() throws IOException {
    ImmutableSortedMap<String, ImmutableMap<String, String>> configs =
        ImmutableSortedMap.of(
            "Debug", ImmutableMap.<String, String>of());

    BuildTarget libraryTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> libraryNode = AppleLibraryBuilder
        .createBuilder(libraryTarget)
        .setConfigs(Optional.of(configs))
        .setUseBuckHeaderMaps(Optional.of(false))
        .setSrcs(
            Optional.of(ImmutableList.of(SourceWithFlags.of(new TestSourcePath("foo.m")))))
        .setFrameworks(
            Optional.of(
                ImmutableSortedSet.of(
                    FrameworkPath.ofSourceTreePath(
                        new SourceTreePath(
                            PBXReference.SourceTree.SDKROOT,
                            Paths.get("Library.framework"))))))
        .build();

    BuildTarget testTarget = BuildTarget.builder("//foo", "xctest").build();
    TargetNode<?> testNode = AppleTestBuilder
        .createBuilder(testTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setConfigs(Optional.of(configs))
        .setUseBuckHeaderMaps(Optional.of(false))
        .setSrcs(
            Optional.of(
                ImmutableList.of(SourceWithFlags.of(new TestSourcePath("fooTest.m")))))
        .setFrameworks(
            Optional.of(
                ImmutableSortedSet.of(
                    FrameworkPath.ofSourceTreePath(
                        new SourceTreePath(
                            PBXReference.SourceTree.SDKROOT,
                            Paths.get("Test.framework"))))))
        .setDeps(Optional.of(ImmutableSortedSet.of(libraryTarget)))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(libraryNode, testNode),
        ImmutableSet.<ProjectGenerator.Option>of());

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:xctest");

    ImmutableMap<String, String> settings = getBuildSettings(testTarget, target, "Debug");
    assertEquals(
        "$(inherited) $BUILT_PRODUCTS_DIR/F4XWM33PHJWGSYQ/Headers",
        settings.get("HEADER_SEARCH_PATHS"));
    assertEquals(
        null,
        settings.get("USER_HEADER_SEARCH_PATHS"));
    assertEquals(
        "$(inherited) $BUILT_PRODUCTS_DIR",
        settings.get("LIBRARY_SEARCH_PATHS"));
    assertEquals(
        "$(inherited) $BUILT_PRODUCTS_DIR $SDKROOT",
        settings.get("FRAMEWORK_SEARCH_PATHS"));
  }

  @Test
  public void testAppleLibraryDependentsInheritSearchPaths() throws IOException {
    ImmutableSortedMap<String, ImmutableMap<String, String>> configs = ImmutableSortedMap.of(
        "Debug",
        ImmutableMap.of(
            "HEADER_SEARCH_PATHS", "headers",
            "USER_HEADER_SEARCH_PATHS", "user_headers",
            "LIBRARY_SEARCH_PATHS", "libraries",
            "FRAMEWORK_SEARCH_PATHS", "frameworks"));

    BuildTarget libraryTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> libraryNode = AppleLibraryBuilder
        .createBuilder(libraryTarget)
        .setConfigs(Optional.of(configs))
        .setUseBuckHeaderMaps(Optional.of(false))
        .setSrcs(
            Optional.of(ImmutableList.of(SourceWithFlags.of(new TestSourcePath("foo.m")))))
        .setFrameworks(
            Optional.of(
                ImmutableSortedSet.of(
                    FrameworkPath.ofSourceTreePath(
                        new SourceTreePath(
                            PBXReference.SourceTree.SDKROOT,
                            Paths.get("Library.framework"))))))
        .build();

    BuildTarget testTarget = BuildTarget.builder("//foo", "xctest").build();
    TargetNode<?> testNode = AppleTestBuilder
        .createBuilder(testTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setConfigs(Optional.of(configs))
        .setUseBuckHeaderMaps(Optional.of(false))
        .setSrcs(
            Optional.of(
                ImmutableList.of(SourceWithFlags.of(new TestSourcePath("fooTest.m")))))
        .setFrameworks(
            Optional.of(
                ImmutableSortedSet.of(
                    FrameworkPath.ofSourceTreePath(
                        new SourceTreePath(
                            PBXReference.SourceTree.SDKROOT,
                            Paths.get("Test.framework"))))))
        .setDeps(Optional.of(ImmutableSortedSet.of(libraryTarget)))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(libraryNode, testNode),
        ImmutableSet.<ProjectGenerator.Option>of());

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:xctest");

    ImmutableMap<String, String> settings = getBuildSettings(testTarget, target, "Debug");
    assertEquals(
        "headers $BUILT_PRODUCTS_DIR/F4XWM33PHJWGSYQ/Headers",
        settings.get("HEADER_SEARCH_PATHS"));
    assertEquals(
        "user_headers",
        settings.get("USER_HEADER_SEARCH_PATHS"));
    assertEquals(
        "libraries $BUILT_PRODUCTS_DIR",
        settings.get("LIBRARY_SEARCH_PATHS"));
    assertEquals(
        "frameworks $BUILT_PRODUCTS_DIR $SDKROOT",
        settings.get("FRAMEWORK_SEARCH_PATHS"));
  }

  @Test
  public void testAppleLibraryTransitiveDependentsSearchHeadersAndLibraries() throws IOException {
    ImmutableSortedMap<String, ImmutableMap<String, String>> configs = ImmutableSortedMap.of(
        "Debug", ImmutableMap.<String, String>of());

    BuildTarget libraryDepTarget = BuildTarget.builder("//bar", "lib").build();
    TargetNode<?> libraryDepNode = AppleLibraryBuilder
        .createBuilder(libraryDepTarget)
        .setUseBuckHeaderMaps(Optional.of(false))
        .setConfigs(Optional.of(configs))
        .setSrcs(
            Optional.of(ImmutableList.of(SourceWithFlags.of(new TestSourcePath("foo.m")))))
        .setFrameworks(
            Optional.of(
                ImmutableSortedSet.of(
                    FrameworkPath.ofSourceTreePath(
                        new SourceTreePath(
                            PBXReference.SourceTree.SDKROOT,
                            Paths.get("Library.framework"))))))
        .build();

    BuildTarget libraryTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> libraryNode = AppleLibraryBuilder
        .createBuilder(libraryTarget)
        .setConfigs(Optional.of(configs))
        .setSrcs(
            Optional.of(ImmutableList.of(SourceWithFlags.of(new TestSourcePath("foo.m")))))
        .setFrameworks(
            Optional.of(
                ImmutableSortedSet.of(
                    FrameworkPath.ofSourceTreePath(
                        new SourceTreePath(
                            PBXReference.SourceTree.SDKROOT,
                            Paths.get("Library.framework"))))))
        .setDeps(Optional.of(ImmutableSortedSet.of(libraryDepTarget)))
        .setUseBuckHeaderMaps(Optional.of(false))
        .build();

    BuildTarget testTarget = BuildTarget.builder("//foo", "xctest").build();
    TargetNode<?> testNode = AppleTestBuilder
        .createBuilder(testTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setConfigs(Optional.of(configs))
        .setSrcs(
            Optional.of(
                ImmutableList.of(SourceWithFlags.of(new TestSourcePath("fooTest.m")))))
        .setFrameworks(
            Optional.of(
                ImmutableSortedSet.of(
                    FrameworkPath.ofSourceTreePath(
                        new SourceTreePath(
                            PBXReference.SourceTree.SDKROOT,
                            Paths.get("Test.framework"))))))
        .setDeps(Optional.of(ImmutableSortedSet.of(libraryTarget)))
        .setUseBuckHeaderMaps(Optional.of(false))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(libraryDepNode, libraryNode, testNode),
        ImmutableSet.<ProjectGenerator.Option>of());

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:xctest");

    ImmutableMap<String, String> settings = getBuildSettings(testTarget, target, "Debug");
    assertEquals(
        "$(inherited) " +
            "$BUILT_PRODUCTS_DIR/F4XWEYLSHJWGSYQ/Headers " +
            "$BUILT_PRODUCTS_DIR/F4XWM33PHJWGSYQ/Headers",
        settings.get("HEADER_SEARCH_PATHS"));
    assertEquals(
        null,
        settings.get("USER_HEADER_SEARCH_PATHS"));
    assertEquals(
        "$(inherited) " +
            "$BUILT_PRODUCTS_DIR",
        settings.get("LIBRARY_SEARCH_PATHS"));
    assertEquals(
        "$(inherited) " +
            "$BUILT_PRODUCTS_DIR $SDKROOT",
        settings.get("FRAMEWORK_SEARCH_PATHS"));
  }

  @Test
  public void testAppleLibraryWithoutSources() throws IOException {
    ImmutableSortedMap<String, ImmutableMap<String, String>> configs = ImmutableSortedMap.of(
        "Debug",
        ImmutableMap.of(
            "HEADER_SEARCH_PATHS", "headers",
            "LIBRARY_SEARCH_PATHS", "libraries"));

    BuildTarget libraryTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> libraryNode = AppleLibraryBuilder
        .createBuilder(libraryTarget)
        .setConfigs(Optional.of(configs))
        .setUseBuckHeaderMaps(Optional.of(false))
        .setFrameworks(
            Optional.of(
                ImmutableSortedSet.of(
                    FrameworkPath.ofSourceTreePath(
                        new SourceTreePath(
                            PBXReference.SourceTree.SDKROOT,
                            Paths.get("Library.framework"))))))
        .build();

    BuildTarget testTarget = BuildTarget.builder("//foo", "xctest").build();
    TargetNode<?> testNode = AppleTestBuilder
        .createBuilder(testTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setConfigs(Optional.of(configs))
        .setUseBuckHeaderMaps(Optional.of(false))
        .setSrcs(
            Optional.of(
                ImmutableList.of(SourceWithFlags.of(new TestSourcePath("fooTest.m")))))
        .setDeps(Optional.of(ImmutableSortedSet.of(libraryTarget)))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(libraryNode, testNode),
        ImmutableSet.<ProjectGenerator.Option>of());

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:xctest");

    ImmutableMap<String, String> settings = getBuildSettings(testTarget, target, "Debug");
    assertEquals(
        "headers $BUILT_PRODUCTS_DIR/F4XWM33PHJWGSYQ/Headers",
        settings.get("HEADER_SEARCH_PATHS"));
    assertEquals(
        "libraries $BUILT_PRODUCTS_DIR",
        settings.get("LIBRARY_SEARCH_PATHS"));

    assertEquals("Should have exact number of build phases", 2, target.getBuildPhases().size());

    assertHasSingletonSourcesPhaseWithSourcesAndFlags(
        target,
        ImmutableMap.of("fooTest.m", Optional.<String>absent()));

    assertHasSingletonFrameworksPhaseWithFrameworkEntries(
        target,
        ImmutableList.of("$SDKROOT/Library.framework"));
  }

  @Test
  public void testAppleLibraryWithoutSourcesWithHeaders() throws IOException {
    ImmutableSortedMap<String, ImmutableMap<String, String>> configs = ImmutableSortedMap.of(
        "Debug",
        ImmutableMap.of(
            "HEADER_SEARCH_PATHS", "headers",
            "LIBRARY_SEARCH_PATHS", "libraries"));

    BuildTarget libraryTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> libraryNode = AppleLibraryBuilder
        .createBuilder(libraryTarget)
        .setConfigs(Optional.of(configs))
        .setUseBuckHeaderMaps(Optional.of(false))
        .setExportedHeaders(
            ImmutableSortedSet.<SourcePath>of(
                new TestSourcePath("HeaderGroup1/bar.h")))
        .setFrameworks(
            Optional.of(
                ImmutableSortedSet.of(
                    FrameworkPath.ofSourceTreePath(
                        new SourceTreePath(
                            PBXReference.SourceTree.SDKROOT,
                            Paths.get("Library.framework"))))))
        .build();

    BuildTarget testTarget = BuildTarget.builder("//foo", "xctest").build();
    TargetNode<?> testNode = AppleTestBuilder
        .createBuilder(testTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setConfigs(Optional.of(configs))
        .setUseBuckHeaderMaps(Optional.of(false))
        .setSrcs(
            Optional.of(
                ImmutableList.of(SourceWithFlags.of(new TestSourcePath("fooTest.m")))))
        .setDeps(Optional.of(ImmutableSortedSet.of(libraryTarget)))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(libraryNode, testNode),
        ImmutableSet.<ProjectGenerator.Option>of());

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:xctest");

    ImmutableMap<String, String> settings = getBuildSettings(testTarget, target, "Debug");
    assertEquals(
        "headers $BUILT_PRODUCTS_DIR/F4XWM33PHJWGSYQ/Headers",
        settings.get("HEADER_SEARCH_PATHS"));
    assertEquals(
        "libraries $BUILT_PRODUCTS_DIR",
        settings.get("LIBRARY_SEARCH_PATHS"));

    assertEquals("Should have exact number of build phases", 2, target.getBuildPhases().size());

    assertHasSingletonSourcesPhaseWithSourcesAndFlags(
        target,
        ImmutableMap.of("fooTest.m", Optional.<String>absent()));

    assertHasSingletonFrameworksPhaseWithFrameworkEntries(
        target,
        ImmutableList.of("$SDKROOT/Library.framework"));
  }

  @Test
  public void testAppleTestRule() throws IOException {
    BuildTarget testTarget = BuildTarget.builder("//foo", "xctest").build();
    TargetNode<?> testNode = AppleTestBuilder
        .createBuilder(testTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setUseBuckHeaderMaps(Optional.of(false))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(testNode));
    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:xctest");
    assertEquals(target.getProductType(), ProductType.UNIT_TEST);
    assertThat(target.isa(), equalTo("PBXNativeTarget"));
    PBXFileReference productReference = target.getProductReference();
    assertEquals("xctest.xctest", productReference.getName());
  }

  @Test
  public void testAppleBinaryRule() throws IOException {
    BuildTarget depTarget = BuildTarget.builder("//dep", "dep").build();
    TargetNode<?> depNode = AppleLibraryBuilder
        .createBuilder(depTarget)
        .setSrcs(Optional.of(ImmutableList.of(SourceWithFlags.of(new TestSourcePath("e.m")))))
        .setUseBuckHeaderMaps(Optional.of(false))
        .build();

    BuildTarget binaryTarget = BuildTarget.builder("//foo", "binary").build();
    TargetNode<?> binaryNode = AppleBinaryBuilder
        .createBuilder(binaryTarget)
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    ImmutableMap.<String, String>of())))
        .setSrcs(
            Optional.of(
                ImmutableList.of(
                    SourceWithFlags.of(
                        new TestSourcePath("foo.m"), ImmutableList.of("-foo")))))
        .setExtraXcodeSources(
            Optional.of(
                ImmutableList.<SourcePath>of(
                    new TestSourcePath("libsomething.a"))))
        .setHeaders(
            ImmutableSortedSet.<SourcePath>of(
                new TestSourcePath("foo.h")))
        .setFrameworks(
            Optional.of(
                ImmutableSortedSet.of(
                    FrameworkPath.ofSourceTreePath(
                        new SourceTreePath(
                            PBXReference.SourceTree.SDKROOT,
                            Paths.get("Foo.framework"))))))
        .setDeps(Optional.of(ImmutableSortedSet.of(depTarget)))
        .setGid(Optional.<String>absent())
        .setHeaderPathPrefix(Optional.<String>absent())
        .setUseBuckHeaderMaps(Optional.of(false))
        .setPrefixHeader(Optional.<SourcePath>absent())
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(depNode, binaryNode));
    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:binary");
    assertHasConfigurations(target, "Debug");
    assertEquals(target.getProductType(), ProductType.TOOL);
    assertEquals("Should have exact number of build phases", 3, target.getBuildPhases().size());
    assertHasSingletonSourcesPhaseWithSourcesAndFlags(
        target,
        ImmutableMap.of(
            "foo.m", Optional.of("-foo"),
            "libsomething.a", Optional.<String>absent()));
    assertHasSingletonFrameworksPhaseWithFrameworkEntries(
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
  public void testAppleBundleRuleWithPreBuildScriptDependency() throws IOException {
    BuildTarget scriptTarget = BuildTarget.builder("//foo", "pre_build_script").build();
    TargetNode<?> scriptNode = XcodePrebuildScriptBuilder
        .createBuilder(scriptTarget)
        .setCmd("script.sh")
        .build();

    BuildTarget resourceTarget = BuildTarget.builder("//foo", "resource").build();
    TargetNode<?> resourceNode = AppleResourceBuilder
        .createBuilder(resourceTarget)
        .setFiles(ImmutableSet.<SourcePath>of(new TestSourcePath("bar.png")))
        .setDirs(ImmutableSet.<SourcePath>of())
        .build();

    BuildTarget sharedLibraryTarget = BuildTarget
        .builder("//dep", "shared")
        .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
        .build();
    TargetNode<?> sharedLibraryNode = AppleLibraryBuilder
        .createBuilder(sharedLibraryTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(resourceTarget)))
        .build();

    BuildTarget bundleTarget = BuildTarget.builder("//foo", "bundle").build();
    TargetNode<?> bundleNode = AppleBundleBuilder
        .createBuilder(bundleTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.BUNDLE))
        .setBinary(sharedLibraryTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(scriptTarget)))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(scriptNode, resourceNode, sharedLibraryNode, bundleNode));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXTarget target = assertTargetExistsAndReturnTarget(
        project, "//foo:bundle");
    assertThat(target.getName(), equalTo("//foo:bundle"));
    assertThat(target.isa(), equalTo("PBXNativeTarget"));

    PBXShellScriptBuildPhase shellScriptBuildPhase =
        getSingletonPhaseByType(
            target,
            PBXShellScriptBuildPhase.class);

    assertThat(
        shellScriptBuildPhase.getShellScript(),
        equalTo("script.sh"));

    // Assert that the pre-build script phase comes before resources are copied.
    assertThat(
        target.getBuildPhases().get(0),
        instanceOf(PBXShellScriptBuildPhase.class));

    assertThat(
        target.getBuildPhases().get(1),
        instanceOf(PBXResourcesBuildPhase.class));
  }

  @Test
  public void testAppleBundleRuleWithPostBuildScriptDependency() throws IOException {
    BuildTarget scriptTarget = BuildTarget.builder("//foo", "post_build_script").build();
    TargetNode<?> scriptNode = XcodePostbuildScriptBuilder
        .createBuilder(scriptTarget)
        .setCmd("script.sh")
        .build();

    BuildTarget resourceTarget = BuildTarget.builder("//foo", "resource").build();
    TargetNode<?> resourceNode = AppleResourceBuilder
        .createBuilder(resourceTarget)
        .setFiles(ImmutableSet.<SourcePath>of(new TestSourcePath("bar.png")))
        .setDirs(ImmutableSet.<SourcePath>of())
        .build();

    BuildTarget sharedLibraryTarget = BuildTarget
        .builder("//dep", "shared")
        .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
        .build();
    TargetNode<?> sharedLibraryNode = AppleLibraryBuilder
        .createBuilder(sharedLibraryTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(resourceTarget)))
        .build();

    BuildTarget bundleTarget = BuildTarget.builder("//foo", "bundle").build();
    TargetNode<?> bundleNode = AppleBundleBuilder
        .createBuilder(bundleTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.BUNDLE))
        .setBinary(sharedLibraryTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(scriptTarget)))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(scriptNode, resourceNode, sharedLibraryNode, bundleNode));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXTarget target = assertTargetExistsAndReturnTarget(
        project, "//foo:bundle");
    assertThat(target.getName(), equalTo("//foo:bundle"));
    assertThat(target.isa(), equalTo("PBXNativeTarget"));

    PBXShellScriptBuildPhase shellScriptBuildPhase =
        getSingletonPhaseByType(
            target,
            PBXShellScriptBuildPhase.class);

    assertThat(
        shellScriptBuildPhase.getShellScript(),
        equalTo("script.sh"));

    // Assert that the post-build script phase comes after resources are copied.
    assertThat(
        target.getBuildPhases().get(0),
        instanceOf(PBXResourcesBuildPhase.class));

    assertThat(
        target.getBuildPhases().get(1),
        instanceOf(PBXShellScriptBuildPhase.class));
  }

  @Test
  public void testAppleBundleRuleWithRNLibraryDependency() throws IOException {
    BuildTarget rnLibraryTarget = BuildTarget.builder("//foo", "rn_library").build();
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    ReactNativeBuckConfig buckConfig = new ReactNativeBuckConfig(new FakeBuckConfig(
        ImmutableMap.of("react-native", ImmutableMap.of("packager", "react-native/packager.sh")),
        filesystem));
    TargetNode<?>  rnLibraryNode = IosReactNativeLibraryBuilder
        .builder(rnLibraryTarget, buckConfig)
        .setBundleName("Apps/Foo/FooBundle.js")
        .setEntryPath(new PathSourcePath(filesystem, Paths.get("js/FooApp.js")))
        .build();

    BuildTarget sharedLibraryTarget = BuildTarget
        .builder("//dep", "shared")
        .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
        .build();
    TargetNode<?> sharedLibraryNode = AppleLibraryBuilder
        .createBuilder(sharedLibraryTarget)
        .build();

    BuildTarget bundleTarget = BuildTarget.builder("//foo", "bundle").build();
    TargetNode<?> bundleNode = AppleBundleBuilder
        .createBuilder(bundleTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.BUNDLE))
        .setBinary(sharedLibraryTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(rnLibraryTarget)))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rnLibraryNode, sharedLibraryNode, bundleNode));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXTarget target = assertTargetExistsAndReturnTarget(
        project, "//foo:bundle");
    assertThat(target.getName(), equalTo("//foo:bundle"));
    assertThat(target.isa(), equalTo("PBXNativeTarget"));

    PBXShellScriptBuildPhase shellScriptBuildPhase =
        getSingletonPhaseByType(
            target,
            PBXShellScriptBuildPhase.class);

    assertThat(
        shellScriptBuildPhase.getShellScript(),
        startsWith("BASE_DIR="));
  }

  @Test
  public void testNoBundleFlavoredAppleBundleRuleWithRNLibraryDependency() throws IOException {
    BuildTarget rnLibraryTarget = BuildTarget.builder("//foo", "rn_library").build();
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    ReactNativeBuckConfig buckConfig = new ReactNativeBuckConfig(new FakeBuckConfig(
        ImmutableMap.of("react-native", ImmutableMap.of("packager", "react-native/packager.sh")),
        filesystem));
    TargetNode<?>  rnLibraryNode = IosReactNativeLibraryBuilder
        .builder(rnLibraryTarget, buckConfig)
        .setBundleName("Apps/Foo/FooBundle.js")
        .setEntryPath(new PathSourcePath(filesystem, Paths.get("js/FooApp.js")))
        .build();

    BuildTarget sharedLibraryTarget = BuildTarget
        .builder("//dep", "shared")
        .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
        .build();
    TargetNode<?> sharedLibraryNode = AppleLibraryBuilder
        .createBuilder(sharedLibraryTarget)
        .build();

    BuildTarget bundleTarget = BuildTarget.builder("//foo", "bundle")
        .addFlavors(ReactNativeFlavors.DO_NOT_BUNDLE)
        .build();
    TargetNode<?> bundleNode = AppleBundleBuilder
        .createBuilder(bundleTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.BUNDLE))
        .setBinary(sharedLibraryTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(rnLibraryTarget)))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rnLibraryNode, sharedLibraryNode, bundleNode),
        ImmutableSet.<ProjectGenerator.Option>of(),
        Optional.of(Paths.get("js/react-native/runServer.sh")));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXTarget target = assertTargetExistsAndReturnTarget(
        project, "//foo:bundle#rn_no_bundle");
    assertThat(target.getName(), equalTo("//foo:bundle#rn_no_bundle"));
    assertThat(target.isa(), equalTo("PBXNativeTarget"));

    Iterator<PBXShellScriptBuildPhase> phases = Iterables.filter(
        target.getBuildPhases(),
        PBXShellScriptBuildPhase.class).iterator();

    assertThat(phases.hasNext(), is(true));
    assertThat(
        phases.next().getShellScript(),
        containsString("rm -rf ${JS_OUT}"));

    assertThat(phases.hasNext(), is(true));
    assertThat(
        phases.next().getShellScript(),
        endsWith("js/react-native/runServer.sh"));

    assertThat(phases.hasNext(), is(false));
  }

  @Test
  public void testAppleBundleRuleForSharedLibraryFramework() throws IOException {
    BuildTarget sharedLibraryTarget = BuildTarget
        .builder("//dep", "shared")
        .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
        .build();
    TargetNode<?> sharedLibraryNode = AppleLibraryBuilder
        .createBuilder(sharedLibraryTarget)
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    ImmutableMap.<String, String>of())))
        .build();

    BuildTarget buildTarget = BuildTarget.builder("//foo", "bundle").build();
    TargetNode<?> node = AppleBundleBuilder
        .createBuilder(buildTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.FRAMEWORK))
        .setBinary(sharedLibraryTarget)
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(sharedLibraryNode, node),
        ImmutableSet.<ProjectGenerator.Option>of());
    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXTarget target = assertTargetExistsAndReturnTarget(project, "//foo:bundle");
    assertEquals(target.getProductType(), ProductType.FRAMEWORK);
    assertThat(target.isa(), equalTo("PBXNativeTarget"));
    PBXFileReference productReference = target.getProductReference();
    assertEquals("bundle.framework", productReference.getName());
    assertEquals(Optional.of("wrapper.framework"), productReference.getExplicitFileType());

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals(
        "framework",
        settings.get("WRAPPER_EXTENSION"));
  }

  @Test
  public void testAppleResourceWithVariantGroupSetsFileTypeBasedOnPath() throws IOException {
    BuildTarget resourceTarget = BuildTarget.builder("//foo", "resource").build();
    TargetNode<?> resourceNode = AppleResourceBuilder
        .createBuilder(resourceTarget)
        .setFiles(ImmutableSet.<SourcePath>of())
        .setDirs(ImmutableSet.<SourcePath>of())
        .setVariants(
            Optional.<Set<SourcePath>>of(
                ImmutableSet.<SourcePath>of(
                    new TestSourcePath("Base.lproj/Bar.storyboard"))))
        .build();
    BuildTarget fooLibraryTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> fooLibraryNode = AppleLibraryBuilder
        .createBuilder(fooLibraryTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(resourceTarget)))
        .build();
    BuildTarget bundleTarget = BuildTarget.builder("//foo", "bundle").build();
    TargetNode<?> bundleNode = AppleBundleBuilder
        .createBuilder(bundleTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.BUNDLE))
        .setBinary(fooLibraryTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(resourceTarget)))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(fooLibraryNode, bundleNode, resourceNode),
        ImmutableSet.<ProjectGenerator.Option>of());
    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXGroup targetGroup =
        project.getMainGroup().getOrCreateChildGroupByName(bundleTarget.getFullyQualifiedName());
    PBXGroup resourcesGroup = targetGroup.getOrCreateChildGroupByName("Resources");
    PBXVariantGroup storyboardGroup = (PBXVariantGroup) Iterables.get(
        resourcesGroup.getChildren(),
        0);
    List<PBXReference> storyboardGroupChildren = storyboardGroup.getChildren();
    assertEquals(1, storyboardGroupChildren.size());
    assertTrue(storyboardGroupChildren.get(0) instanceof PBXFileReference);
    PBXFileReference baseStoryboardReference = (PBXFileReference) storyboardGroupChildren.get(0);

    assertEquals("Base", baseStoryboardReference.getName());

    // Make sure the file type is set from the path.
    assertEquals(Optional.of("file.storyboard"), baseStoryboardReference.getLastKnownFileType());
    assertEquals(Optional.<String>absent(), baseStoryboardReference.getExplicitFileType());
  }

  @Test
  public void testAppleBundleRuleWithCustomXcodeProductType() throws IOException {
    BuildTarget sharedLibraryTarget = BuildTarget
        .builder("//dep", "shared")
        .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
        .build();
    TargetNode<?> sharedLibraryNode = AppleLibraryBuilder
        .createBuilder(sharedLibraryTarget)
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    ImmutableMap.<String, String>of())))
        .build();

    BuildTarget buildTarget = BuildTarget.builder("//foo", "custombundle").build();
    TargetNode<?> node = AppleBundleBuilder
        .createBuilder(buildTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.FRAMEWORK))
        .setBinary(sharedLibraryTarget)
        .setXcodeProductType(Optional.of("com.facebook.buck.niftyProductType"))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(sharedLibraryNode, node),
        ImmutableSet.<ProjectGenerator.Option>of());
    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXTarget target = assertTargetExistsAndReturnTarget(project, "//foo:custombundle");
    assertEquals(
        target.getProductType(),
        ProductType.of("com.facebook.buck.niftyProductType"));
    assertThat(target.isa(), equalTo("PBXNativeTarget"));
    PBXFileReference productReference = target.getProductReference();
    assertEquals("custombundle.framework", productReference.getName());
    assertEquals(Optional.of("wrapper.framework"), productReference.getExplicitFileType());

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals(
        "framework",
        settings.get("WRAPPER_EXTENSION"));
  }

  @Test
  public void testCoreDataModelRuleAddsReference() throws IOException {
    BuildTarget modelTarget = BuildTarget.builder("//foo", "model").build();
    TargetNode<?> modelNode = CoreDataModelBuilder
        .createBuilder(modelTarget)
        .setPath(new TestSourcePath("foo.xcdatamodel").getRelativePath())
        .build();

    BuildTarget libraryTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> libraryNode = AppleLibraryBuilder
        .createBuilder(libraryTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(modelTarget)))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(modelNode, libraryNode));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXGroup targetGroup =
        project.getMainGroup().getOrCreateChildGroupByName(libraryTarget.getFullyQualifiedName());
    PBXGroup resourcesGroup = targetGroup.getOrCreateChildGroupByName("Resources");

    assertThat(resourcesGroup.getChildren(), hasSize(1));

    PBXFileReference modelReference = (PBXFileReference) Iterables.get(
        resourcesGroup.getChildren(),
        0);
    assertEquals("foo.xcdatamodel", modelReference.getName());
  }

  @Test
  public void testAppleWatchTarget() throws IOException {
    BuildTarget watchAppBinaryTarget = BuildTarget.builder("//foo", "WatchAppBinary").build();
    TargetNode<?> watchAppBinaryNode = AppleBinaryBuilder
        .createBuilder(watchAppBinaryTarget)
        .build();

    BuildTarget watchAppTarget = BuildTarget.builder("//foo", "WatchApp").build();
    TargetNode<?> watchAppNode = AppleBundleBuilder
        .createBuilder(watchAppTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.APP))
        .setXcodeProductType(Optional.<String>of("com.apple.product-type.application.watchapp2"))
        .setBinary(watchAppBinaryTarget)
        .build();

    BuildTarget hostAppBinaryTarget = BuildTarget.builder("//foo", "HostAppBinary").build();
    TargetNode<?> hostAppBinaryNode = AppleBinaryBuilder
        .createBuilder(hostAppBinaryTarget)
        .build();

    BuildTarget hostAppTarget = BuildTarget.builder("//foo", "HostApp").build();
    TargetNode<?> hostAppNode = AppleBundleBuilder
        .createBuilder(hostAppTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.APP))
        .setBinary(hostAppBinaryTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(watchAppTarget)))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(watchAppNode, watchAppBinaryNode, hostAppNode, hostAppBinaryNode));
    projectGenerator.createXcodeProjects();


    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:HostApp");
    assertEquals(target.getProductType(), ProductType.APPLICATION);

    assertHasSingletonCopyFilesPhaseWithFileEntries(
        target,
        ImmutableList.of("$BUILT_PRODUCTS_DIR/WatchApp.app"));

    PBXCopyFilesBuildPhase copyBuildPhase = getSingletonPhaseByType(
        target,
        PBXCopyFilesBuildPhase.class
    );
    assertEquals(
        copyBuildPhase.getDstSubfolderSpec(),
        CopyFilePhaseDestinationSpec.builder()
            .setDestination(PBXCopyFilesBuildPhase.Destination.PRODUCTS)
            .setPath("$(CONTENTS_FOLDER_PATH)/Watch")
            .build()
    );
  }

  @Test
  public void ruleToTargetMapContainsPBXTarget() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .setSrcs(
            Optional.of(
                ImmutableList.of(
                    SourceWithFlags.of(
                        new TestSourcePath("foo.m"), ImmutableList.of("-foo")),
                    SourceWithFlags.of(new TestSourcePath("bar.m")))))
        .setHeaders(
            ImmutableSortedSet.<SourcePath>of(
                new TestSourcePath("foo.h")))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(node));

    projectGenerator.createXcodeProjects();

    assertEquals(
        buildTarget, Iterables.getOnlyElement(
            projectGenerator.getBuildTargetToGeneratedTargetMap().keySet()));

    PBXTarget target = Iterables.getOnlyElement(
        projectGenerator.getBuildTargetToGeneratedTargetMap().values());
    assertHasSingletonSourcesPhaseWithSourcesAndFlags(
        target, ImmutableMap.of(
            "foo.m", Optional.of("-foo"),
            "bar.m", Optional.<String>absent()));
  }

  @Test
  public void generatedGidsForTargetsAreStable() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder("//foo", "foo").build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(node));
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
  public void stopsLinkingRecursiveDependenciesAtSharedLibraries() throws IOException {
    BuildTarget dependentStaticLibraryTarget = BuildTarget.builder("//dep", "static").build();
    TargetNode<?> dependentStaticLibraryNode = AppleLibraryBuilder
        .createBuilder(dependentStaticLibraryTarget)
        .build();

    BuildTarget dependentSharedLibraryTarget = BuildTarget
        .builder("//dep", "shared")
        .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
        .build();
    TargetNode<?> dependentSharedLibraryNode = AppleLibraryBuilder
        .createBuilder(dependentSharedLibraryTarget)
        .setSrcs(Optional.of(ImmutableList.of(SourceWithFlags.of(new TestSourcePath("empty.m")))))
        .setDeps(Optional.of(ImmutableSortedSet.of(dependentStaticLibraryTarget)))
        .build();

    BuildTarget libraryTarget = BuildTarget
        .builder("//foo", "library")
        .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
        .build();
    TargetNode<?> libraryNode = AppleLibraryBuilder
        .createBuilder(libraryTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(dependentSharedLibraryTarget)))
        .build();

    BuildTarget bundleTarget = BuildTarget.builder("//foo", "final").build();
    TargetNode<?> bundleNode = AppleBundleBuilder
        .createBuilder(bundleTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.BUNDLE))
        .setBinary(libraryTarget)
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(
            dependentStaticLibraryNode,
            dependentSharedLibraryNode,
            libraryNode,
            bundleNode));
    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:final");
    assertEquals(target.getProductType(), ProductType.BUNDLE);
    assertEquals("Should have exact number of build phases ", 2, target.getBuildPhases().size());
    assertHasSingletonFrameworksPhaseWithFrameworkEntries(
        target,
        ImmutableList.of(
            "$BUILT_PRODUCTS_DIR/libshared.dylib"));
  }

  @Test
  public void stopsLinkingRecursiveDependenciesAtBundles() throws IOException {
    BuildTarget dependentStaticLibraryTarget = BuildTarget.builder("//dep", "static").build();
    TargetNode<?> dependentStaticLibraryNode = AppleLibraryBuilder
        .createBuilder(dependentStaticLibraryTarget)
        .build();

    BuildTarget dependentSharedLibraryTarget = BuildTarget
        .builder("//dep", "shared")
        .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
        .build();
    TargetNode<?> dependentSharedLibraryNode = AppleLibraryBuilder
        .createBuilder(dependentSharedLibraryTarget)
        .setSrcs(Optional.of(ImmutableList.of(SourceWithFlags.of(new TestSourcePath("e.m")))))
        .setDeps(Optional.of(ImmutableSortedSet.of(dependentStaticLibraryTarget)))
        .build();

    BuildTarget dependentFrameworkTarget = BuildTarget.builder("//dep", "framework").build();
    TargetNode<?> dependentFrameworkNode = AppleBundleBuilder
        .createBuilder(dependentFrameworkTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.FRAMEWORK))
        .setBinary(dependentSharedLibraryTarget)
        .build();

    BuildTarget libraryTarget = BuildTarget
        .builder("//foo", "library")
        .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
        .build();
    TargetNode<?> libraryNode = AppleLibraryBuilder
        .createBuilder(libraryTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(dependentFrameworkTarget)))
        .build();

    BuildTarget bundleTarget = BuildTarget.builder("//foo", "final").build();
    TargetNode<?> bundleNode = AppleBundleBuilder
        .createBuilder(bundleTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.BUNDLE))
        .setBinary(libraryTarget)
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(
            dependentStaticLibraryNode,
            dependentSharedLibraryNode,
            dependentFrameworkNode,
            libraryNode,
            bundleNode));
    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:final");
    assertEquals(target.getProductType(), ProductType.BUNDLE);
    assertEquals("Should have exact number of build phases ", 2, target.getBuildPhases().size());
    assertHasSingletonFrameworksPhaseWithFrameworkEntries(
        target,
        ImmutableList.of("$BUILT_PRODUCTS_DIR/framework.framework"));
  }

  @Test
  public void stopsCopyingRecursiveDependenciesAtBundles() throws IOException {
    BuildTarget dependentStaticLibraryTarget = BuildTarget.builder("//dep", "static").build();
    TargetNode<?> dependentStaticLibraryNode = AppleLibraryBuilder
        .createBuilder(dependentStaticLibraryTarget)
        .build();

    BuildTarget dependentStaticFrameworkTarget = BuildTarget
        .builder("//dep", "static-framework")
        .build();
    TargetNode<?> dependentStaticFrameworkNode = AppleBundleBuilder
        .createBuilder(dependentStaticFrameworkTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.FRAMEWORK))
        .setBinary(dependentStaticLibraryTarget)
        .build();

    BuildTarget dependentSharedLibraryTarget = BuildTarget
        .builder("//dep", "shared")
        .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
        .build();
    TargetNode<?> dependentSharedLibraryNode = AppleLibraryBuilder
        .createBuilder(dependentSharedLibraryTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(dependentStaticFrameworkTarget)))
        .build();

    BuildTarget dependentFrameworkTarget = BuildTarget.builder("//dep", "framework").build();
    TargetNode<?> dependentFrameworkNode = AppleBundleBuilder
        .createBuilder(dependentFrameworkTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.FRAMEWORK))
        .setBinary(dependentSharedLibraryTarget)
        .build();

    BuildTarget libraryTarget = BuildTarget
        .builder("//foo", "library")
        .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
        .build();
    TargetNode<?> libraryNode = AppleLibraryBuilder
        .createBuilder(libraryTarget)
        .setSrcs(Optional.of(ImmutableList.of(SourceWithFlags.of(new TestSourcePath("e.m")))))
        .setDeps(Optional.of(ImmutableSortedSet.of(dependentFrameworkTarget)))
        .build();

    BuildTarget bundleTarget = BuildTarget.builder("//foo", "final").build();
    TargetNode<?> bundleNode = AppleBundleBuilder
        .createBuilder(bundleTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.BUNDLE))
        .setBinary(libraryTarget)
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        // ant needs this to be explicit
        ImmutableSet.<TargetNode<?>>of(
            dependentStaticLibraryNode,
            dependentStaticFrameworkNode,
            dependentSharedLibraryNode,
            dependentFrameworkNode,
            libraryNode,
            bundleNode));
    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:final");
    assertEquals(target.getProductType(), ProductType.BUNDLE);
    assertEquals("Should have exact number of build phases ", 2, target.getBuildPhases().size());
    assertHasSingletonCopyFilesPhaseWithFileEntries(
        target,
        ImmutableList.of("$BUILT_PRODUCTS_DIR/framework.framework"));
  }

  @Test
  public void bundlesDontLinkTheirOwnBinary() throws IOException {
    BuildTarget libraryTarget = BuildTarget
        .builder("//foo", "library")
        .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
        .build();
    TargetNode<?> libraryNode = AppleLibraryBuilder
        .createBuilder(libraryTarget)
        .build();

    BuildTarget bundleTarget = BuildTarget.builder("//foo", "final").build();
    TargetNode<?> bundleNode = AppleBundleBuilder
        .createBuilder(bundleTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.BUNDLE))
        .setBinary(libraryTarget)
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(libraryNode, bundleNode));
    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:final");
    assertEquals(target.getProductType(), ProductType.BUNDLE);
    assertEquals("Should have exact number of build phases ", 0, target.getBuildPhases().size());
  }

  @Test
  public void resourcesInDependenciesPropagatesToBundles() throws IOException {
    BuildTarget resourceTarget = BuildTarget.builder("//foo", "res").build();
    TargetNode<?> resourceNode = AppleResourceBuilder
        .createBuilder(resourceTarget)
        .setFiles(ImmutableSet.<SourcePath>of(new TestSourcePath("bar.png")))
        .setDirs(ImmutableSet.<SourcePath>of(new TestSourcePath("foodir")))
        .build();

    BuildTarget libraryTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> libraryNode = AppleLibraryBuilder
        .createBuilder(libraryTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(resourceTarget)))
        .build();

    BuildTarget bundleLibraryTarget = BuildTarget.builder("//foo", "bundlelib").build();
    TargetNode<?> bundleLibraryNode = AppleLibraryBuilder
        .createBuilder(bundleLibraryTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(libraryTarget)))
        .build();

    BuildTarget bundleTarget = BuildTarget.builder("//foo", "bundle").build();
    TargetNode<?> bundleNode = AppleBundleBuilder
        .createBuilder(bundleTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.BUNDLE))
        .setBinary(bundleLibraryTarget)
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(resourceNode, libraryNode, bundleLibraryNode, bundleNode));
    projectGenerator.createXcodeProjects();

    PBXProject generatedProject = projectGenerator.getGeneratedProject();
    PBXTarget target = assertTargetExistsAndReturnTarget(
        generatedProject,
        "//foo:bundle");
    assertHasSingletonResourcesPhaseWithEntries(target, "bar.png", "foodir");
  }

  @Test
  public void assetCatalogsInDependenciesPropogatesToBundles() throws IOException {
    BuildTarget assetCatalogTarget = BuildTarget.builder("//foo", "asset_catalog").build();
    TargetNode<?> assetCatalogNode = AppleAssetCatalogBuilder
        .createBuilder(assetCatalogTarget)
        .setDirs(ImmutableSortedSet.of(Paths.get("AssetCatalog.xcassets")))
        .build();

    BuildTarget libraryTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> libraryNode = AppleLibraryBuilder
        .createBuilder(libraryTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(assetCatalogTarget)))
        .build();

    BuildTarget bundleLibraryTarget = BuildTarget.builder("//foo", "bundlelib").build();
    TargetNode<?> bundleLibraryNode = AppleLibraryBuilder
        .createBuilder(bundleLibraryTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(libraryTarget)))
        .build();

    BuildTarget bundleTarget = BuildTarget.builder("//foo", "bundle").build();
    TargetNode<?> bundleNode = AppleBundleBuilder
        .createBuilder(bundleTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.BUNDLE))
        .setBinary(bundleLibraryTarget)
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(assetCatalogNode, libraryNode, bundleLibraryNode, bundleNode));
    projectGenerator.createXcodeProjects();

    PBXProject generatedProject = projectGenerator.getGeneratedProject();
    PBXTarget target = assertTargetExistsAndReturnTarget(
        generatedProject,
        "//foo:bundle");
    assertTrue(hasShellScriptPhaseToCompileAssetCatalogs(target));
  }

  @Test
  public void generatedTargetConfigurationHasRepoRootSet() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder("//foo", "rule").build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    ImmutableMap.<String, String>of())))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(node),
        ImmutableSet.<ProjectGenerator.Option>of());
    projectGenerator.createXcodeProjects();

    PBXProject generatedProject = projectGenerator.getGeneratedProject();
    ImmutableMap<String, String> settings = getBuildSettings(
        buildTarget, generatedProject.getTargets().get(0), "Debug");
    assertThat(settings, hasKey("REPO_ROOT"));
    assertEquals(
        projectFilesystem.getRootPath().toAbsolutePath().normalize().toString(),
        settings.get("REPO_ROOT"));
  }

  /**
   * The project configurations should have named entries corresponding to every existing target
   * configuration for targets in the project.
   */
  @Test
  public void generatedProjectConfigurationListIsUnionOfAllTargetConfigurations()
      throws IOException {
    BuildTarget buildTarget1 = BuildTarget.builder("//foo", "rule1").build();
    TargetNode<?> node1 = AppleLibraryBuilder
        .createBuilder(buildTarget1)
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Conf1", ImmutableMap.<String, String>of(),
                    "Conf2", ImmutableMap.<String, String>of())))
        .build();

    BuildTarget buildTarget2 = BuildTarget.builder("//foo", "rule2").build();
    TargetNode<?> node2 = AppleLibraryBuilder
        .createBuilder(buildTarget2)
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Conf2", ImmutableMap.<String, String>of(),
                    "Conf3", ImmutableMap.<String, String>of())))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(node1, node2));
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
    BuildTarget buildTarget = BuildTarget
        .builder("//foo", "rule")
        .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
        .build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .setUseBuckHeaderMaps(Optional.of(false))
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    ImmutableMap.<String, String>of())))
        .setFrameworks(
            Optional.of(
                ImmutableSortedSet.of(
                    FrameworkPath.ofSourceTreePath(
                        new SourceTreePath(
                            PBXReference.SourceTree.BUILT_PRODUCTS_DIR,
                            Paths.get("libfoo.a"))),
                    FrameworkPath.ofSourceTreePath(
                        new SourceTreePath(
                            PBXReference.SourceTree.SDKROOT,
                            Paths.get("libfoo.a"))),
                    FrameworkPath.ofSourceTreePath(
                        new SourceTreePath(
                            PBXReference.SourceTree.SOURCE_ROOT,
                            Paths.get("libfoo.a"))))))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(node));
    projectGenerator.createXcodeProjects();

    PBXProject generatedProject = projectGenerator.getGeneratedProject();
    PBXTarget target = assertTargetExistsAndReturnTarget(generatedProject, "//foo:rule#shared");
    assertHasSingletonFrameworksPhaseWithFrameworkEntries(
        target,
        ImmutableList.of(
            "$BUILT_PRODUCTS_DIR/libfoo.a",
            "$SDKROOT/libfoo.a",
            "$SOURCE_ROOT/libfoo.a"));

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals(
        "$(inherited) ",
        settings.get("HEADER_SEARCH_PATHS"));
    assertEquals(
        null,
        settings.get("USER_HEADER_SEARCH_PATHS"));
    assertEquals(
        "$(inherited) $BUILT_PRODUCTS_DIR $SDKROOT $SOURCE_ROOT",
        settings.get("LIBRARY_SEARCH_PATHS"));
    assertEquals(
        "$(inherited) $BUILT_PRODUCTS_DIR",
        settings.get("FRAMEWORK_SEARCH_PATHS"));

  }

  @Test
  public void testGeneratedProjectIsNotReadOnlyIfOptionNotSpecified() throws IOException {
    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of());

    projectGenerator.createXcodeProjects();

    assertTrue(fakeProjectFilesystem.getFileAttributesAtPath(OUTPUT_PROJECT_FILE_PATH).isEmpty());
  }

  @Test
  public void testGeneratedProjectIsReadOnlyIfOptionSpecified() throws IOException {
    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(),
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

  @Test
  public void targetGidInDescriptionSetsTargetGidInGeneratedProject() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .setGid(Optional.of("D00D64738"))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(node));
    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:lib");
    // Ensure the GID for the target uses the gid value in the description.
    assertThat(target.getGlobalID(), equalTo("D00D64738"));
  }

  @Test
  public void targetGidInDescriptionReservesGidFromUseByAnotherTarget() throws IOException {
    BuildTarget fooTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> fooNode = AppleLibraryBuilder
        .createBuilder(fooTarget)
        .setGid(Optional.of("E66DC04E36F2D8BE00000000"))
        .build();

    BuildTarget barTarget = BuildTarget.builder("//bar", "lib").build();
    TargetNode<?> barNode = AppleLibraryBuilder
        .createBuilder(barTarget)
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(fooNode, barNode));
    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//bar:lib");
    // Note the '1': normally //bar:lib's GID would be
    // E66DC04E36F2D8BE00000000 but we hard-coded that in //foo:lib, so //bar:lib
    // will try and fail to use GID, as it'll already have been reserved.
    String expectedGID = String.format(
        "%08X%08X%08X", target.isa().hashCode(), target.getName().hashCode(), 1);
    assertEquals(
        "expected GID has correct value",
        "E66DC04E36F2D8BE00000001", expectedGID);
    assertEquals("generated GID is same as expected", expectedGID, target.getGlobalID());
  }

  @Test
  public void conflictingHardcodedGidsThrow() throws IOException {
    BuildTarget fooTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> fooNode = AppleLibraryBuilder
        .createBuilder(fooTarget)
        .setGid(Optional.of("E66DC04E36F2D8BE00000000"))
        .build();

    BuildTarget barTarget = BuildTarget.builder("//bar", "lib").build();
    TargetNode<?> barNode = AppleLibraryBuilder
        .createBuilder(barTarget)
        .setGid(Optional.of("E66DC04E36F2D8BE00000000"))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(fooNode, barNode));

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "Targets [//bar:lib, //foo:lib] have the same hardcoded GID (E66DC04E36F2D8BE00000000)");

    projectGenerator.createXcodeProjects();
  }

  @Test
  public void projectIsRewrittenIfContentsHaveChanged() throws IOException {
    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of());

    clock.setCurrentTimeMillis(49152);
    projectGenerator.createXcodeProjects();
    assertThat(
        projectFilesystem.getLastModifiedTime(OUTPUT_PROJECT_FILE_PATH),
        equalTo(49152L));

    BuildTarget buildTarget = BuildTarget.builder("//foo", "foo").build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .build();
    ProjectGenerator projectGenerator2 = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(node));

    clock.setCurrentTimeMillis(64738);
    projectGenerator2.createXcodeProjects();
    assertThat(
        projectFilesystem.getLastModifiedTime(OUTPUT_PROJECT_FILE_PATH),
        equalTo(64738L));
  }

  @Test
  public void projectIsNotRewrittenIfContentsHaveNotChanged() throws IOException {
    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of());

    clock.setCurrentTimeMillis(49152);
    projectGenerator.createXcodeProjects();
    assertThat(
        projectFilesystem.getLastModifiedTime(OUTPUT_PROJECT_FILE_PATH),
        equalTo(49152L));

    ProjectGenerator projectGenerator2 = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of());

    clock.setCurrentTimeMillis(64738);
    projectGenerator2.createXcodeProjects();
    assertThat(
        projectFilesystem.getLastModifiedTime(OUTPUT_PROJECT_FILE_PATH),
        equalTo(49152L));
  }

  @Test
  public void nonexistentResourceDirectoryShouldThrow() throws IOException {
    ImmutableSet<TargetNode<?>> nodes = setupSimpleLibraryWithResources(
        ImmutableSet.<SourcePath>of(),
        ImmutableSet.<SourcePath>of(new TestSourcePath("nonexistent-directory")));

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "nonexistent-directory specified in the dirs parameter of //foo:res is not a directory");

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(nodes);
    projectGenerator.createXcodeProjects();
  }

  @Test
  public void nonexistentResourceFileShouldThrow() throws IOException {
    ImmutableSet<TargetNode<?>> nodes = setupSimpleLibraryWithResources(
        ImmutableSet.<SourcePath>of(new TestSourcePath("nonexistent-file.png")),
        ImmutableSet.<SourcePath>of());

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "nonexistent-file.png specified in the files parameter of //foo:res is not a regular file");

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(nodes);
    projectGenerator.createXcodeProjects();
  }

  @Test
  public void usingFileAsResourceDirectoryShouldThrow() throws IOException {
    ImmutableSet<TargetNode<?>> nodes = setupSimpleLibraryWithResources(
        ImmutableSet.<SourcePath>of(),
        ImmutableSet.<SourcePath>of(new TestSourcePath("bar.png")));

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "bar.png specified in the dirs parameter of //foo:res is not a directory");

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(nodes);
    projectGenerator.createXcodeProjects();
  }

  @Test
  public void usingDirectoryAsResourceFileShouldThrow() throws IOException {
    ImmutableSet<TargetNode<?>> nodes = setupSimpleLibraryWithResources(
        ImmutableSet.<SourcePath>of(new TestSourcePath("foodir")),
        ImmutableSet.<SourcePath>of());

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "foodir specified in the files parameter of //foo:res is not a regular file");

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(nodes);
    projectGenerator.createXcodeProjects();
  }

  @Test
  public void usingBuildTargetSourcePathInResourceDirsOrFilesDoesNotThrow() throws IOException {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//some:rule");
    SourcePath sourcePath = new BuildTargetSourcePath(buildTarget);
    TargetNode<?> generatingTarget = GenruleBuilder.newGenruleBuilder(buildTarget)
        .setCmd("echo HI")
        .build();

    ImmutableSet<TargetNode<?>> nodes = FluentIterable.from(
        setupSimpleLibraryWithResources(
            ImmutableSet.of(sourcePath),
            ImmutableSet.of(sourcePath)))
        .append(generatingTarget)
        .toSet();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(nodes);
    projectGenerator.createXcodeProjects();
  }

  @Test
  public void testGeneratingTestsAsStaticLibraries() throws IOException {
    TargetNode<AppleTestDescription.Arg> libraryTestStatic =
        AppleTestBuilder.createBuilder(BuildTarget.builder("//foo", "libraryTestStatic").build())
            .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
            .build();
    TargetNode<AppleTestDescription.Arg> libraryTestNotStatic =
        AppleTestBuilder.createBuilder(BuildTarget.builder("//foo", "libraryTestNotStatic").build())
            .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
            .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableList.<TargetNode<?>>of(libraryTestStatic, libraryTestNotStatic));
    projectGenerator
        .setTestsToGenerateAsStaticLibraries(ImmutableSet.of(libraryTestStatic))
        .createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXTarget libraryTestStaticTarget =
        assertTargetExistsAndReturnTarget(project, "//foo:libraryTestStatic");
    PBXTarget libraryTestNotStaticTarget =
        assertTargetExistsAndReturnTarget(project, "//foo:libraryTestNotStatic");
    assertThat(
        libraryTestStaticTarget.getProductType(),
        equalTo(ProductType.STATIC_LIBRARY));
    assertThat(
        libraryTestNotStaticTarget.getProductType(),
        equalTo(ProductType.UNIT_TEST));
  }

  @Test
  public void testGeneratingCombinedTests() throws IOException {
    TargetNode<AppleResourceDescription.Arg> testLibDepResource =
        AppleResourceBuilder.createBuilder(BuildTarget.builder("//lib", "deplibresource").build())
            .setFiles(ImmutableSet.<SourcePath>of(new TestSourcePath("bar.png")))
            .setDirs(ImmutableSet.<SourcePath>of())
            .build();
    TargetNode<AppleNativeTargetDescriptionArg> testLibDepLib =
        AppleLibraryBuilder.createBuilder(BuildTarget.builder("//libs", "deplib").build())
            .setFrameworks(
                Optional.of(
                    ImmutableSortedSet.of(
                        FrameworkPath.ofSourceTreePath(
                            new SourceTreePath(
                                PBXReference.SourceTree.SDKROOT,
                                Paths.get("DeclaredInTestLibDep.framework"))))))
            .setDeps(Optional.of(ImmutableSortedSet.of(testLibDepResource.getBuildTarget())))
            .setSrcs(Optional.of(ImmutableList.of(SourceWithFlags.of(new TestSourcePath("e.m")))))
            .build();
    TargetNode<AppleNativeTargetDescriptionArg> dep1 =
        AppleLibraryBuilder.createBuilder(BuildTarget.builder("//foo", "dep1").build())
            .setDeps(Optional.of(ImmutableSortedSet.of(testLibDepLib.getBuildTarget())))
            .setSrcs(Optional.of(ImmutableList.of(SourceWithFlags.of(new TestSourcePath("e.m")))))
            .setFrameworks(
                Optional.of(
                    ImmutableSortedSet.of(
                        FrameworkPath.ofSourceTreePath(
                            new SourceTreePath(
                                PBXReference.SourceTree.SDKROOT,
                                Paths.get("DeclaredInTestLib.framework"))))))
            .build();
    TargetNode<AppleNativeTargetDescriptionArg> dep2 =
        AppleLibraryBuilder.createBuilder(BuildTarget.builder("//foo", "dep2").build())
            .setSrcs(Optional.of(ImmutableList.of(SourceWithFlags.of(new TestSourcePath("e.m")))))
            .build();
    TargetNode<AppleTestDescription.Arg> xctest1 =
        AppleTestBuilder.createBuilder(BuildTarget.builder("//foo", "xctest1").build())
            .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
            .setDeps(Optional.of(ImmutableSortedSet.of(dep1.getBuildTarget())))
            .setFrameworks(
                Optional.of(
                    ImmutableSortedSet.of(
                        FrameworkPath.ofSourceTreePath(
                            new SourceTreePath(
                                PBXReference.SourceTree.SDKROOT,
                                Paths.get("DeclaredInTest.framework"))))))
            .build();
    TargetNode<AppleTestDescription.Arg> xctest2 =
        AppleTestBuilder.createBuilder(BuildTarget.builder("//foo", "xctest2").build())
            .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
            .setDeps(Optional.of(ImmutableSortedSet.of(dep2.getBuildTarget())))
            .build();

    ProjectGenerator projectGenerator = new ProjectGenerator(
        TargetGraphFactory.newInstance(
            testLibDepResource,
            testLibDepLib,
            dep1,
            dep2,
            xctest1,
            xctest2),
        ImmutableSet.<BuildTarget>of(),
        projectFilesystem,
        /* reactNativeServer */ Optional.<Path>absent(),
        OUTPUT_DIRECTORY,
        PROJECT_NAME,
        "BUCK",
        ProjectGenerator.SEPARATED_PROJECT_OPTIONS,
        Optional.<BuildTarget>absent(),
        ImmutableList.<String>of(),
        new Function<TargetNode<?>, Path>() {
          @Nullable
          @Override
          public Path apply(TargetNode<?> input) {
            return null;
          }
        })
        .setTestsToGenerateAsStaticLibraries(ImmutableSet.of(xctest1, xctest2))
        .setAdditionalCombinedTestTargets(
            ImmutableMultimap.of(
                AppleTestBundleParamsKey.fromAppleTestDescriptionArg(xctest1.getConstructorArg()),
                xctest1,
                AppleTestBundleParamsKey.fromAppleTestDescriptionArg(xctest2.getConstructorArg()),
                xctest2));
    projectGenerator.createXcodeProjects();

    ImmutableSet<PBXTarget> combinedTestTargets =
        projectGenerator.getBuildableCombinedTestTargets();
    assertThat(combinedTestTargets, hasSize(1));
    assertThat(combinedTestTargets, hasItem(targetWithName("_BuckCombinedTest-xctest-0")));

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXTarget target = assertTargetExistsAndReturnTarget(project, "_BuckCombinedTest-xctest-0");
    assertHasSingletonSourcesPhaseWithSourcesAndFlags(
        target,
        ImmutableMap.of(
            BuckConstant.GEN_PATH.resolve("xcode-scripts/emptyFile.c").toString(),
            Optional.<String>absent()));
    assertHasSingletonFrameworksPhaseWithFrameworkEntries(
        target,
        ImmutableList.of(
            "$BUILT_PRODUCTS_DIR/libxctest1.a",
            "$BUILT_PRODUCTS_DIR/libxctest2.a",
            "$BUILT_PRODUCTS_DIR/libdeplib.a",
            "$BUILT_PRODUCTS_DIR/libdep1.a",
            "$BUILT_PRODUCTS_DIR/libdep2.a",
            "$SDKROOT/DeclaredInTestLib.framework",
            "$SDKROOT/DeclaredInTestLibDep.framework",
            "$SDKROOT/DeclaredInTest.framework"));
    assertHasSingletonResourcesPhaseWithEntries(
        target,
        "bar.png");
  }

  @Test
  public void testResolvingExportFile() throws IOException {
    BuildTarget source1Target = BuildTarget.builder("//Vendor", "source1").build();
    BuildTarget source2Target = BuildTarget.builder("//Vendor", "source2").build();
    BuildTarget source2RefTarget = BuildTarget.builder("//Vendor", "source2ref").build();
    BuildTarget source3Target = BuildTarget.builder("//Vendor", "source3").build();
    BuildTarget headerTarget = BuildTarget.builder("//Vendor", "header").build();
    BuildTarget libTarget = BuildTarget.builder("//Libraries", "foo").build();

    TargetNode<ExportFileDescription.Arg> source1 = ExportFileBuilder
        .newExportFileBuilder(source1Target)
        .setSrc(new PathSourcePath(projectFilesystem, Paths.get("Vendor/sources/source1")))
        .build();

    TargetNode<ExportFileDescription.Arg> source2 = ExportFileBuilder
        .newExportFileBuilder(source2Target)
        .setSrc(new PathSourcePath(projectFilesystem, Paths.get("Vendor/source2")))
        .build();

    TargetNode<ExportFileDescription.Arg> source2Ref = ExportFileBuilder
        .newExportFileBuilder(source2RefTarget)
        .setSrc(new BuildTargetSourcePath(source2Target))
        .build();

    TargetNode<ExportFileDescription.Arg> source3 = ExportFileBuilder
        .newExportFileBuilder(source3Target)
        .build();

    TargetNode<ExportFileDescription.Arg> header = ExportFileBuilder
        .newExportFileBuilder(headerTarget)
        .build();

    TargetNode<AppleNativeTargetDescriptionArg> library = AppleLibraryBuilder
        .createBuilder(libTarget)
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    ImmutableMap.<String, String>of())))
        .setSrcs(
            Optional.of(
                ImmutableList.of(
                    SourceWithFlags.of(
                        new BuildTargetSourcePath(source1Target)),
                    SourceWithFlags.of(
                        new BuildTargetSourcePath(source2RefTarget)),
                    SourceWithFlags.of(
                        new BuildTargetSourcePath(source3Target)))))
        .setPrefixHeader(
            Optional.<SourcePath>of(new BuildTargetSourcePath(headerTarget)))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(
            source1,
            source2,
            source2Ref,
            source3,
            header,
            library));

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        libTarget.toString());

    assertHasSingletonSourcesPhaseWithSourcesAndFlags(
        target,
        ImmutableMap.of(
            "Vendor/sources/source1", Optional.<String>absent(),
            "Vendor/source2", Optional.<String>absent(),
            "Vendor/source3", Optional.<String>absent()));

    ImmutableMap<String, String> settings = getBuildSettings(libTarget, target, "Debug");
    assertEquals("../Vendor/header", settings.get("GCC_PREFIX_HEADER"));
  }

  @Test
  public void applicationTestUsesHostAppAsTestHostAndBundleLoader() throws IOException {
    BuildTarget hostAppBinaryTarget = BuildTarget.builder("//foo", "HostAppBinary").build();
    TargetNode<?> hostAppBinaryNode = AppleBinaryBuilder
        .createBuilder(hostAppBinaryTarget)
        .build();

    BuildTarget hostAppTarget = BuildTarget.builder("//foo", "HostApp").build();
    TargetNode<?> hostAppNode = AppleBundleBuilder
        .createBuilder(hostAppTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.APP))
        .setBinary(hostAppBinaryTarget)
        .build();

    BuildTarget testTarget = BuildTarget.builder("//foo", "AppTest").build();
    TargetNode<?> testNode = AppleTestBuilder.createBuilder(testTarget)
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    ImmutableMap.<String, String>of())))
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setTestHostApp(Optional.of(hostAppTarget))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(hostAppBinaryNode, hostAppNode, testNode),
        ImmutableSet.<ProjectGenerator.Option>of());

    projectGenerator.createXcodeProjects();

    PBXTarget testPBXTarget = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:AppTest");

    ImmutableMap<String, String> settings = getBuildSettings(testTarget, testPBXTarget, "Debug");
    assertEquals("$BUILT_PRODUCTS_DIR/./HostApp.app/HostApp", settings.get("BUNDLE_LOADER"));
    assertEquals("$(BUNDLE_LOADER)", settings.get("TEST_HOST"));
  }

  @Test
  public void aggregateTargetForBuildWithBuck() throws IOException {
    BuildTarget binaryTarget = BuildTarget.builder("//foo", "binary").build();
    TargetNode<?> binaryNode = AppleBinaryBuilder
        .createBuilder(binaryTarget)
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    ImmutableMap.<String, String>of())))
        .setSrcs(
            Optional.of(
                ImmutableList.of(
                    SourceWithFlags.of(
                        new TestSourcePath("foo.m"), ImmutableList.of("-foo")))))
        .build();

    ImmutableSet<TargetNode<?>> nodes = ImmutableSet.<TargetNode<?>>of(binaryNode);
    ProjectGenerator projectGenerator = new ProjectGenerator(
        TargetGraphFactory.newInstance(nodes),
        FluentIterable.from(nodes).transform(HasBuildTarget.TO_TARGET).toSet(),
        projectFilesystem,
        /* reactNativeServer */ Optional.<Path>absent(),
        OUTPUT_DIRECTORY,
        PROJECT_NAME,
        "BUCK",
        ImmutableSet.<ProjectGenerator.Option>of(),
        Optional.of(binaryTarget),
        ImmutableList.of("--flag", "value with spaces"),
        Functions.<Path>constant(null));
    projectGenerator.createXcodeProjects();

    PBXTarget buildWithBuckTarget = null;
    for (PBXTarget target : projectGenerator.getGeneratedProject().getTargets()) {
      if (target.getProductName() != null &&
          target.getProductName().endsWith("-Buck")) {
        buildWithBuckTarget = target;
      }
    }
    assertThat(buildWithBuckTarget, is(notNullValue()));

    assertHasConfigurations(buildWithBuckTarget, "Debug");
    assertEquals(
        "Should have exact number of build phases",
        1,
        buildWithBuckTarget.getBuildPhases().size());
    PBXBuildPhase buildPhase = Iterables.getOnlyElement(buildWithBuckTarget.getBuildPhases());
    assertThat(buildPhase, instanceOf(PBXShellScriptBuildPhase.class));
    PBXShellScriptBuildPhase shellScriptBuildPhase = (PBXShellScriptBuildPhase) buildPhase;
    assertThat(
        shellScriptBuildPhase.getShellScript(),
        equalTo("buck build --flag 'value with spaces' " + binaryTarget.getFullyQualifiedName()));
  }

  @Test
  public void cxxFlagsPropagatedToConfig() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .setLangPreprocessorFlags(
            Optional.of(
                ImmutableMap.of(
                    CxxSource.Type.CXX, ImmutableList.of("-std=c++11", "-stdlib=libc++"),
                    CxxSource.Type.OBJCXX, ImmutableList.of("-std=c++11", "-stdlib=libc++"))))
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    ImmutableMap.<String, String>of())))
        .setSrcs(
            Optional.of(
                ImmutableList.of(SourceWithFlags.of(new TestSourcePath("foo.mm")))))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(node));

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:lib");

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals("$(inherited) -std=c++11 -stdlib=libc++", settings.get("OTHER_CPLUSPLUSFLAGS"));
  }

  @Test
  public void unsupportedLangPreprocessorFlagsThrows() throws IOException {
    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "//foo:lib: Xcode project generation does not support specified lang_preprocessor_flags " +
        "keys: [ASSEMBLER]");

    BuildTarget buildTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .setLangPreprocessorFlags(
            Optional.of(
                ImmutableMap.of(
                    CxxSource.Type.ASSEMBLER, ImmutableList.of("-Xawesome"))))
        .setConfigs(
            Optional.of(
                ImmutableSortedMap.of(
                    "Debug",
                    ImmutableMap.<String, String>of())))
        .setSrcs(
            Optional.of(
                ImmutableList.of(SourceWithFlags.of(new TestSourcePath("foo.mm")))))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(node));

    projectGenerator.createXcodeProjects();
  }

  private ProjectGenerator createProjectGeneratorForCombinedProject(
      Iterable<TargetNode<?>> nodes) {
    return createProjectGeneratorForCombinedProject(
        nodes,
        ImmutableSet.<ProjectGenerator.Option>of());
  }

  private ProjectGenerator createProjectGeneratorForCombinedProject(
      Iterable<TargetNode<?>> nodes,
      ImmutableSet<ProjectGenerator.Option> projectGeneratorOptions) {
    return createProjectGeneratorForCombinedProject(
        nodes,
        projectGeneratorOptions,
        Optional.<Path>absent());
  }

  private ProjectGenerator createProjectGeneratorForCombinedProject(
      Iterable<TargetNode<?>> nodes,
      ImmutableSet<ProjectGenerator.Option> projectGeneratorOptions,
      Optional<Path> reactNativeServer) {
    ImmutableSet<BuildTarget> initialBuildTargets = FluentIterable
        .from(nodes)
        .transform(HasBuildTarget.TO_TARGET)
        .toSet();

    return new ProjectGenerator(
        TargetGraphFactory.newInstance(ImmutableSet.copyOf(nodes)),
        initialBuildTargets,
        projectFilesystem,
        reactNativeServer,
        OUTPUT_DIRECTORY,
        PROJECT_NAME,
        "BUCK",
        projectGeneratorOptions,
        Optional.<BuildTarget>absent(),
        ImmutableList.<String>of(),
        Functions.<Path>constant(null));
  }

  private ImmutableSet<TargetNode<?>> setupSimpleLibraryWithResources(
      ImmutableSet<SourcePath> resourceFiles,
      ImmutableSet<SourcePath> resourceDirectories) {
    BuildTarget resourceTarget = BuildTarget.builder("//foo", "res").build();
    TargetNode<?> resourceNode = AppleResourceBuilder
        .createBuilder(resourceTarget)
        .setFiles(resourceFiles)
        .setDirs(resourceDirectories)
        .build();

    BuildTarget libraryTarget = BuildTarget.builder("//foo", "foo").build();
    TargetNode<?> libraryNode = AppleLibraryBuilder
        .createBuilder(libraryTarget)
        .setDeps(Optional.of(ImmutableSortedSet.of(resourceTarget)))
        .build();

    return ImmutableSet.of(resourceNode, libraryNode);
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
        getSingletonPhaseByType(target, PBXSourcesBuildPhase.class);

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
      assertNotNull(String.format("Unexpected file ref '%s' found", filePath), flags);
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
        getSingletonPhaseByType(target, PBXResourcesBuildPhase.class);
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

  private ImmutableMap<String, String> getBuildSettings(
      BuildTarget buildTarget, PBXTarget target, String config) {
    assertHasConfigurations(target, config);
    return ProjectGeneratorTestUtils.getBuildSettings(
        projectFilesystem, buildTarget, target, config);
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

  private Matcher<PBXTarget> targetWithName(String name) {
    return new FeatureMatcher<PBXTarget, String>(
        org.hamcrest.Matchers.equalTo(name),
        "target with name",
        "name") {
      @Override
      protected String featureValueOf(PBXTarget pbxTarget) {
        return pbxTarget.getName();
      }
    };
  }
}
