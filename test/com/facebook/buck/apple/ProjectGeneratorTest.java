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

import static com.facebook.buck.apple.ProjectGeneratorTestUtils.assertHasSingletonFrameworksPhaseWithFrameworkEntries;
import static com.facebook.buck.apple.ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.instanceOf;
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
import com.facebook.buck.apple.xcode.xcodeproj.ImmutableProductType;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildFile;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildPhase;
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
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.rules.coercer.AppleSource;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.timing.SettableFakeClock;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.HumanReadableException;
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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
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
  public void shouldNotCreateHeaderMapsWhenHeaderMapsAreDisabled() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .setSrcs(
            Optional.of(
                ImmutableList.of(
                    AppleSource.ofSourcePath(new TestSourcePath("HeaderGroup1/foo.h")),
                    AppleSource.ofSourcePathWithFlags(
                        new Pair<SourcePath, String>(
                            new TestSourcePath("HeaderGroup1/bar.h"), "public")))))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(node));

    projectGenerator.createXcodeProjects();

    // No header map should be generated
    List<Path> headerMaps = projectGenerator.getGeneratedHeaderMaps();
    assertThat(headerMaps, hasSize(0));
  }

  @Test(expected = HumanReadableException.class)
  public void testLibraryPrivateHeaderWithHeaderMaps() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .setSrcs(
            Optional.of(
                ImmutableList.of(
                      AppleSource.ofSourcePathWithFlags(
                          new Pair<SourcePath, String>(
                              new TestSourcePath("HeaderGroup2/blech.h"), "private")))))
        .setUseBuckHeaderMaps(Optional.of(true))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(node));

    projectGenerator.createXcodeProjects();
  }

  @Test
  public void testLibraryHeaderGroupsWithHeaderMaps() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .setSrcs(
            Optional.of(
                ImmutableList.of(
                    AppleSource.ofSourcePath(new TestSourcePath("HeaderGroup1/foo.h")),
                    AppleSource.ofSourcePathWithFlags(
                        new Pair<SourcePath, String>(
                            new TestSourcePath("HeaderGroup1/bar.h"),
                            "public")),
                    AppleSource.ofSourcePath(
                        new TestSourcePath("HeaderGroup2/baz.h")))))
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
    assertEquals(Optional.<PBXBuildPhase>absent(),
        Iterables.tryFind(target.getBuildPhases(), new Predicate<PBXBuildPhase>() {
          @Override
          public boolean apply(PBXBuildPhase input) {
            return input instanceof PBXHeadersBuildPhase;
          }
        }));

    List<Path> headerMaps = projectGenerator.getGeneratedHeaderMaps();
    assertThat(headerMaps, hasSize(3));

    assertEquals("buck-out/gen/foo/lib-public-headers.hmap", headerMaps.get(0).toString());
    assertThatHeaderMapFileContains(
        Paths.get("buck-out/gen/foo/lib-public-headers.hmap"),
        ImmutableMap.<String, String>of("lib/bar.h", "HeaderGroup1/bar.h")
    );

    assertEquals("buck-out/gen/foo/lib-target-headers.hmap", headerMaps.get(1).toString());
    assertThatHeaderMapFileContains(
        Paths.get("buck-out/gen/foo/lib-target-headers.hmap"),
        ImmutableMap.<String, String>of(
            "lib/foo.h", "HeaderGroup1/foo.h",
            "lib/bar.h", "HeaderGroup1/bar.h",
            "lib/baz.h", "HeaderGroup2/baz.h"
            )
    );

    assertEquals("buck-out/gen/foo/lib-target-user-headers.hmap", headerMaps.get(2).toString());
    assertThatHeaderMapFileContains(
        Paths.get("buck-out/gen/foo/lib-target-user-headers.hmap"),
        ImmutableMap.<String, String>of(
            "foo.h", "HeaderGroup1/foo.h",
            "bar.h", "HeaderGroup1/bar.h",
            "baz.h", "HeaderGroup2/baz.h"
        )
    );
  }

  @Test
  public void testHeaderMapsWithHeadersVisibleForTesting() throws IOException {
    BuildTarget libraryTarget = BuildTarget.builder("//foo", "lib").build();
    BuildTarget testTarget = BuildTarget.builder("//foo", "test").build();

    TargetNode<?> libraryNode = AppleLibraryBuilder
        .createBuilder(libraryTarget)
        .setSrcs(
            Optional.of(
                ImmutableList.of(
                    AppleSource.ofSourcePathWithFlags(
                        new Pair<SourcePath, String>(
                            new TestSourcePath("foo.h"),
                            "public")),
                    AppleSource.ofSourcePath(
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
        "test binary should use headermaps for both public and non-public headers of the tested " +
            "library in HEADER_SEARCH_PATHS",
        "$(inherited) " +
            "../buck-out/gen/foo/test-target-headers.hmap " +
            "../buck-out/gen/foo/lib-public-headers.hmap " +
            "../buck-out/gen/foo/lib-target-headers.hmap",
        buildSettings.get("HEADER_SEARCH_PATHS"));
    assertEquals(
        "test binary should use user headermaps of its own as well as of the tested library " +
            "in USER_HEADER_SEARCH_PATHS",
        "$(inherited) " +
            "../buck-out/gen/foo/test-target-user-headers.hmap " +
            "../buck-out/gen/foo/lib-target-user-headers.hmap",
        buildSettings.get("USER_HEADER_SEARCH_PATHS"));
  }

  @Test
  public void testHeaderMapsWithTestsAndLibraryBundles() throws IOException {
    BuildTarget libraryTarget = BuildTarget.builder("//foo", "lib").build();
    BuildTarget bundleTarget = BuildTarget.builder("//foo", "bundle").build();
    BuildTarget testTarget = BuildTarget.builder("//foo", "test").build();

    TargetNode<?> libraryNode = AppleLibraryBuilder
        .createBuilder(libraryTarget)
        .setSrcs(
            Optional.of(
                ImmutableList.of(
                    AppleSource.ofSourcePathWithFlags(
                        new Pair<SourcePath, String>(
                            new TestSourcePath("foo.h"),
                            "public")),
                    AppleSource.ofSourcePath(
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
        "test binary should use headermaps for both public and non-public headers of the tested " +
            "library in HEADER_SEARCH_PATHS",
        "$(inherited) " +
            "../buck-out/gen/foo/test-target-headers.hmap " +
            "../buck-out/gen/foo/lib-public-headers.hmap " +
            "../buck-out/gen/foo/lib-target-headers.hmap",
        buildSettings.get("HEADER_SEARCH_PATHS"));
    assertEquals(
        "test binary should use user headermaps of its own as well as of the tested library " +
            "in USER_HEADER_SEARCH_PATHS",
        "$(inherited) " +
            "../buck-out/gen/foo/test-target-user-headers.hmap " +
            "../buck-out/gen/foo/lib-target-user-headers.hmap",
        buildSettings.get("USER_HEADER_SEARCH_PATHS"));
  }

  @Test
  public void testHeaderMapsWithTestsAndBinaryBundles() throws IOException {
    BuildTarget binaryTarget = BuildTarget.builder("//foo", "bin").build();
    BuildTarget bundleTarget = BuildTarget.builder("//foo", "bundle").build();
    BuildTarget testTarget = BuildTarget.builder("//foo", "test").build();

    TargetNode<?> binaryNode = AppleBinaryBuilder
        .createBuilder(binaryTarget)
        .setSrcs(
            Optional.of(
                ImmutableList.of(
                    AppleSource.ofSourcePathWithFlags(
                        new Pair<SourcePath, String>(
                            new TestSourcePath("foo.h"),
                            "public")),
                    AppleSource.ofSourcePath(
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
        "test binary should use headermaps for both public and non-public headers of the tested " +
            "binary in HEADER_SEARCH_PATHS",
        "$(inherited) " +
            "../buck-out/gen/foo/test-target-headers.hmap " +
            "../buck-out/gen/foo/bin-public-headers.hmap " +
            "../buck-out/gen/foo/bin-target-headers.hmap",
        buildSettings.get("HEADER_SEARCH_PATHS"));
    assertEquals(
        "test binary should use user headermaps of its own as well as of the tested binary " +
            "in USER_HEADER_SEARCH_PATHS",
        "$(inherited) " +
            "../buck-out/gen/foo/test-target-user-headers.hmap " +
            "../buck-out/gen/foo/bin-target-user-headers.hmap",
        buildSettings.get("USER_HEADER_SEARCH_PATHS"));
  }

  private void assertThatHeaderMapFileContains(Path file, ImmutableMap<String, String> content)
      throws IOException {
    byte[] headerMapBytes = ByteStreams.toByteArray(projectFilesystem.newFileInputStream(file));
    HeaderMap map = HeaderMap.deserialize(headerMapBytes);
    assertEquals(content.size(), map.getNumEntries());
    for (String key : content.keySet()) {
      assertEquals(
          Paths.get(content.get(key)).toAbsolutePath().toString(),
          map.lookup(key));
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
                    AppleSource.ofSourcePathWithFlags(
                        new Pair<SourcePath, String>(new TestSourcePath("foo.m"), "-foo")),
                    AppleSource.ofSourcePath(new TestSourcePath("foo.h")),
                    AppleSource.ofSourcePath(new TestSourcePath("bar.m")))))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(node));

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:lib");
    assertThat(target.isa(), equalTo("PBXNativeTarget"));
    assertThat(target.getProductType(), equalTo(PBXTarget.ProductType.STATIC_LIBRARY));

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
    assertThat(target.getProductType(), equalTo(PBXTarget.ProductType.STATIC_LIBRARY));

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals(
        "../Foo/Foo-Prefix.pch",
        settings.get("GCC_PREFIX_HEADER"));
    assertEquals(
        "$SYMROOT/$CONFIGURATION$EFFECTIVE_PLATFORM_NAME",
        settings.get("BUILT_PRODUCTS_DIR"));
    assertEquals(
        "$BUILT_PRODUCTS_DIR/F4XWM33PHJWGSYQ",
        settings.get("CONFIGURATION_BUILD_DIR"));
    assertEquals(
        "Headers/MyHeaderPathPrefix",
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
    assertThat(target.getProductType(), equalTo(PBXTarget.ProductType.DYNAMIC_LIBRARY));

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals(
        "$SYMROOT/$CONFIGURATION$EFFECTIVE_PLATFORM_NAME",
        settings.get("BUILT_PRODUCTS_DIR"));
    assertEquals(
        "$BUILT_PRODUCTS_DIR/F4XWQ2J2NRUWEI3TNBQXEZLE",
        settings.get("CONFIGURATION_BUILD_DIR"));
    assertEquals(
        "Headers/MyHeaderPathPrefix",
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
    assertThat(target.getProductType(), equalTo(PBXTarget.ProductType.STATIC_LIBRARY));

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals(
        "$SYMROOT/$CONFIGURATION$EFFECTIVE_PLATFORM_NAME",
        settings.get("BUILT_PRODUCTS_DIR"));
    assertEquals(
        "$BUILT_PRODUCTS_DIR/F4XWM33PHJWGSYQ",
        settings.get("CONFIGURATION_BUILD_DIR"));
    assertEquals(
        "FooHeaders",
        settings.get("PUBLIC_HEADERS_FOLDER_PATH"));
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
    assertThat(target.getProductType(), equalTo(PBXTarget.ProductType.STATIC_LIBRARY));

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
        "$BUILT_PRODUCTS_DIR/F4XWM33PHJWGSYQ",
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
        .setSrcs(
            Optional.of(ImmutableList.of(AppleSource.ofSourcePath(new TestSourcePath("foo.m")))))
        .setFrameworks(Optional.of(ImmutableSortedSet.of("$SDKROOT/Library.framework")))
        .build();

    BuildTarget testTarget = BuildTarget.builder("//foo", "xctest").build();
    TargetNode<?> testNode = AppleTestBuilder
        .createBuilder(testTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setConfigs(Optional.of(configs))
        .setSrcs(
            Optional.of(
                ImmutableList.of(AppleSource.ofSourcePath(new TestSourcePath("fooTest.m")))))
        .setFrameworks(Optional.of(ImmutableSortedSet.of("$SDKROOT/Test.framework")))
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
        "$(inherited) " + "$BUILT_PRODUCTS_DIR/F4XWM33PHJWGSYQ/Headers",
        settings.get("HEADER_SEARCH_PATHS"));
    assertEquals(
        "$(inherited) ",
        settings.get("USER_HEADER_SEARCH_PATHS"));
    assertEquals(
        "$(inherited) " + "$BUILT_PRODUCTS_DIR/F4XWM33PHJWGSYQ",
        settings.get("LIBRARY_SEARCH_PATHS"));
    assertEquals(
        "$(inherited) ",
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
        .setSrcs(
            Optional.of(ImmutableList.of(AppleSource.ofSourcePath(new TestSourcePath("foo.m")))))
        .setFrameworks(Optional.of(ImmutableSortedSet.of("$SDKROOT/Library.framework")))
        .build();

    BuildTarget testTarget = BuildTarget.builder("//foo", "xctest").build();
    TargetNode<?> testNode = AppleTestBuilder
        .createBuilder(testTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setConfigs(Optional.of(configs))
        .setSrcs(
            Optional.of(
                ImmutableList.of(AppleSource.ofSourcePath(new TestSourcePath("fooTest.m")))))
        .setFrameworks(Optional.of(ImmutableSortedSet.of("$SDKROOT/Test.framework")))
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
        "headers " + "$BUILT_PRODUCTS_DIR/F4XWM33PHJWGSYQ/Headers",
        settings.get("HEADER_SEARCH_PATHS"));
    assertEquals(
        "user_headers ",
        settings.get("USER_HEADER_SEARCH_PATHS"));
    assertEquals(
        "libraries " + "$BUILT_PRODUCTS_DIR/F4XWM33PHJWGSYQ",
        settings.get("LIBRARY_SEARCH_PATHS"));
    assertEquals(
        "frameworks ",
        settings.get("FRAMEWORK_SEARCH_PATHS"));
  }

  @Test
  public void testAppleLibraryTransitiveDependentsSearchHeadersAndLibraries() throws IOException {
    ImmutableSortedMap<String, ImmutableMap<String, String>> configs = ImmutableSortedMap.of(
        "Debug", ImmutableMap.<String, String>of());

    BuildTarget libraryDepTarget = BuildTarget.builder("//bar", "lib").build();
    TargetNode<?> libraryDepNode = AppleLibraryBuilder
        .createBuilder(libraryDepTarget)
        .setConfigs(Optional.of(configs))
        .setSrcs(
            Optional.of(ImmutableList.of(AppleSource.ofSourcePath(new TestSourcePath("foo.m")))))
        .setFrameworks(Optional.of(ImmutableSortedSet.of("$SDKROOT/Library.framework")))
        .build();

    BuildTarget libraryTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> libraryNode = AppleLibraryBuilder
        .createBuilder(libraryTarget)
        .setConfigs(Optional.of(configs))
        .setSrcs(
            Optional.of(ImmutableList.of(AppleSource.ofSourcePath(new TestSourcePath("foo.m")))))
        .setFrameworks(Optional.of(ImmutableSortedSet.of("$SDKROOT/Library.framework")))
        .setDeps(Optional.of(ImmutableSortedSet.of(libraryDepTarget)))
        .build();

    BuildTarget testTarget = BuildTarget.builder("//foo", "xctest").build();
    TargetNode<?> testNode = AppleTestBuilder
        .createBuilder(testTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .setConfigs(Optional.of(configs))
        .setSrcs(
            Optional.of(
                ImmutableList.of(AppleSource.ofSourcePath(new TestSourcePath("fooTest.m")))))
        .setFrameworks(Optional.of(ImmutableSortedSet.of("$SDKROOT/Test.framework")))
        .setDeps(Optional.of(ImmutableSortedSet.of(libraryTarget)))
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
        "$(inherited) ",
        settings.get("USER_HEADER_SEARCH_PATHS"));
    assertEquals(
        "$(inherited) " +
            "$BUILT_PRODUCTS_DIR/F4XWEYLSHJWGSYQ " +
            "$BUILT_PRODUCTS_DIR/F4XWM33PHJWGSYQ",
        settings.get("LIBRARY_SEARCH_PATHS"));
    assertEquals(
        "$(inherited) ",
        settings.get("FRAMEWORK_SEARCH_PATHS"));
  }

  @Test
  public void testAppleTestRule() throws IOException {
    BuildTarget testTarget = BuildTarget.builder("//foo", "xctest").build();
    TargetNode<?> testNode = AppleTestBuilder
        .createBuilder(testTarget)
        .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(testNode));
    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:xctest");
    assertEquals(target.getProductType(), PBXTarget.ProductType.UNIT_TEST);
    assertThat(target.isa(), equalTo("PBXNativeTarget"));
    PBXFileReference productReference = target.getProductReference();
    assertEquals("xctest.xctest", productReference.getName());
  }

  @Test
  public void testAppleBinaryRule() throws IOException {
    BuildTarget depTarget = BuildTarget.builder("//dep", "dep").build();
    TargetNode<?> depNode = AppleLibraryBuilder
        .createBuilder(depTarget)
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
                    AppleSource.ofSourcePathWithFlags(
                        new Pair<SourcePath, String>(new TestSourcePath("foo.m"), "-foo")),
                    AppleSource.ofSourcePath(new TestSourcePath("foo.h")))))
        .setFrameworks(Optional.of(ImmutableSortedSet.of("$SDKROOT/Foo.framework")))
        .setDeps(Optional.of(ImmutableSortedSet.of(depTarget)))
        .setGid(Optional.<String>absent())
        .setHeaderPathPrefix(Optional.<String>absent())
        .setUseBuckHeaderMaps(Optional.<Boolean>absent())
        .setPrefixHeader(Optional.<SourcePath>absent())
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(depNode, binaryNode));
    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:binary");
    assertHasConfigurations(target, "Debug");
    assertEquals(target.getProductType(), PBXTarget.ProductType.TOOL);
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
            "$BUILT_PRODUCTS_DIR/F4XWIZLQHJSGK4A/libdep.a"));

    // this test does not have a dependency on any asset catalogs, so verify no build phase for them
    // exists.
    assertFalse(hasShellScriptPhaseToCompileAssetCatalogs(target));
  }

  @Test
  public void testAppleBundleRuleWithPostBuildScriptDependency() throws IOException {
    BuildTarget scriptTarget = BuildTarget.builder("//foo", "post_build_script").build();
    TargetNode<?> scriptNode = IosPostprocessResourcesBuilder
        .createBuilder(scriptTarget)
        .setCmd(Optional.of("script.sh"))
        .build();

    BuildTarget resourceTarget = BuildTarget.builder("//foo", "resource").build();
    TargetNode<?> resourceNode = AppleResourceBuilder
        .createBuilder(resourceTarget)
        .setFiles(ImmutableSet.<SourcePath>of(new TestSourcePath("bar.png")))
        .setDirs(ImmutableSet.<Path>of())
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
    PBXTarget target = ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget(
        project, "//foo:bundle");
    assertThat(target.getName(), equalTo("//foo:bundle"));
    assertThat(target.isa(), equalTo("PBXNativeTarget"));

    PBXShellScriptBuildPhase shellScriptBuildPhase =
        ProjectGeneratorTestUtils.getSingletonPhaseByType(
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
  public void testAppleBundleRuleForSharedLibaryFramework() throws IOException {
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
    assertEquals(target.getProductType(), PBXTarget.ProductType.FRAMEWORK);
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
        ImmutableProductType.of("com.facebook.buck.niftyProductType"));
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
  public void ruleToTargetMapContainsPBXTarget() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder("//foo", "lib").build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .setSrcs(
            Optional.of(
                ImmutableList.of(
                    AppleSource.ofSourcePathWithFlags(
                        new Pair<SourcePath, String>(new TestSourcePath("foo.m"), "-foo")),
                    AppleSource.ofSourcePath(new TestSourcePath("foo.h")),
                    AppleSource.ofSourcePath(new TestSourcePath("bar.m")))))
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
    assertEquals(target.getProductType(), PBXTarget.ProductType.BUNDLE);
    assertEquals("Should have exact number of build phases ", 2, target.getBuildPhases().size());
    ProjectGeneratorTestUtils.assertHasSingletonFrameworksPhaseWithFrameworkEntries(
        target,
        ImmutableList.of(
            "$BUILT_PRODUCTS_DIR/F4XWIZLQHJZWQYLSMVSCG43IMFZGKZA/libshared.dylib"));
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
    assertEquals(target.getProductType(), PBXTarget.ProductType.BUNDLE);
    assertEquals("Should have exact number of build phases ", 2, target.getBuildPhases().size());
    ProjectGeneratorTestUtils.assertHasSingletonFrameworksPhaseWithFrameworkEntries(
        target,
        ImmutableList.of("$BUILT_PRODUCTS_DIR/F4XWIZLQHJTHEYLNMV3W64TL/framework.framework"));
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
    assertEquals(target.getProductType(), PBXTarget.ProductType.BUNDLE);
    assertEquals("Should have exact number of build phases ", 2, target.getBuildPhases().size());
    ProjectGeneratorTestUtils.assertHasSingletonCopyFilesPhaseWithFileEntries(
        target,
        ImmutableList.of("$BUILT_PRODUCTS_DIR/F4XWIZLQHJTHEYLNMV3W64TL/framework.framework"));
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
    assertEquals(target.getProductType(), PBXTarget.ProductType.BUNDLE);
    assertEquals("Should have exact number of build phases ", 0, target.getBuildPhases().size());
  }

  @Test
  public void resourcesInDependenciesPropagatesToBundles() throws IOException {
    BuildTarget resourceTarget = BuildTarget.builder("//foo", "res").build();
    TargetNode<?> resourceNode = AppleResourceBuilder
        .createBuilder(resourceTarget)
        .setFiles(ImmutableSet.<SourcePath>of(new TestSourcePath("bar.png")))
        .setDirs(ImmutableSet.of(Paths.get("foodir")))
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
        .setDirs(ImmutableSet.of(Paths.get("AssetCatalog.xcassets")))
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
        .setFrameworks(
            Optional.of(
                ImmutableSortedSet.of(
                    "$BUILT_PRODUCTS_DIR/libfoo.a",
                    "$SDKROOT/libfoo.a",
                    "$SOURCE_ROOT/libfoo.a")))
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
  }

  @Test(expected = HumanReadableException.class)
  public void shouldRejectUnknownBuildSettingsInFrameworkEntries() throws IOException {
    BuildTarget buildTarget = BuildTarget
        .builder("//foo", "rule")
        .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
        .build();
    TargetNode<?> node = AppleLibraryBuilder
        .createBuilder(buildTarget)
        .setFrameworks(Optional.of(ImmutableSortedSet.of("$FOOBAR/libfoo.a")))
        .build();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<TargetNode<?>>of(node));
    projectGenerator.createXcodeProjects();
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
        ImmutableSet.<Path>of(Paths.get("nonexistent-directory")));

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
        ImmutableSet.<Path>of());

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
        ImmutableSet.<Path>of(Paths.get("bar.png")));

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
        ImmutableSet.<Path>of());

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "foodir specified in the files parameter of //foo:res is not a regular file");

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
        equalTo(PBXTarget.ProductType.STATIC_LIBRARY));
    assertThat(
        libraryTestNotStaticTarget.getProductType(),
        equalTo(PBXTarget.ProductType.UNIT_TEST));
  }

  @Test
  public void testGeneratingCombinedTests() throws IOException {
    TargetNode<AppleResourceDescription.Arg> testLibDepResource =
        AppleResourceBuilder.createBuilder(BuildTarget.builder("//lib", "deplibresource").build())
            .setFiles(ImmutableSet.<SourcePath>of(new TestSourcePath("bar.png")))
            .setDirs(ImmutableSet.<Path>of())
            .build();
    TargetNode<AppleNativeTargetDescriptionArg> testLibDepLib =
        AppleLibraryBuilder.createBuilder(BuildTarget.builder("//libs", "deplib").build())
            .setFrameworks(
                Optional.of(ImmutableSortedSet.of("$SDKROOT/DeclaredInTestLibDep.framework")))
            .setDeps(Optional.of(ImmutableSortedSet.of(testLibDepResource.getBuildTarget())))
            .build();
    TargetNode<AppleNativeTargetDescriptionArg> dep1 =
        AppleLibraryBuilder.createBuilder(BuildTarget.builder("//foo", "dep1").build())
            .setDeps(Optional.of(ImmutableSortedSet.of(testLibDepLib.getBuildTarget())))
            .setFrameworks(
                Optional.of(ImmutableSortedSet.of("$SDKROOT/DeclaredInTestLib.framework")))
            .build();
    TargetNode<AppleNativeTargetDescriptionArg> dep2 =
        AppleLibraryBuilder.createBuilder(BuildTarget.builder("//foo", "dep2").build())
            .build();
    TargetNode<AppleTestDescription.Arg> xctest1 =
        AppleTestBuilder.createBuilder(BuildTarget.builder("//foo", "xctest1").build())
            .setExtension(Either.<AppleBundleExtension, String>ofLeft(AppleBundleExtension.XCTEST))
            .setDeps(Optional.of(ImmutableSortedSet.of(dep1.getBuildTarget())))
            .setFrameworks(
                Optional.of(ImmutableSortedSet.of("$SDKROOT/DeclaredInTest.framework")))
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
        OUTPUT_DIRECTORY,
        PROJECT_NAME,
        "BUCK",
        ProjectGenerator.SEPARATED_PROJECT_OPTIONS)
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
            "$BUILT_PRODUCTS_DIR/F4XWY2LCOM5GIZLQNRUWE/libdeplib.a",
            "$BUILT_PRODUCTS_DIR/F4XWM33PHJSGK4BR/libdep1.a",
            "$BUILT_PRODUCTS_DIR/F4XWM33PHJSGK4BS/libdep2.a",
            "$SDKROOT/DeclaredInTestLib.framework",
            "$SDKROOT/DeclaredInTestLibDep.framework",
            "$SDKROOT/DeclaredInTest.framework"));
    assertHasSingletonResourcesPhaseWithEntries(
        target,
        "bar.png");
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
    ImmutableSet<BuildTarget> initialBuildTargets = FluentIterable
        .from(nodes)
        .transform(HasBuildTarget.TO_TARGET)
        .toSet();

    return new ProjectGenerator(
        TargetGraphFactory.newInstance(ImmutableSet.copyOf(nodes)),
        initialBuildTargets,
        projectFilesystem,
        OUTPUT_DIRECTORY,
        PROJECT_NAME,
        "BUCK",
        projectGeneratorOptions);
  }

  private ImmutableSet<TargetNode<?>> setupSimpleLibraryWithResources(
      ImmutableSet<SourcePath> resourceFiles,
      ImmutableSet<Path> resourceDirectories) {
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
