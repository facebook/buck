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

package com.facebook.buck.apple.project_generator;

import static com.facebook.buck.apple.project_generator.ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.dd.plist.NSDictionary;
import com.dd.plist.NSString;
import com.facebook.buck.apple.AppleAssetCatalogBuilder;
import com.facebook.buck.apple.AppleBinaryBuilder;
import com.facebook.buck.apple.AppleBundleBuilder;
import com.facebook.buck.apple.AppleBundleExtension;
import com.facebook.buck.apple.AppleDependenciesCache;
import com.facebook.buck.apple.AppleLibraryBuilder;
import com.facebook.buck.apple.AppleLibraryDescriptionArg;
import com.facebook.buck.apple.AppleResourceBuilder;
import com.facebook.buck.apple.AppleTestBuilder;
import com.facebook.buck.apple.CoreDataModelBuilder;
import com.facebook.buck.apple.FakeAppleRuleDescriptions;
import com.facebook.buck.apple.SceneKitAssetsBuilder;
import com.facebook.buck.apple.XcodePostbuildScriptBuilder;
import com.facebook.buck.apple.XcodePrebuildScriptBuilder;
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
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLibraryBuilder;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPlatformUtils;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.graph.AbstractBottomUpTraversal;
import com.facebook.buck.halide.HalideBuckConfig;
import com.facebook.buck.halide.HalideLibraryBuilder;
import com.facebook.buck.halide.HalideLibraryDescription;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.js.IosReactNativeLibraryBuilder;
import com.facebook.buck.js.ReactNativeBuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Either;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.DefaultBuildTargetSourcePath;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.facebook.buck.shell.ExportFileBuilder;
import com.facebook.buck.shell.ExportFileDescriptionArg;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.testutil.AllExistingProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.facebook.buck.timing.IncrementingFakeClock;
import com.facebook.buck.timing.SettableFakeClock;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ProjectGeneratorTest {

  private static final Path OUTPUT_DIRECTORY = Paths.get("_gen");
  private static final String PROJECT_NAME = "GeneratedProject";
  private static final String PROJECT_CONTAINER = PROJECT_NAME + ".xcodeproj";
  private static final Path OUTPUT_PROJECT_BUNDLE_PATH =
      OUTPUT_DIRECTORY.resolve(PROJECT_CONTAINER);
  private static final Path OUTPUT_PROJECT_FILE_PATH =
      OUTPUT_PROJECT_BUNDLE_PATH.resolve("project.pbxproj");
  private static final CxxPlatform DEFAULT_PLATFORM = CxxPlatformUtils.DEFAULT_PLATFORM;
  private static final Flavor DEFAULT_FLAVOR = InternalFlavor.of("default");
  private SettableFakeClock clock;
  private ProjectFilesystem projectFilesystem;
  private Cell projectCell;
  private FakeProjectFilesystem fakeProjectFilesystem;
  private HalideBuckConfig halideBuckConfig;
  private CxxBuckConfig cxxBuckConfig;
  private SwiftBuckConfig swiftBuckConfig;

  @Rule public ExpectedException thrown = ExpectedException.none();
  private Path rootPath;

  @Before
  public void setUp() throws InterruptedException, IOException {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);
    clock = new SettableFakeClock(0, 0);
    fakeProjectFilesystem = new FakeProjectFilesystem(clock);
    projectCell = (new TestCellBuilder()).setFilesystem(fakeProjectFilesystem).build();
    projectFilesystem = projectCell.getFilesystem();
    rootPath = projectFilesystem.getRootPath();

    // Add files and directories used to test resources.
    projectFilesystem.createParentDirs(Paths.get("foodir", "foo.png"));
    projectFilesystem.writeContentsToPath("", Paths.get("foodir", "foo.png"));
    projectFilesystem.writeContentsToPath("", Paths.get("bar.png"));
    fakeProjectFilesystem.touch(Paths.get("Base.lproj", "Bar.storyboard"));
    halideBuckConfig = HalideLibraryBuilder.createDefaultHalideConfig(fakeProjectFilesystem);

    ImmutableMap<String, ImmutableMap<String, String>> sections =
        ImmutableMap.of(
            "cxx",
                ImmutableMap.of(
                    "cflags", "-Wno-deprecated -Wno-conversion",
                    "cxxflags", "-Wundeclared-selector -Wno-objc-designated-initializers"),
            "apple", ImmutableMap.of("force_dsym_mode_in_build_with_buck", "false"),
            "swift", ImmutableMap.of("version", "1.23"));
    BuckConfig config = FakeBuckConfig.builder().setSections(sections).build();
    cxxBuckConfig = new CxxBuckConfig(config);
    swiftBuckConfig = new SwiftBuckConfig(config);
  }

  @Test
  public void testProjectStructureForEmptyProject() throws IOException {
    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    Optional<String> pbxproj = projectFilesystem.readFileIfItExists(OUTPUT_PROJECT_FILE_PATH);
    assertTrue(pbxproj.isPresent());
  }

  @Test
  public void testProjectStructureWithInfoPlist() throws IOException {
    BuildTarget libraryTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    BuildTarget bundleTarget = BuildTarget.builder(rootPath, "//foo", "bundle").build();

    TargetNode<?, ?> libraryNode =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setExportedHeaders(ImmutableSortedSet.of(new FakeSourcePath("foo.h")))
            .build();

    TargetNode<?, ?> bundleNode =
        AppleBundleBuilder.createBuilder(bundleTarget)
            .setBinary(libraryTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
            .setInfoPlist(new FakeSourcePath(("Info.plist")))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(libraryNode, bundleNode));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXGroup bundleGroup =
        project.getMainGroup().getOrCreateChildGroupByName(bundleTarget.getFullyQualifiedName());
    PBXGroup sourcesGroup = bundleGroup.getOrCreateChildGroupByName("Sources");

    assertThat(bundleGroup.getChildren(), hasSize(2));

    Iterable<String> childNames =
        Iterables.transform(sourcesGroup.getChildren(), PBXReference::getName);
    assertThat(childNames, hasItem("Info.plist"));
  }

  @Test
  public void testProjectStructureWithGenruleSources() throws IOException {
    BuildTarget libraryTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    BuildTarget bundleTarget = BuildTarget.builder(rootPath, "//foo", "bundle").build();
    BuildTarget genruleTarget = BuildTarget.builder(rootPath, "//foo", "genrule").build();

    TargetNode<?, ?> libraryNode =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setExportedHeaders(ImmutableSortedSet.of(new FakeSourcePath("foo.h")))
            .build();

    TargetNode<?, ?> bundleNode =
        AppleBundleBuilder.createBuilder(bundleTarget)
            .setBinary(libraryTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
            .setInfoPlist(new FakeSourcePath(("Info.plist")))
            .build();

    TargetNode<?, ?> genruleNode =
        GenruleBuilder.newGenruleBuilder(genruleTarget)
            .setSrcs(
                ImmutableList.of(
                    new FakeSourcePath("foo/foo.json"), new FakeSourcePath("bar.json")))
            .setOut("out")
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(libraryNode, bundleNode, genruleNode));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXGroup bundleGroup =
        project.getMainGroup().getOrCreateChildGroupByName(bundleTarget.getFullyQualifiedName());
    PBXGroup sourcesGroup = bundleGroup.getOrCreateChildGroupByName("Sources");

    assertThat(bundleGroup.getChildren(), hasSize(2));

    Iterable<String> childNames =
        Iterables.transform(sourcesGroup.getChildren(), PBXReference::getName);
    assertThat(childNames, hasItem("Info.plist"));

    PBXGroup otherGroup =
        project
            .getMainGroup()
            .getOrCreateChildGroupByName("Other")
            .getOrCreateChildGroupByName("..");
    assertThat(otherGroup.getChildren(), hasSize(2));
    childNames = Iterables.transform(otherGroup.getChildren(), PBXReference::getName);
    assertThat(childNames, hasItem("bar.json"));

    PBXGroup otherFooGroup = otherGroup.getOrCreateChildGroupByName("foo");
    assertThat(otherFooGroup.getChildren(), hasSize(1));
    childNames = Iterables.transform(otherFooGroup.getChildren(), PBXReference::getName);
    assertThat(childNames, hasItem("foo.json"));
  }

  @Test
  public void testProjectStructureWithExtraXcodeFiles() throws IOException {
    BuildTarget libraryTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    BuildTarget bundleTarget = BuildTarget.builder(rootPath, "//foo", "bundle").build();

    TargetNode<?, ?> libraryNode =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setExportedHeaders(ImmutableSortedSet.of(new FakeSourcePath("foo.h")))
            .setExtraXcodeFiles(
                ImmutableList.of(
                    new FakeSourcePath("foo/foo.json"), new FakeSourcePath("bar.json")))
            .build();

    TargetNode<?, ?> bundleNode =
        AppleBundleBuilder.createBuilder(bundleTarget)
            .setBinary(libraryTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
            .setInfoPlist(new FakeSourcePath(("Info.plist")))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(libraryNode, bundleNode));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXGroup bundleGroup =
        project.getMainGroup().getOrCreateChildGroupByName(libraryTarget.getFullyQualifiedName());
    PBXGroup sourcesGroup = bundleGroup.getOrCreateChildGroupByName("Sources");

    Iterable<String> childNames =
        Iterables.transform(sourcesGroup.getChildren(), PBXReference::getName);
    assertThat(childNames, hasItem("bar.json"));

    PBXGroup fooGroup = sourcesGroup.getOrCreateChildGroupByName("foo");
    childNames = Iterables.transform(fooGroup.getChildren(), PBXReference::getName);
    assertThat(childNames, hasItem("foo.json"));

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:lib");
    assertSourcesNotInSourcesPhase(target, ImmutableSet.of("bar.json"));
  }

  @Test
  public void testProjectStructureWithExtraXcodeSources() throws IOException {
    BuildTarget libraryTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    BuildTarget bundleTarget = BuildTarget.builder(rootPath, "//foo", "bundle").build();

    TargetNode<?, ?> libraryNode =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setExportedHeaders(ImmutableSortedSet.of(new FakeSourcePath("foo.h")))
            .setExtraXcodeSources(
                ImmutableList.of(new FakeSourcePath("foo/foo.m"), new FakeSourcePath("bar.m")))
            .build();

    TargetNode<?, ?> bundleNode =
        AppleBundleBuilder.createBuilder(bundleTarget)
            .setBinary(libraryTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
            .setInfoPlist(new FakeSourcePath(("Info.plist")))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(libraryNode, bundleNode));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXGroup bundleGroup =
        project.getMainGroup().getOrCreateChildGroupByName(libraryTarget.getFullyQualifiedName());
    PBXGroup sourcesGroup = bundleGroup.getOrCreateChildGroupByName("Sources");

    Iterable<String> childNames =
        Iterables.transform(sourcesGroup.getChildren(), PBXReference::getName);
    assertThat(childNames, hasItem("bar.m"));

    PBXGroup fooGroup = sourcesGroup.getOrCreateChildGroupByName("foo");
    childNames = Iterables.transform(fooGroup.getChildren(), PBXReference::getName);
    assertThat(childNames, hasItem("foo.m"));

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:lib");
    assertHasSingletonSourcesPhaseWithSourcesAndFlags(
        target,
        ImmutableMap.of(
            "foo/foo.m", Optional.empty(),
            "bar.m", Optional.empty()));
  }

  @Test
  public void testCreateDirectoryStructure() throws IOException {
    BuildTarget buildTarget1 = BuildTarget.builder(rootPath, "//foo/bar", "target1").build();
    TargetNode<?, ?> node1 = AppleLibraryBuilder.createBuilder(buildTarget1).build();

    BuildTarget buildTarget2 = BuildTarget.builder(rootPath, "//foo/foo", "target2").build();
    TargetNode<?, ?> node2 = AppleLibraryBuilder.createBuilder(buildTarget2).build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
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
  public void testModularFrameworkHeaderMapInclusionAsDependency() throws IOException {
    BuildTarget frameworkBundleTarget = BuildTarget.builder(rootPath, "//foo", "framework").build();
    BuildTarget frameworkLibTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    BuildTarget appBundleTarget = BuildTarget.builder(rootPath, "//product", "app").build();
    BuildTarget appBinaryTarget = BuildTarget.builder(rootPath, "//product", "binary").build();

    String configName = "Default";

    TargetNode<?, ?> frameworkLibNode =
        AppleLibraryBuilder.createBuilder(frameworkLibTarget)
            .setSrcs(ImmutableSortedSet.of())
            .setHeaders(
                ImmutableSortedSet.of(
                    new FakeSourcePath("HeaderGroup1/foo.h"),
                    new FakeSourcePath("HeaderGroup2/baz.h")))
            .setExportedHeaders(ImmutableSortedSet.of(new FakeSourcePath("HeaderGroup1/bar.h")))
            .setConfigs(ImmutableSortedMap.of(configName, ImmutableMap.of()))
            .setModular(true)
            .build();

    TargetNode<?, ?> frameworkBundleNode =
        AppleBundleBuilder.createBuilder(frameworkBundleTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(frameworkLibTarget)
            .build();

    TargetNode<?, ?> appBinaryNode =
        AppleLibraryBuilder.createBuilder(appBinaryTarget)
            .setSrcs(ImmutableSortedSet.of())
            .setDeps(ImmutableSortedSet.of(frameworkBundleTarget))
            .setConfigs(ImmutableSortedMap.of(configName, ImmutableMap.of()))
            .build();

    TargetNode<?, ?> appBundleNode =
        AppleBundleBuilder.createBuilder(appBundleTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.APP))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(appBinaryTarget)
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(frameworkLibNode, frameworkBundleNode, appBinaryNode, appBundleNode),
            ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    assertNotNull(project);

    List<Path> headerSymlinkTrees = projectGenerator.getGeneratedHeaderSymlinkTrees();
    assertThat(headerSymlinkTrees, hasSize(8));

    assertTrue(headerSymlinkTrees.contains(Paths.get("buck-out/gen/_p/CwkbTNOBmb-pub")));
    assertThatHeaderMapWithoutSymLinksIsEmpty(Paths.get("buck-out/gen/_p/CwkbTNOBmb-pub"));
  }

  @Test
  public void testModularFrameworkHeaderMapInclusionInTargetItself() throws IOException {
    BuildTarget libTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();

    TargetNode<?, ?> libNode =
        AppleLibraryBuilder.createBuilder(libTarget)
            .setSrcs(ImmutableSortedSet.of())
            .setHeaders(
                ImmutableSortedSet.of(
                    new FakeSourcePath("HeaderGroup1/foo.h"),
                    new FakeSourcePath("HeaderGroup2/baz.h")))
            .setExportedHeaders(ImmutableSortedSet.of(new FakeSourcePath("HeaderGroup1/bar.h")))
            .setConfigs(ImmutableSortedMap.of("Default", ImmutableMap.of()))
            .setModular(true)
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(libNode), ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    assertNotNull(project);

    List<Path> headerSymlinkTrees = projectGenerator.getGeneratedHeaderSymlinkTrees();
    assertThat(headerSymlinkTrees, hasSize(2));

    assertThat(headerSymlinkTrees.get(0).toString(), is(equalTo("buck-out/gen/_p/CwkbTNOBmb-pub")));
    assertThatHeaderMapWithoutSymLinksIsEmpty(Paths.get("buck-out/gen/_p/CwkbTNOBmb-pub"));
  }

  @Test
  public void testModularFrameworkBuildSettings() throws IOException {
    BuildTarget bundleTarget = BuildTarget.builder(rootPath, "//foo", "framework").build();
    BuildTarget libTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();

    String configName = "Default";

    TargetNode<?, ?> libNode =
        AppleLibraryBuilder.createBuilder(libTarget)
            .setSrcs(ImmutableSortedSet.of())
            .setHeaders(
                ImmutableSortedSet.of(
                    new FakeSourcePath("HeaderGroup1/foo.h"),
                    new FakeSourcePath("HeaderGroup2/baz.h")))
            .setExportedHeaders(ImmutableSortedSet.of(new FakeSourcePath("HeaderGroup1/bar.h")))
            .setConfigs(ImmutableSortedMap.of(configName, ImmutableMap.of()))
            .setModular(true)
            .build();

    TargetNode<?, ?> frameworkNode =
        AppleBundleBuilder.createBuilder(bundleTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(libTarget)
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(libNode, frameworkNode), ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXTarget libPBXTarget = assertTargetExistsAndReturnTarget(project, "//foo:lib");

    ImmutableMap<String, String> buildSettings =
        getBuildSettings(bundleTarget, libPBXTarget, configName);

    assertEquals(
        "USE_HEADERMAP must be turned on for modular framework targets "
            + "so that Xcode generates VFS overlays",
        "YES",
        buildSettings.get("USE_HEADERMAP"));
    assertEquals(
        "CLANG_ENABLE_MODULES must be turned on for modular framework targets"
            + "so that Xcode generates VFS overlays",
        "YES",
        buildSettings.get("CLANG_ENABLE_MODULES"));
    assertEquals(
        "DEFINES_MODULE must be turned on for modular framework targets",
        "YES",
        buildSettings.get("DEFINES_MODULE"));
  }

  @Test
  public void testModularFrameworkHeadersInHeadersBuildPhase() throws IOException {
    BuildTarget bundleTarget = BuildTarget.builder(rootPath, "//foo", "framework").build();
    BuildTarget libTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();

    String exportedHeaderName = "bar.h";

    TargetNode<?, ?> libNode =
        AppleLibraryBuilder.createBuilder(libTarget)
            .setSrcs(ImmutableSortedSet.of())
            .setHeaders(
                ImmutableSortedSet.of(
                    new FakeSourcePath("HeaderGroup1/foo.h"),
                    new FakeSourcePath("HeaderGroup2/baz.h")))
            .setExportedHeaders(
                ImmutableSortedSet.of(new FakeSourcePath("HeaderGroup1/" + exportedHeaderName)))
            .setModular(true)
            .build();

    TargetNode<?, ?> frameworkNode =
        AppleBundleBuilder.createBuilder(bundleTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(libTarget)
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(libNode, frameworkNode), ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXTarget target = assertTargetExistsAndReturnTarget(project, "//foo:framework");

    List<PBXBuildPhase> headersPhases = target.getBuildPhases();

    headersPhases.removeIf(input -> !(input instanceof PBXHeadersBuildPhase));
    assertEquals(1, headersPhases.size());

    PBXHeadersBuildPhase headersPhase = (PBXHeadersBuildPhase) headersPhases.get(0);
    List<PBXBuildFile> headers = headersPhase.getFiles();
    assertEquals(1, headers.size());

    PBXFileReference headerReference = (PBXFileReference) headers.get(0).getFileRef();
    assertNotNull(headerReference);
    assertEquals(headerReference.getName(), exportedHeaderName);
  }

  @Test
  public void testAppleLibraryHeaderGroupsWithHeaderSymlinkTrees() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        AppleLibraryBuilder.createBuilder(buildTarget)
            .setSrcs(ImmutableSortedSet.of())
            .setHeaders(
                ImmutableSortedSet.of(
                    new FakeSourcePath("HeaderGroup1/foo.h"),
                    new FakeSourcePath("HeaderGroup2/baz.h")))
            .setExportedHeaders(ImmutableSortedSet.of(new FakeSourcePath("HeaderGroup1/bar.h")))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(node));

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
        0,
        target
            .getBuildPhases()
            .stream()
            .filter(input -> input instanceof PBXHeadersBuildPhase)
            .count());

    List<Path> headerSymlinkTrees = projectGenerator.getGeneratedHeaderSymlinkTrees();
    assertThat(headerSymlinkTrees, hasSize(2));

    assertEquals("buck-out/gen/_p/CwkbTNOBmb-pub", headerSymlinkTrees.get(0).toString());
    assertThatHeaderSymlinkTreeContains(
        Paths.get("buck-out/gen/_p/CwkbTNOBmb-pub"),
        ImmutableMap.of("lib/bar.h", "HeaderGroup1/bar.h"));

    assertEquals("buck-out/gen/_p/CwkbTNOBmb-priv", headerSymlinkTrees.get(1).toString());
    assertThatHeaderSymlinkTreeContains(
        Paths.get("buck-out/gen/_p/CwkbTNOBmb-priv"),
        ImmutableMap.<String, String>builder()
            .put("lib/foo.h", "HeaderGroup1/foo.h")
            .put("lib/baz.h", "HeaderGroup2/baz.h")
            .put("foo.h", "HeaderGroup1/foo.h")
            .put("bar.h", "HeaderGroup1/bar.h")
            .put("baz.h", "HeaderGroup2/baz.h")
            .build());
  }

  @Test
  public void testAppleLibraryHeaderGroupsWithMappedHeaders() throws IOException {
    BuildTarget privateGeneratedTarget =
        BuildTarget.builder(rootPath, "//foo", "generated1.h").build();
    BuildTarget publicGeneratedTarget =
        BuildTarget.builder(rootPath, "//foo", "generated2.h").build();

    TargetNode<?, ?> privateGeneratedNode =
        ExportFileBuilder.newExportFileBuilder(privateGeneratedTarget).build();
    TargetNode<?, ?> publicGeneratedNode =
        ExportFileBuilder.newExportFileBuilder(publicGeneratedTarget).build();

    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        AppleLibraryBuilder.createBuilder(buildTarget)
            .setSrcs(ImmutableSortedSet.of())
            .setHeaders(
                ImmutableSortedMap.of(
                    "any/name.h", new FakeSourcePath("HeaderGroup1/foo.h"),
                    "different/name.h", new FakeSourcePath("HeaderGroup2/baz.h"),
                    "one/more/name.h", new DefaultBuildTargetSourcePath(privateGeneratedTarget)))
            .setExportedHeaders(
                ImmutableSortedMap.of(
                    "yet/another/name.h", new FakeSourcePath("HeaderGroup1/bar.h"),
                    "and/one/more.h", new DefaultBuildTargetSourcePath(publicGeneratedTarget)))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
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
        0,
        target
            .getBuildPhases()
            .stream()
            .filter(input -> input instanceof PBXHeadersBuildPhase)
            .count());

    List<Path> headerSymlinkTrees = projectGenerator.getGeneratedHeaderSymlinkTrees();
    assertThat(headerSymlinkTrees, hasSize(2));

    assertEquals("buck-out/gen/_p/CwkbTNOBmb-pub", headerSymlinkTrees.get(0).toString());
    assertThatHeaderSymlinkTreeContains(
        Paths.get("buck-out/gen/_p/CwkbTNOBmb-pub"),
        ImmutableMap.of(
            "yet/another/name.h", "HeaderGroup1/bar.h",
            "and/one/more.h", "foo/generated2.h"));

    assertEquals("buck-out/gen/_p/CwkbTNOBmb-priv", headerSymlinkTrees.get(1).toString());
    assertThatHeaderSymlinkTreeContains(
        Paths.get("buck-out/gen/_p/CwkbTNOBmb-priv"),
        ImmutableMap.of(
            "any/name.h", "HeaderGroup1/foo.h",
            "different/name.h", "HeaderGroup2/baz.h",
            "one/more/name.h", "foo/generated1.h"));
  }

  @Test
  public void testCxxLibraryWithListsOfHeaders() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        new CxxLibraryBuilder(buildTarget)
            .setExportedHeaders(ImmutableSortedSet.of(new FakeSourcePath("foo/dir1/bar.h")))
            .setHeaders(
                ImmutableSortedSet.of(
                    new FakeSourcePath("foo/dir1/foo.h"), new FakeSourcePath("foo/dir2/baz.h")))
            .setSrcs(ImmutableSortedSet.of())
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(node));

    projectGenerator.createXcodeProjects();

    List<Path> headerSymlinkTrees = projectGenerator.getGeneratedHeaderSymlinkTrees();
    assertThat(headerSymlinkTrees, hasSize(2));

    assertThat(headerSymlinkTrees.get(0).toString(), is(equalTo("buck-out/gen/_p/CwkbTNOBmb-pub")));
    assertThatHeaderSymlinkTreeContains(
        Paths.get("buck-out/gen/_p/CwkbTNOBmb-pub"),
        ImmutableMap.of("foo/dir1/bar.h", "foo/dir1/bar.h"));

    assertThat(
        headerSymlinkTrees.get(1).toString(), is(equalTo("buck-out/gen/_p/CwkbTNOBmb-priv")));
    assertThatHeaderSymlinkTreeContains(
        Paths.get("buck-out/gen/_p/CwkbTNOBmb-priv"),
        ImmutableMap.<String, String>builder()
            .put("foo/dir1/foo.h", "foo/dir1/foo.h")
            .put("foo/dir2/baz.h", "foo/dir2/baz.h")
            .build());
  }

  @Test
  public void testCxxLibraryWithoutHeadersSymLinks() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        new CxxLibraryBuilder(buildTarget)
            .setExportedHeaders(ImmutableSortedSet.of(new FakeSourcePath("foo/dir1/bar.h")))
            .setHeaders(
                ImmutableSortedSet.of(
                    new FakeSourcePath("foo/dir1/foo.h"), new FakeSourcePath("foo/dir2/baz.h")))
            .setSrcs(ImmutableSortedSet.of())
            .setXcodePublicHeadersSymlinks(false)
            .setXcodePrivateHeadersSymlinks(false)
            .build();

    ImmutableSet.Builder<ProjectGenerator.Option> optionsBuilder = ImmutableSet.builder();
    ImmutableSet<ProjectGenerator.Option> projectGeneratorOptions = optionsBuilder.build();
    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(node), projectGeneratorOptions);

    projectGenerator.createXcodeProjects();

    List<Path> headerSymlinkTrees = projectGenerator.getGeneratedHeaderSymlinkTrees();
    assertThat(headerSymlinkTrees, hasSize(2));

    assertThat(headerSymlinkTrees.get(0).toString(), is(equalTo("buck-out/gen/_p/CwkbTNOBmb-pub")));
    assertThatHeaderMapWithoutSymLinksContains(
        Paths.get("buck-out/gen/_p/CwkbTNOBmb-pub"),
        ImmutableMap.of("foo/dir1/bar.h", "foo/dir1/bar.h"));

    assertThat(
        headerSymlinkTrees.get(1).toString(), is(equalTo("buck-out/gen/_p/CwkbTNOBmb-priv")));
    assertThatHeaderMapWithoutSymLinksContains(
        Paths.get("buck-out/gen/_p/CwkbTNOBmb-priv"),
        ImmutableMap.<String, String>builder()
            .put("foo/dir1/foo.h", "foo/dir1/foo.h")
            .put("foo/dir2/baz.h", "foo/dir2/baz.h")
            .build());
  }

  @Test
  public void testCxxLibraryWithListsOfHeadersAndCustomNamespace() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        new CxxLibraryBuilder(buildTarget)
            .setExportedHeaders(ImmutableSortedSet.of(new FakeSourcePath("foo/dir1/bar.h")))
            .setHeaders(
                ImmutableSortedSet.of(
                    new FakeSourcePath("foo/dir1/foo.h"), new FakeSourcePath("foo/dir2/baz.h")))
            .setSrcs(ImmutableSortedSet.of())
            .setHeaderNamespace("name/space")
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(node));

    projectGenerator.createXcodeProjects();

    List<Path> headerSymlinkTrees = projectGenerator.getGeneratedHeaderSymlinkTrees();
    assertThat(headerSymlinkTrees, hasSize(2));

    assertThat(headerSymlinkTrees.get(0).toString(), is(equalTo("buck-out/gen/_p/CwkbTNOBmb-pub")));
    assertThatHeaderSymlinkTreeContains(
        Paths.get("buck-out/gen/_p/CwkbTNOBmb-pub"),
        ImmutableMap.of("name/space/dir1/bar.h", "foo/dir1/bar.h"));

    assertThat(
        headerSymlinkTrees.get(1).toString(), is(equalTo("buck-out/gen/_p/CwkbTNOBmb-priv")));
    assertThatHeaderSymlinkTreeContains(
        Paths.get("buck-out/gen/_p/CwkbTNOBmb-priv"),
        ImmutableMap.<String, String>builder()
            .put("name/space/dir1/foo.h", "foo/dir1/foo.h")
            .put("name/space/dir2/baz.h", "foo/dir2/baz.h")
            .build());
  }

  @Test
  public void testCxxLibraryHeaderGroupsWithMapsOfHeaders() throws IOException {
    BuildTarget privateGeneratedTarget =
        BuildTarget.builder(rootPath, "//foo", "generated1.h").build();
    BuildTarget publicGeneratedTarget =
        BuildTarget.builder(rootPath, "//foo", "generated2.h").build();

    TargetNode<?, ?> privateGeneratedNode =
        ExportFileBuilder.newExportFileBuilder(privateGeneratedTarget).build();
    TargetNode<?, ?> publicGeneratedNode =
        ExportFileBuilder.newExportFileBuilder(publicGeneratedTarget).build();

    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        new CxxLibraryBuilder(buildTarget)
            .setExportedHeaders(
                ImmutableSortedMap.of(
                    "yet/another/name.h", new FakeSourcePath("foo/dir1/bar.h"),
                    "and/one/more.h", new DefaultBuildTargetSourcePath(publicGeneratedTarget)))
            .setHeaders(
                ImmutableSortedMap.of(
                    "any/name.h", new FakeSourcePath("foo/dir1/foo.h"),
                    "different/name.h", new FakeSourcePath("foo/dir2/baz.h"),
                    "one/more/name.h", new DefaultBuildTargetSourcePath(privateGeneratedTarget)))
            .setSrcs(ImmutableSortedSet.of())
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(node, privateGeneratedNode, publicGeneratedNode));

    projectGenerator.createXcodeProjects();

    List<Path> headerSymlinkTrees = projectGenerator.getGeneratedHeaderSymlinkTrees();
    assertThat(headerSymlinkTrees, hasSize(2));

    assertThat(headerSymlinkTrees.get(0).toString(), is(equalTo("buck-out/gen/_p/CwkbTNOBmb-pub")));
    assertThatHeaderSymlinkTreeContains(
        Paths.get("buck-out/gen/_p/CwkbTNOBmb-pub"),
        ImmutableMap.of(
            "foo/yet/another/name.h", "foo/dir1/bar.h",
            "foo/and/one/more.h", "foo/generated2.h"));

    assertThat(
        headerSymlinkTrees.get(1).toString(), is(equalTo("buck-out/gen/_p/CwkbTNOBmb-priv")));
    assertThatHeaderSymlinkTreeContains(
        Paths.get("buck-out/gen/_p/CwkbTNOBmb-priv"),
        ImmutableMap.of(
            "foo/any/name.h", "foo/dir1/foo.h",
            "foo/different/name.h", "foo/dir2/baz.h",
            "foo/one/more/name.h", "foo/generated1.h"));
  }

  @Test
  public void testCxxLibraryHeaderGroupsWithMapsOfHeadersAndCustomNamespace() throws IOException {
    BuildTarget privateGeneratedTarget =
        BuildTarget.builder(rootPath, "//foo", "generated1.h").build();
    BuildTarget publicGeneratedTarget =
        BuildTarget.builder(rootPath, "//foo", "generated2.h").build();

    TargetNode<?, ?> privateGeneratedNode =
        ExportFileBuilder.newExportFileBuilder(privateGeneratedTarget).build();
    TargetNode<?, ?> publicGeneratedNode =
        ExportFileBuilder.newExportFileBuilder(publicGeneratedTarget).build();

    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        new CxxLibraryBuilder(buildTarget)
            .setExportedHeaders(
                ImmutableSortedMap.of(
                    "yet/another/name.h", new FakeSourcePath("foo/dir1/bar.h"),
                    "and/one/more.h", new DefaultBuildTargetSourcePath(publicGeneratedTarget)))
            .setHeaders(
                ImmutableSortedMap.of(
                    "any/name.h", new FakeSourcePath("foo/dir1/foo.h"),
                    "different/name.h", new FakeSourcePath("foo/dir2/baz.h"),
                    "one/more/name.h", new DefaultBuildTargetSourcePath(privateGeneratedTarget)))
            .setSrcs(ImmutableSortedSet.of())
            .setHeaderNamespace("name/space")
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(node, privateGeneratedNode, publicGeneratedNode));

    projectGenerator.createXcodeProjects();

    List<Path> headerSymlinkTrees = projectGenerator.getGeneratedHeaderSymlinkTrees();
    assertThat(headerSymlinkTrees, hasSize(2));

    assertThat(headerSymlinkTrees.get(0).toString(), is(equalTo("buck-out/gen/_p/CwkbTNOBmb-pub")));
    assertThatHeaderSymlinkTreeContains(
        Paths.get("buck-out/gen/_p/CwkbTNOBmb-pub"),
        ImmutableMap.of(
            "name/space/yet/another/name.h", "foo/dir1/bar.h",
            "name/space/and/one/more.h", "foo/generated2.h"));

    assertThat(
        headerSymlinkTrees.get(1).toString(), is(equalTo("buck-out/gen/_p/CwkbTNOBmb-priv")));
    assertThatHeaderSymlinkTreeContains(
        Paths.get("buck-out/gen/_p/CwkbTNOBmb-priv"),
        ImmutableMap.of(
            "name/space/any/name.h", "foo/dir1/foo.h",
            "name/space/different/name.h", "foo/dir2/baz.h",
            "name/space/one/more/name.h", "foo/generated1.h"));
  }

  @Test
  public void testHeaderSymlinkTreesAreRegeneratedWhenKeyChanges() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        AppleLibraryBuilder.createBuilder(buildTarget)
            .setSrcs(ImmutableSortedSet.of())
            .setHeaders(ImmutableSortedMap.of("key.h", new FakeSourcePath("value.h")))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(node));

    projectGenerator.createXcodeProjects();

    List<Path> headerSymlinkTrees = projectGenerator.getGeneratedHeaderSymlinkTrees();
    assertThat(headerSymlinkTrees, hasSize(2));

    assertEquals("buck-out/gen/_p/CwkbTNOBmb-priv", headerSymlinkTrees.get(1).toString());
    assertThatHeaderSymlinkTreeContains(
        Paths.get("buck-out/gen/_p/CwkbTNOBmb-priv"), ImmutableMap.of("key.h", "value.h"));

    node =
        AppleLibraryBuilder.createBuilder(buildTarget)
            .setSrcs(ImmutableSortedSet.of())
            .setHeaders(ImmutableSortedMap.of("new-key.h", new FakeSourcePath("value.h")))
            .build();

    projectGenerator = createProjectGeneratorForCombinedProject(ImmutableSet.of(node));

    projectGenerator.createXcodeProjects();

    headerSymlinkTrees = projectGenerator.getGeneratedHeaderSymlinkTrees();
    assertThat(headerSymlinkTrees, hasSize(2));

    assertEquals("buck-out/gen/_p/CwkbTNOBmb-priv", headerSymlinkTrees.get(1).toString());
    assertFalse(
        projectFilesystem.isSymLink(
            Paths.get("buck-out/gen/foo/lib-private-header-symlink-tree/key.h")));
    assertThatHeaderSymlinkTreeContains(
        Paths.get("buck-out/gen/_p/CwkbTNOBmb-priv"), ImmutableMap.of("new-key.h", "value.h"));
  }

  @Test
  public void testHeaderSymlinkTreesAreRegeneratedWhenValueChanges() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        AppleLibraryBuilder.createBuilder(buildTarget)
            .setSrcs(ImmutableSortedSet.of())
            .setHeaders(ImmutableSortedMap.of("key.h", new FakeSourcePath("value.h")))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(node));

    projectGenerator.createXcodeProjects();

    List<Path> headerSymlinkTrees = projectGenerator.getGeneratedHeaderSymlinkTrees();
    assertThat(headerSymlinkTrees, hasSize(2));

    assertEquals("buck-out/gen/_p/CwkbTNOBmb-priv", headerSymlinkTrees.get(1).toString());
    assertThatHeaderSymlinkTreeContains(
        Paths.get("buck-out/gen/_p/CwkbTNOBmb-priv"), ImmutableMap.of("key.h", "value.h"));

    node =
        AppleLibraryBuilder.createBuilder(buildTarget)
            .setSrcs(ImmutableSortedSet.of())
            .setHeaders(ImmutableSortedMap.of("key.h", new FakeSourcePath("new-value.h")))
            .build();

    projectGenerator = createProjectGeneratorForCombinedProject(ImmutableSet.of(node));

    projectGenerator.createXcodeProjects();

    headerSymlinkTrees = projectGenerator.getGeneratedHeaderSymlinkTrees();
    assertThat(headerSymlinkTrees, hasSize(2));

    assertEquals("buck-out/gen/_p/CwkbTNOBmb-priv", headerSymlinkTrees.get(1).toString());
    assertThatHeaderSymlinkTreeContains(
        Paths.get("buck-out/gen/_p/CwkbTNOBmb-priv"), ImmutableMap.of("key.h", "new-value.h"));
  }

  @Test
  public void testHeaderSymlinkTreesWithHeadersVisibleForTesting() throws IOException {
    BuildTarget libraryTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    BuildTarget testTarget = BuildTarget.builder(rootPath, "//foo", "test").build();

    TargetNode<?, ?> libraryNode =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(new FakeSourcePath("foo.h"), ImmutableList.of("public")),
                    SourceWithFlags.of(new FakeSourcePath("bar.h"))))
            .setTests(ImmutableSortedSet.of(testTarget))
            .build();

    TargetNode<?, ?> testNode =
        AppleTestBuilder.createBuilder(testTarget)
            .setConfigs(ImmutableSortedMap.of("Default", ImmutableMap.of()))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setDeps(ImmutableSortedSet.of(libraryTarget))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(libraryNode, testNode));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXTarget testPBXTarget = assertTargetExistsAndReturnTarget(project, "//foo:test");

    ImmutableMap<String, String> buildSettings =
        getBuildSettings(testTarget, testPBXTarget, "Default");

    assertEquals(
        "test binary should use header symlink trees for both public and non-public headers "
            + "of the tested library in HEADER_SEARCH_PATHS",
        "$(inherited) "
            + "../buck-out/gen/_p/LpygK8zq5F-priv/.hmap "
            + "../buck-out/gen/_p/LpygK8zq5F-pub/.hmap "
            + "../buck-out/gen/_p/CwkbTNOBmb-pub/.hmap "
            + "../buck-out/gen/_p/CwkbTNOBmb-priv/.hmap "
            + "../buck-out",
        buildSettings.get("HEADER_SEARCH_PATHS"));
    assertEquals(
        "USER_HEADER_SEARCH_PATHS should not be set",
        null,
        buildSettings.get("USER_HEADER_SEARCH_PATHS"));
  }

  @Test
  public void testHeaderSymlinkTreesWithTestsAndLibraryBundles() throws IOException {
    BuildTarget libraryTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    BuildTarget bundleTarget = BuildTarget.builder(rootPath, "//foo", "bundle").build();
    BuildTarget testTarget = BuildTarget.builder(rootPath, "//foo", "test").build();

    TargetNode<?, ?> libraryNode =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(new FakeSourcePath("foo.h"), ImmutableList.of("public")),
                    SourceWithFlags.of(new FakeSourcePath("bar.h"))))
            .build();

    TargetNode<?, ?> bundleNode =
        AppleBundleBuilder.createBuilder(bundleTarget)
            .setBinary(libraryTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setTests(ImmutableSortedSet.of(testTarget))
            .build();

    TargetNode<?, ?> testNode =
        AppleTestBuilder.createBuilder(testTarget)
            .setConfigs(ImmutableSortedMap.of("Default", ImmutableMap.of()))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setDeps(ImmutableSortedSet.of(bundleTarget))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(libraryNode, bundleNode, testNode));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXTarget testPBXTarget = assertTargetExistsAndReturnTarget(project, "//foo:test");

    ImmutableMap<String, String> buildSettings =
        getBuildSettings(testTarget, testPBXTarget, "Default");

    assertEquals(
        "test binary should use header symlink trees for both public and non-public headers "
            + "of the tested library in HEADER_SEARCH_PATHS",
        "$(inherited) "
            + "../buck-out/gen/_p/LpygK8zq5F-priv/.hmap "
            + "../buck-out/gen/_p/LpygK8zq5F-pub/.hmap "
            + "../buck-out/gen/_p/CwkbTNOBmb-pub/.hmap "
            + "../buck-out/gen/_p/CwkbTNOBmb-priv/.hmap "
            + "../buck-out",
        buildSettings.get("HEADER_SEARCH_PATHS"));
    assertEquals(
        "USER_HEADER_SEARCH_PATHS should not be set",
        null,
        buildSettings.get("USER_HEADER_SEARCH_PATHS"));
  }

  @Test
  public void testHeaderSymlinkTreesWithTestsAndBinaryBundles() throws IOException {
    BuildTarget binaryTarget = BuildTarget.builder(rootPath, "//foo", "bin").build();
    BuildTarget bundleTarget = BuildTarget.builder(rootPath, "//foo", "bundle").build();
    BuildTarget testTarget = BuildTarget.builder(rootPath, "//foo", "test").build();

    TargetNode<?, ?> binaryNode =
        AppleBinaryBuilder.createBuilder(binaryTarget)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(new FakeSourcePath("foo.h"), ImmutableList.of("public")),
                    SourceWithFlags.of(new FakeSourcePath("bar.h"))))
            .build();

    TargetNode<?, ?> bundleNode =
        AppleBundleBuilder.createBuilder(bundleTarget)
            .setBinary(binaryTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.APP))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setTests(ImmutableSortedSet.of(testTarget))
            .build();

    TargetNode<?, ?> testNode =
        AppleTestBuilder.createBuilder(testTarget)
            .setConfigs(ImmutableSortedMap.of("Default", ImmutableMap.of()))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setDeps(ImmutableSortedSet.of(bundleTarget))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(binaryNode, bundleNode, testNode));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXTarget testPBXTarget = assertTargetExistsAndReturnTarget(project, "//foo:test");

    ImmutableMap<String, String> buildSettings =
        getBuildSettings(testTarget, testPBXTarget, "Default");

    assertEquals(
        "test binary should use header symlink trees for both public and non-public headers "
            + "of the tested binary in HEADER_SEARCH_PATHS",
        "$(inherited) "
            + "../buck-out/gen/_p/LpygK8zq5F-priv/.hmap "
            + "../buck-out/gen/_p/LpygK8zq5F-pub/.hmap "
            + "../buck-out/gen/_p/4UdYl649ee-pub/.hmap "
            + "../buck-out/gen/_p/4UdYl649ee-priv/.hmap "
            + "../buck-out",
        buildSettings.get("HEADER_SEARCH_PATHS"));
    assertEquals(
        "USER_HEADER_SEARCH_PATHS should not be set",
        null,
        buildSettings.get("USER_HEADER_SEARCH_PATHS"));
  }

  @Test
  public void testGenerateOnlyHeaderSymlinkTrees() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        new CxxLibraryBuilder(buildTarget)
            .setExportedHeaders(ImmutableSortedSet.of(new FakeSourcePath("foo/dir1/bar.h")))
            .setHeaders(
                ImmutableSortedSet.of(
                    new FakeSourcePath("foo/dir1/foo.h"), new FakeSourcePath("foo/dir2/baz.h")))
            .setSrcs(ImmutableSortedSet.of())
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(node),
            ImmutableSet.of(ProjectGenerator.Option.GENERATE_HEADERS_SYMLINK_TREES_ONLY));
    projectGenerator.createXcodeProjects();

    // The project should not generated since we're generating only header symlink trees.
    assertFalse(
        projectGenerator.getProjectPath() + " should not be generated.",
        projectFilesystem.isDirectory(projectGenerator.getProjectPath()));

    List<Path> headerSymlinkTrees = projectGenerator.getGeneratedHeaderSymlinkTrees();
    assertThat(headerSymlinkTrees, hasSize(2));

    assertThat(headerSymlinkTrees.get(0).toString(), is(equalTo("buck-out/gen/_p/CwkbTNOBmb-pub")));
    assertThatHeaderSymlinkTreeContains(
        Paths.get("buck-out/gen/_p/CwkbTNOBmb-pub"),
        ImmutableMap.of("foo/dir1/bar.h", "foo/dir1/bar.h"));

    assertThat(
        headerSymlinkTrees.get(1).toString(), is(equalTo("buck-out/gen/_p/CwkbTNOBmb-priv")));
    assertThatHeaderSymlinkTreeContains(
        Paths.get("buck-out/gen/_p/CwkbTNOBmb-priv"),
        ImmutableMap.<String, String>builder()
            .put("foo/dir1/foo.h", "foo/dir1/foo.h")
            .put("foo/dir2/baz.h", "foo/dir2/baz.h")
            .build());
  }

  private void assertThatHeaderSymlinkTreeContains(Path root, ImmutableMap<String, String> content)
      throws IOException {
    // Read the tree's header map.
    byte[] headerMapBytes;
    try (InputStream headerMapInputStream =
        projectFilesystem.newFileInputStream(root.resolve(".hmap"))) {
      headerMapBytes = ByteStreams.toByteArray(headerMapInputStream);
    }
    HeaderMap headerMap = HeaderMap.deserialize(headerMapBytes);
    assertNotNull(headerMap);
    assertThat(headerMap.getNumEntries(), equalTo(content.size()));
    for (Map.Entry<String, String> entry : content.entrySet()) {
      String key = entry.getKey();
      Path link = root.resolve(Paths.get(key));
      Path target = Paths.get(entry.getValue()).toAbsolutePath();
      // Check the filesystem symlink
      assertTrue(projectFilesystem.isSymLink(link));
      assertEquals(target, projectFilesystem.readSymLink(link));

      // Check the header map
      assertThat(
          headerMap.lookup(key),
          equalTo(
              Paths.get("../../")
                  .resolve(projectCell.getRoot().getFileName())
                  .resolve(link)
                  .toString()));
    }
  }

  private void assertThatHeaderMapWithoutSymLinksIsEmpty(Path root) throws IOException {
    // Read the tree's header map.
    byte[] headerMapBytes;
    try (InputStream headerMapInputStream =
        projectFilesystem.newFileInputStream(root.resolve(".hmap"))) {
      headerMapBytes = ByteStreams.toByteArray(headerMapInputStream);
    }
    HeaderMap headerMap = HeaderMap.deserialize(headerMapBytes);
    assertNotNull(headerMap);
    assertEquals(headerMap.getNumEntries(), 0);
  }

  private void assertThatHeaderMapWithoutSymLinksContains(
      Path root, ImmutableMap<String, String> content) throws IOException {
    // Read the tree's header map.
    byte[] headerMapBytes;
    try (InputStream headerMapInputStream =
        projectFilesystem.newFileInputStream(root.resolve(".hmap"))) {
      headerMapBytes = ByteStreams.toByteArray(headerMapInputStream);
    }
    HeaderMap headerMap = HeaderMap.deserialize(headerMapBytes);
    assertNotNull(headerMap);
    assertThat(headerMap.getNumEntries(), equalTo(content.size()));
    for (Map.Entry<String, String> entry : content.entrySet()) {
      String key = entry.getKey();
      Path target = Paths.get(entry.getValue()).toAbsolutePath();
      // Check the header map
      assertThat(headerMap.lookup(key), equalTo(target.toString()));
    }
  }

  @Test
  public void testAppleLibraryRule() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        AppleLibraryBuilder.createBuilder(buildTarget)
            .setConfigs(ImmutableSortedMap.of("Debug", ImmutableMap.of()))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(new FakeSourcePath("foo.m"), ImmutableList.of("-foo")),
                    SourceWithFlags.of(new FakeSourcePath("bar.m"))))
            .setExtraXcodeSources(ImmutableList.of(new FakeSourcePath("libsomething.a")))
            .setHeaders(ImmutableSortedSet.of(new FakeSourcePath("foo.h")))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(node));

    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:lib");
    assertThat(target.isa(), equalTo("PBXNativeTarget"));
    assertThat(target.getProductType(), equalTo(ProductType.STATIC_LIBRARY));

    assertHasConfigurations(target, "Debug");
    assertEquals("Should have exact number of build phases", 1, target.getBuildPhases().size());
    assertHasSingletonSourcesPhaseWithSourcesAndFlags(
        target,
        ImmutableMap.of(
            "foo.m", Optional.of("-foo"),
            "bar.m", Optional.empty(),
            "libsomething.a", Optional.empty()));

    // this target should not have an asset catalog build phase
    assertTrue(
        FluentIterable.from(target.getBuildPhases())
            .filter(PBXResourcesBuildPhase.class)
            .isEmpty());
  }

  @Test
  public void testHalideLibraryRule() throws IOException {
    BuildTarget compilerTarget =
        BuildTarget.builder(rootPath, "//foo", "lib")
            .addFlavors(HalideLibraryDescription.HALIDE_COMPILER_FLAVOR)
            .build();
    TargetNode<?, ?> compiler =
        new HalideLibraryBuilder(compilerTarget)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(new FakeSourcePath("main.cpp")),
                    SourceWithFlags.of(new FakeSourcePath("filter.cpp"))))
            .build();

    BuildTarget libTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> lib = new HalideLibraryBuilder(libTarget).build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(compiler, lib));
    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:lib");
    assertThat(target.isa(), equalTo("PBXNativeTarget"));
    assertHasConfigurations(target, "Debug", "Release", "Profile");
    assertEquals(1, target.getBuildPhases().size());
    PBXShellScriptBuildPhase scriptPhase =
        ProjectGeneratorTestUtils.getSingletonPhaseByType(target, PBXShellScriptBuildPhase.class);
    assertEquals(0, scriptPhase.getInputPaths().size());
    assertEquals(0, scriptPhase.getOutputPaths().size());

    // Note that we require that both the Halide "compiler" and the unflavored
    // library target are present in the requiredBuildTargets, so that both the
    // compiler and the generated header for the pipeline will be available for
    // use by the Xcode compilation step.
    ImmutableSet<BuildTarget> requiredBuildTargets = projectGenerator.getRequiredBuildTargets();
    assertTrue(requiredBuildTargets.contains(compilerTarget));
    assertThat(
        requiredBuildTargets,
        hasItem(
            libTarget.withFlavors(
                HalideLibraryDescription.HALIDE_COMPILE_FLAVOR, DEFAULT_PLATFORM.getFlavor())));
  }

  @Test
  public void testCxxLibraryRule() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();

    TargetNode<?, ?> cxxNode =
        new CxxLibraryBuilder(buildTarget)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(new FakeSourcePath("foo.cpp"), ImmutableList.of("-foo")),
                    SourceWithFlags.of(new FakeSourcePath("bar.cpp"))))
            .setHeaders(ImmutableSortedSet.of(new FakeSourcePath("foo.h")))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(cxxNode));

    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:lib");
    assertThat(target.isa(), equalTo("PBXNativeTarget"));
    assertThat(target.getProductType(), equalTo(ProductType.STATIC_LIBRARY));

    assertHasConfigurations(target, "Debug", "Release", "Profile");
    assertEquals("Should have exact number of build phases", 1, target.getBuildPhases().size());
    assertHasSingletonSourcesPhaseWithSourcesAndFlags(
        target,
        ImmutableMap.of(
            "foo.cpp", Optional.of("-foo"),
            "bar.cpp", Optional.empty()));
  }

  @Test
  public void testAppleLibraryConfiguresOutputPaths() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        AppleLibraryBuilder.createBuilder(buildTarget)
            .setConfigs(ImmutableSortedMap.of("Debug", ImmutableMap.of()))
            .setHeaderPathPrefix(Optional.of("MyHeaderPathPrefix"))
            .setPrefixHeader(Optional.of(new FakeSourcePath("Foo/Foo-Prefix.pch")))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(node), ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:lib");
    assertThat(target.isa(), equalTo("PBXNativeTarget"));
    assertThat(target.getProductType(), equalTo(ProductType.STATIC_LIBRARY));

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals("../Foo/Foo-Prefix.pch", settings.get("GCC_PREFIX_HEADER"));
    assertEquals(
        "$SYMROOT/$CONFIGURATION$EFFECTIVE_PLATFORM_NAME", settings.get("BUILT_PRODUCTS_DIR"));
    assertEquals("$BUILT_PRODUCTS_DIR", settings.get("CONFIGURATION_BUILD_DIR"));
  }

  @Test
  public void testAppleLibraryConfiguresSharedLibraryOutputPaths() throws IOException {
    BuildTarget buildTarget =
        BuildTarget.builder(rootPath, "//hi", "lib")
            .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
            .build();
    TargetNode<?, ?> node =
        AppleLibraryBuilder.createBuilder(buildTarget)
            .setConfigs(ImmutableSortedMap.of("Debug", ImmutableMap.of()))
            .setHeaderPathPrefix(Optional.of("MyHeaderPathPrefix"))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(node), ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(
            projectGenerator.getGeneratedProject(), "//hi:lib#shared");
    assertThat(target.isa(), equalTo("PBXNativeTarget"));
    assertThat(target.getProductType(), equalTo(ProductType.DYNAMIC_LIBRARY));

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals(
        "$SYMROOT/$CONFIGURATION$EFFECTIVE_PLATFORM_NAME", settings.get("BUILT_PRODUCTS_DIR"));
    assertEquals("$BUILT_PRODUCTS_DIR", settings.get("CONFIGURATION_BUILD_DIR"));
  }

  @Test
  public void testAppleLibraryDoesntOverrideHeaderOutputPath() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        AppleLibraryBuilder.createBuilder(buildTarget)
            .setConfigs(
                ImmutableSortedMap.of(
                    "Debug", ImmutableMap.of("PUBLIC_HEADERS_FOLDER_PATH", "FooHeaders")))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(node), ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:lib");
    assertThat(target.isa(), equalTo("PBXNativeTarget"));
    assertThat(target.getProductType(), equalTo(ProductType.STATIC_LIBRARY));

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals(
        "$SYMROOT/$CONFIGURATION$EFFECTIVE_PLATFORM_NAME", settings.get("BUILT_PRODUCTS_DIR"));
    assertEquals("$BUILT_PRODUCTS_DIR", settings.get("CONFIGURATION_BUILD_DIR"));
    assertEquals("FooHeaders", settings.get("PUBLIC_HEADERS_FOLDER_PATH"));
  }

  @Test
  public void testAppleLibraryCxxCFlags() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        AppleLibraryBuilder.createBuilder(buildTarget)
            .setConfigs(ImmutableSortedMap.of("Debug", ImmutableMap.of()))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(node), ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:lib");

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals("$(inherited) -Wno-deprecated -Wno-conversion", settings.get("OTHER_CFLAGS"));
    assertEquals(
        "$(inherited) -Wundeclared-selector -Wno-objc-designated-initializers",
        settings.get("OTHER_CPLUSPLUSFLAGS"));
  }

  @Test
  public void testAppleLibraryCompilerAndPreprocessorFlags() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        AppleLibraryBuilder.createBuilder(buildTarget)
            .setConfigs(ImmutableSortedMap.of("Debug", ImmutableMap.of()))
            .setCompilerFlags(ImmutableList.of("-fhello"))
            .setPreprocessorFlags(ImmutableList.of("-fworld"))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(node), ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:lib");

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals(
        "$(inherited) -Wno-deprecated -Wno-conversion -fhello -fworld",
        settings.get("OTHER_CFLAGS"));
  }

  @Test
  public void testAppleLibraryCompilerAndPreprocessorFlagsDontPropagate() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        AppleLibraryBuilder.createBuilder(buildTarget)
            .setConfigs(ImmutableSortedMap.of("Debug", ImmutableMap.of()))
            .setCompilerFlags(ImmutableList.of("-fhello"))
            .setPreprocessorFlags(ImmutableList.of("-fworld"))
            .build();

    BuildTarget dependentBuildTarget = BuildTarget.builder(rootPath, "//foo", "bin").build();
    TargetNode<?, ?> dependentNode =
        AppleBinaryBuilder.createBuilder(dependentBuildTarget)
            .setConfigs(ImmutableSortedMap.of("Debug", ImmutableMap.of()))
            .setDeps(ImmutableSortedSet.of(buildTarget))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(node, dependentNode), ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:bin");

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals("$(inherited) -Wno-deprecated -Wno-conversion", settings.get("OTHER_CFLAGS"));
  }

  @Test
  public void testAppleLibraryExportedPreprocessorFlags() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        AppleLibraryBuilder.createBuilder(buildTarget)
            .setConfigs(ImmutableSortedMap.of("Debug", ImmutableMap.of()))
            .setExportedPreprocessorFlags(ImmutableList.of("-DHELLO"))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(node), ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:lib");

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals(
        "$(inherited) -Wno-deprecated -Wno-conversion -DHELLO", settings.get("OTHER_CFLAGS"));
  }

  @Test
  public void testAppleLibraryExportedPreprocessorFlagsPropagate() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        AppleLibraryBuilder.createBuilder(buildTarget)
            .setConfigs(ImmutableSortedMap.of("Debug", ImmutableMap.of()))
            .setExportedPreprocessorFlags(ImmutableList.of("-DHELLO"))
            .build();

    BuildTarget dependentBuildTarget = BuildTarget.builder(rootPath, "//foo", "bin").build();
    TargetNode<?, ?> dependentNode =
        AppleBinaryBuilder.createBuilder(dependentBuildTarget)
            .setConfigs(ImmutableSortedMap.of("Debug", ImmutableMap.of()))
            .setDeps(ImmutableSortedSet.of(buildTarget))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(node, dependentNode), ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:bin");

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals(
        "$(inherited) -Wno-deprecated -Wno-conversion -DHELLO", settings.get("OTHER_CFLAGS"));
  }

  @Test
  public void testAppleLibraryLinkerFlags() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        AppleLibraryBuilder.createBuilder(buildTarget)
            .setConfigs(ImmutableSortedMap.of("Debug", ImmutableMap.of()))
            .setLinkerFlags(
                ImmutableList.of(
                    StringWithMacrosUtils.format("-Xlinker"),
                    StringWithMacrosUtils.format("-lhello")))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(node), ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:lib");

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals("$(inherited) -Xlinker -lhello", settings.get("OTHER_LDFLAGS"));
  }

  @Test
  public void testAppleLibraryLinkerFlagsDontPropagate() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        AppleLibraryBuilder.createBuilder(buildTarget)
            .setConfigs(ImmutableSortedMap.of("Debug", ImmutableMap.of()))
            .setLinkerFlags(ImmutableList.of(StringWithMacrosUtils.format("-lhello")))
            .build();

    BuildTarget dependentBuildTarget = BuildTarget.builder(rootPath, "//foo", "bin").build();
    TargetNode<?, ?> dependentNode =
        AppleBinaryBuilder.createBuilder(dependentBuildTarget)
            .setConfigs(ImmutableSortedMap.of("Debug", ImmutableMap.of()))
            .setDeps(ImmutableSortedSet.of(buildTarget))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(node, dependentNode), ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:bin");

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals("$(inherited) ", settings.get("OTHER_LDFLAGS"));
  }

  @Test
  public void testAppleLibraryExportedLinkerFlags() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        AppleLibraryBuilder.createBuilder(buildTarget)
            .setConfigs(ImmutableSortedMap.of("Debug", ImmutableMap.of()))
            .setExportedLinkerFlags(
                ImmutableList.of(
                    StringWithMacrosUtils.format("-Xlinker"),
                    StringWithMacrosUtils.format("-lhello")))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(node), ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:lib");

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals("$(inherited) -Xlinker -lhello", settings.get("OTHER_LDFLAGS"));
  }

  @Test
  public void testAppleLibraryExportedLinkerFlagsPropagate() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        AppleLibraryBuilder.createBuilder(buildTarget)
            .setConfigs(ImmutableSortedMap.of("Debug", ImmutableMap.of()))
            .setExportedLinkerFlags(
                ImmutableList.of(
                    StringWithMacrosUtils.format("-Xlinker"),
                    StringWithMacrosUtils.format("-lhello")))
            .build();

    BuildTarget dependentBuildTarget = BuildTarget.builder(rootPath, "//foo", "bin").build();
    TargetNode<?, ?> dependentNode =
        AppleBinaryBuilder.createBuilder(dependentBuildTarget)
            .setConfigs(ImmutableSortedMap.of("Debug", ImmutableMap.of()))
            .setDeps(ImmutableSortedSet.of(buildTarget))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(node, dependentNode), ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:bin");

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals("$(inherited) -Xlinker -lhello", settings.get("OTHER_LDFLAGS"));
  }

  @Test
  public void testCxxLibraryCompilerAndPreprocessorFlags() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        new CxxLibraryBuilder(buildTarget)
            .setCompilerFlags(ImmutableList.of("-ffoo"))
            .setPreprocessorFlags(ImmutableList.of("-fbar"))
            .setLinkerFlags(ImmutableList.of(StringWithMacrosUtils.format("-lbaz")))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(node), ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:lib");
    assertHasConfigurations(target, "Debug", "Release", "Profile");
    ImmutableMap<String, String> settings =
        ProjectGeneratorTestUtils.getBuildSettings(projectFilesystem, buildTarget, target, "Debug");

    assertEquals(
        "-Wno-deprecated -Wno-conversion -ffoo -fbar "
            + "-Wno-deprecated -Wno-conversion -ffoo -fbar",
        settings.get("OTHER_CFLAGS"));
    assertEquals(
        "-Wundeclared-selector -Wno-objc-designated-initializers -ffoo -fbar "
            + "-Wundeclared-selector -Wno-objc-designated-initializers -ffoo -fbar",
        settings.get("OTHER_CPLUSPLUSFLAGS"));
    assertEquals("-lbaz -lbaz", settings.get("OTHER_LDFLAGS"));
  }

  @Test
  public void testCxxLibraryPlatformFlags() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        new CxxLibraryBuilder(buildTarget)
            .setPlatformCompilerFlags(
                PatternMatchedCollection.<ImmutableList<String>>builder()
                    .add(Pattern.compile("android.*"), ImmutableList.of("-ffoo-android"))
                    .add(Pattern.compile("iphone.*"), ImmutableList.of("-ffoo-iphone"))
                    .add(Pattern.compile("macosx.*"), ImmutableList.of("-ffoo-macosx"))
                    .build())
            .setPlatformPreprocessorFlags(
                PatternMatchedCollection.<ImmutableList<String>>builder()
                    .add(Pattern.compile("android.*"), ImmutableList.of("-fbar-android"))
                    .add(Pattern.compile("iphone.*"), ImmutableList.of("-fbar-iphone"))
                    .add(Pattern.compile("macosx.*"), ImmutableList.of("-fbar-macosx"))
                    .build())
            .setPlatformLinkerFlags(
                PatternMatchedCollection.<ImmutableList<StringWithMacros>>builder()
                    .add(
                        Pattern.compile("android.*"),
                        ImmutableList.of(StringWithMacrosUtils.format("-lbaz-android")))
                    .add(
                        Pattern.compile("iphone.*"),
                        ImmutableList.of(StringWithMacrosUtils.format("-lbaz-iphone")))
                    .add(
                        Pattern.compile("macosx.*"),
                        ImmutableList.of(StringWithMacrosUtils.format("-lbaz-macosx")))
                    .build())
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(node), ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:lib");
    assertHasConfigurations(target, "Debug", "Release", "Profile");
    ImmutableMap<String, String> settings =
        ProjectGeneratorTestUtils.getBuildSettings(projectFilesystem, buildTarget, target, "Debug");

    assertEquals(
        "-Wno-deprecated -Wno-conversion -Wno-deprecated -Wno-conversion",
        settings.get("OTHER_CFLAGS"));
    assertEquals(
        "-Wundeclared-selector -Wno-objc-designated-initializers "
            + "-Wundeclared-selector -Wno-objc-designated-initializers",
        settings.get("OTHER_CPLUSPLUSFLAGS"));
    assertEquals("$(inherited) ", settings.get("OTHER_LDFLAGS"));

    assertEquals(
        "-Wno-deprecated -Wno-conversion -ffoo-iphone -fbar-iphone "
            + "-Wno-deprecated -Wno-conversion -ffoo-iphone -fbar-iphone",
        settings.get("OTHER_CFLAGS[sdk=*iphone*]"));
    assertEquals(
        "-Wundeclared-selector -Wno-objc-designated-initializers -ffoo-iphone -fbar-iphone "
            + "-Wundeclared-selector -Wno-objc-designated-initializers -ffoo-iphone -fbar-iphone",
        settings.get("OTHER_CPLUSPLUSFLAGS[sdk=*iphone*]"));
    assertEquals("-lbaz-iphone -lbaz-iphone", settings.get("OTHER_LDFLAGS[sdk=*iphone*]"));
  }

  @Test
  public void testCxxLibraryExportedPreprocessorFlags() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        new CxxLibraryBuilder(buildTarget)
            .setExportedPreprocessorFlags(ImmutableList.of("-DHELLO"))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(node), ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:lib");

    assertHasConfigurations(target, "Debug", "Release", "Profile");
    ImmutableMap<String, String> settings =
        ProjectGeneratorTestUtils.getBuildSettings(projectFilesystem, buildTarget, target, "Debug");
    assertEquals(
        "-Wno-deprecated -Wno-conversion -DHELLO -Wno-deprecated -Wno-conversion -DHELLO",
        settings.get("OTHER_CFLAGS"));
  }

  @Test
  public void testCxxLibraryExportedPreprocessorFlagsPropagate() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        new CxxLibraryBuilder(buildTarget)
            .setExportedPreprocessorFlags(ImmutableList.of("-DHELLO"))
            .build();

    BuildTarget dependentBuildTarget = BuildTarget.builder(rootPath, "//foo", "bin").build();
    TargetNode<?, ?> dependentNode =
        AppleBinaryBuilder.createBuilder(dependentBuildTarget)
            .setConfigs(ImmutableSortedMap.of("Debug", ImmutableMap.of()))
            .setPreprocessorFlags(ImmutableList.of("-D__APPLE__"))
            .setDeps(ImmutableSortedSet.of(buildTarget))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(node, dependentNode), ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:bin");

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals(
        "$(inherited) -Wno-deprecated -Wno-conversion -DHELLO -D__APPLE__",
        settings.get("OTHER_CFLAGS"));
  }

  @Test
  public void testCxxLibraryExportedPlatformFlags() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        new CxxLibraryBuilder(buildTarget)
            .setExportedPlatformPreprocessorFlags(
                PatternMatchedCollection.<ImmutableList<String>>builder()
                    .add(Pattern.compile("iphone.*"), ImmutableList.of("-fbar-iphone"))
                    .build())
            .setExportedPlatformLinkerFlags(
                PatternMatchedCollection.<ImmutableList<StringWithMacros>>builder()
                    .add(
                        Pattern.compile("macosx.*"),
                        ImmutableList.of(StringWithMacrosUtils.format("-lbaz-macosx")))
                    .build())
            .build();

    BuildTarget dependentBuildTarget = BuildTarget.builder(rootPath, "//foo", "bin").build();
    TargetNode<?, ?> dependentNode =
        AppleBinaryBuilder.createBuilder(dependentBuildTarget)
            .setPlatformCompilerFlags(
                PatternMatchedCollection.<ImmutableList<String>>builder()
                    .add(Pattern.compile("iphone.*"), ImmutableList.of("-ffoo-iphone"))
                    .build())
            .setDeps(ImmutableSortedSet.of(buildTarget))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(node, dependentNode), ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:lib");
    assertHasConfigurations(target, "Debug", "Release", "Profile");
    ImmutableMap<String, String> settings =
        ProjectGeneratorTestUtils.getBuildSettings(projectFilesystem, buildTarget, target, "Debug");

    assertEquals(
        "-Wno-deprecated -Wno-conversion -fbar-iphone -Wno-deprecated -Wno-conversion -fbar-iphone",
        settings.get("OTHER_CFLAGS[sdk=*iphone*]"));
    assertEquals(
        "-Wundeclared-selector -Wno-objc-designated-initializers -fbar-iphone "
            + "-Wundeclared-selector -Wno-objc-designated-initializers -fbar-iphone",
        settings.get("OTHER_CPLUSPLUSFLAGS[sdk=*iphone*]"));
    assertEquals(null, settings.get("OTHER_LDFLAGS[sdk=*iphone*]"));

    PBXTarget dependentTarget =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:bin");
    assertHasConfigurations(target, "Debug", "Release", "Profile");
    ImmutableMap<String, String> dependentSettings =
        ProjectGeneratorTestUtils.getBuildSettings(
            projectFilesystem, dependentBuildTarget, dependentTarget, "Debug");

    assertEquals(
        "-Wno-deprecated -Wno-conversion -ffoo-iphone -fbar-iphone "
            + "-Wno-deprecated -Wno-conversion -ffoo-iphone -fbar-iphone",
        dependentSettings.get("OTHER_CFLAGS[sdk=*iphone*]"));
    assertEquals(
        "-Wundeclared-selector -Wno-objc-designated-initializers -ffoo-iphone -fbar-iphone "
            + "-Wundeclared-selector -Wno-objc-designated-initializers -ffoo-iphone -fbar-iphone",
        dependentSettings.get("OTHER_CPLUSPLUSFLAGS[sdk=*iphone*]"));
    assertEquals(null, dependentSettings.get("OTHER_LDFLAGS[sdk=*iphone*]"));
  }

  @Test
  public void testConfigurationSerializationWithoutExistingXcconfig() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        AppleLibraryBuilder.createBuilder(buildTarget)
            .setConfigs(ImmutableSortedMap.of("Debug", ImmutableMap.of("CUSTOM_SETTING", "VALUE")))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(node), ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:lib");
    assertThat(target.isa(), equalTo("PBXNativeTarget"));
    assertThat(target.getProductType(), equalTo(ProductType.STATIC_LIBRARY));

    assertHasConfigurations(target, "Debug");
    assertKeepsConfigurationsInMainGroup(projectGenerator.getGeneratedProject(), target);
    XCBuildConfiguration configuration =
        target.getBuildConfigurationList().getBuildConfigurationsByName().asMap().get("Debug");
    assertEquals(configuration.getBuildSettings().count(), 0);

    PBXFileReference xcconfigReference = configuration.getBaseConfigurationReference();
    assertEquals(xcconfigReference.getPath(), "../buck-out/gen/foo/lib-Debug.xcconfig");

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals(
        "$SYMROOT/$CONFIGURATION$EFFECTIVE_PLATFORM_NAME", settings.get("BUILT_PRODUCTS_DIR"));
    assertEquals("$BUILT_PRODUCTS_DIR", settings.get("CONFIGURATION_BUILD_DIR"));
    assertEquals("VALUE", settings.get("CUSTOM_SETTING"));
  }

  @Test
  public void testAppleLibraryDependentsSearchHeadersAndLibraries() throws IOException {
    ImmutableSortedMap<String, ImmutableMap<String, String>> configs =
        ImmutableSortedMap.of("Debug", ImmutableMap.<String, String>of());

    BuildTarget libraryTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> libraryNode =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setConfigs(configs)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("foo.m"))))
            .setFrameworks(
                ImmutableSortedSet.of(
                    FrameworkPath.ofSourceTreePath(
                        new SourceTreePath(
                            PBXReference.SourceTree.SDKROOT,
                            Paths.get("Library.framework"),
                            Optional.empty()))))
            .build();

    BuildTarget testTarget = BuildTarget.builder(rootPath, "//foo", "xctest").build();
    TargetNode<?, ?> testNode =
        AppleTestBuilder.createBuilder(testTarget)
            .setConfigs(configs)
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("fooTest.m"))))
            .setFrameworks(
                ImmutableSortedSet.of(
                    FrameworkPath.ofSourceTreePath(
                        new SourceTreePath(
                            PBXReference.SourceTree.SDKROOT,
                            Paths.get("Test.framework"),
                            Optional.empty()))))
            .setDeps(ImmutableSortedSet.of(libraryTarget))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(libraryNode, testNode), ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:xctest");

    ImmutableMap<String, String> settings = getBuildSettings(testTarget, target, "Debug");
    assertEquals(
        "$(inherited) "
            + "../buck-out/gen/_p/ptQfVNNRRE-priv/.hmap "
            + "../buck-out/gen/_p/ptQfVNNRRE-pub/.hmap "
            + "../buck-out/gen/_p/CwkbTNOBmb-pub/.hmap "
            + "../buck-out",
        settings.get("HEADER_SEARCH_PATHS"));
    assertEquals(null, settings.get("USER_HEADER_SEARCH_PATHS"));
    assertEquals("$(inherited) $BUILT_PRODUCTS_DIR", settings.get("LIBRARY_SEARCH_PATHS"));
    assertEquals(
        "$(inherited) $BUILT_PRODUCTS_DIR $SDKROOT", settings.get("FRAMEWORK_SEARCH_PATHS"));
  }

  @Test
  public void testAppleLibraryDependentsInheritSearchPaths() throws IOException {
    ImmutableSortedMap<String, ImmutableMap<String, String>> configs =
        ImmutableSortedMap.of(
            "Debug",
            ImmutableMap.of(
                "HEADER_SEARCH_PATHS", "headers",
                "USER_HEADER_SEARCH_PATHS", "user_headers",
                "LIBRARY_SEARCH_PATHS", "libraries",
                "FRAMEWORK_SEARCH_PATHS", "frameworks"));

    BuildTarget libraryTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> libraryNode =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setConfigs(configs)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("foo.m"))))
            .setFrameworks(
                ImmutableSortedSet.of(
                    FrameworkPath.ofSourceTreePath(
                        new SourceTreePath(
                            PBXReference.SourceTree.SDKROOT,
                            Paths.get("Library.framework"),
                            Optional.empty()))))
            .build();

    BuildTarget testTarget = BuildTarget.builder(rootPath, "//foo", "xctest").build();
    TargetNode<?, ?> testNode =
        AppleTestBuilder.createBuilder(testTarget)
            .setConfigs(configs)
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("fooTest.m"))))
            .setFrameworks(
                ImmutableSortedSet.of(
                    FrameworkPath.ofSourceTreePath(
                        new SourceTreePath(
                            PBXReference.SourceTree.SDKROOT,
                            Paths.get("Test.framework"),
                            Optional.empty()))))
            .setDeps(ImmutableSortedSet.of(libraryTarget))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(libraryNode, testNode), ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:xctest");

    ImmutableMap<String, String> settings = getBuildSettings(testTarget, target, "Debug");
    assertEquals(
        "headers "
            + "../buck-out/gen/_p/ptQfVNNRRE-priv/.hmap "
            + "../buck-out/gen/_p/ptQfVNNRRE-pub/.hmap "
            + "../buck-out/gen/_p/CwkbTNOBmb-pub/.hmap "
            + "../buck-out",
        settings.get("HEADER_SEARCH_PATHS"));
    assertEquals("user_headers", settings.get("USER_HEADER_SEARCH_PATHS"));
    assertEquals("libraries $BUILT_PRODUCTS_DIR", settings.get("LIBRARY_SEARCH_PATHS"));
    assertEquals("frameworks $BUILT_PRODUCTS_DIR $SDKROOT", settings.get("FRAMEWORK_SEARCH_PATHS"));
  }

  @Test
  public void testAppleLibraryTransitiveDependentsSearchHeadersAndLibraries() throws IOException {
    ImmutableSortedMap<String, ImmutableMap<String, String>> configs =
        ImmutableSortedMap.of("Debug", ImmutableMap.<String, String>of());

    BuildTarget libraryDepTarget = BuildTarget.builder(rootPath, "//bar", "lib").build();
    TargetNode<?, ?> libraryDepNode =
        AppleLibraryBuilder.createBuilder(libraryDepTarget)
            .setConfigs(configs)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("foo.m"))))
            .setFrameworks(
                ImmutableSortedSet.of(
                    FrameworkPath.ofSourceTreePath(
                        new SourceTreePath(
                            PBXReference.SourceTree.SDKROOT,
                            Paths.get("Library.framework"),
                            Optional.empty()))))
            .build();

    BuildTarget libraryTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> libraryNode =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setConfigs(configs)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("foo.m"))))
            .setFrameworks(
                ImmutableSortedSet.of(
                    FrameworkPath.ofSourceTreePath(
                        new SourceTreePath(
                            PBXReference.SourceTree.SDKROOT,
                            Paths.get("Library.framework"),
                            Optional.empty()))))
            .setDeps(ImmutableSortedSet.of(libraryDepTarget))
            .build();

    BuildTarget testTarget = BuildTarget.builder(rootPath, "//foo", "xctest").build();
    TargetNode<?, ?> testNode =
        AppleTestBuilder.createBuilder(testTarget)
            .setConfigs(configs)
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("fooTest.m"))))
            .setFrameworks(
                ImmutableSortedSet.of(
                    FrameworkPath.ofSourceTreePath(
                        new SourceTreePath(
                            PBXReference.SourceTree.SDKROOT,
                            Paths.get("Test.framework"),
                            Optional.empty()))))
            .setDeps(ImmutableSortedSet.of(libraryTarget))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(libraryDepNode, libraryNode, testNode), ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:xctest");

    ImmutableMap<String, String> settings = getBuildSettings(testTarget, target, "Debug");
    assertEquals(
        "$(inherited) "
            + "../buck-out/gen/_p/ptQfVNNRRE-priv/.hmap "
            + "../buck-out/gen/_p/ptQfVNNRRE-pub/.hmap "
            + "../buck-out/gen/_p/zAW4E7kxsV-pub/.hmap "
            + "../buck-out/gen/_p/CwkbTNOBmb-pub/.hmap "
            + "../buck-out",
        settings.get("HEADER_SEARCH_PATHS"));
    assertEquals(null, settings.get("USER_HEADER_SEARCH_PATHS"));
    assertEquals("$(inherited) " + "$BUILT_PRODUCTS_DIR", settings.get("LIBRARY_SEARCH_PATHS"));
    assertEquals(
        "$(inherited) " + "$BUILT_PRODUCTS_DIR $SDKROOT", settings.get("FRAMEWORK_SEARCH_PATHS"));
  }

  @Test
  public void testAppleLibraryWithoutSources() throws IOException {
    BuildTarget libraryTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> libraryNode =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setFrameworks(
                ImmutableSortedSet.of(
                    FrameworkPath.ofSourceTreePath(
                        new SourceTreePath(
                            PBXReference.SourceTree.SDKROOT,
                            Paths.get("Library.framework"),
                            Optional.empty()))))
            .build();

    BuildTarget testTarget = BuildTarget.builder(rootPath, "//foo", "xctest").build();
    TargetNode<?, ?> testNode =
        AppleTestBuilder.createBuilder(testTarget)
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("fooTest.m"))))
            .setDeps(ImmutableSortedSet.of(libraryTarget))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(libraryNode, testNode), ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:xctest");

    assertEquals("Should have exact number of build phases", 2, target.getBuildPhases().size());

    assertHasSingletonSourcesPhaseWithSourcesAndFlags(
        target, ImmutableMap.of("fooTest.m", Optional.empty()));

    ProjectGeneratorTestUtils.assertHasSingletonFrameworksPhaseWithFrameworkEntries(
        target, ImmutableList.of("$SDKROOT/Library.framework"));
  }

  @Test
  public void testAppleLibraryWithoutSourcesWithHeaders() throws IOException {
    ImmutableSortedMap<String, ImmutableMap<String, String>> configs =
        ImmutableSortedMap.of(
            "Debug",
            ImmutableMap.of(
                "HEADER_SEARCH_PATHS", "headers",
                "LIBRARY_SEARCH_PATHS", "libraries"));

    BuildTarget libraryTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> libraryNode =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setConfigs(configs)
            .setExportedHeaders(ImmutableSortedSet.of(new FakeSourcePath("HeaderGroup1/bar.h")))
            .setFrameworks(
                ImmutableSortedSet.of(
                    FrameworkPath.ofSourceTreePath(
                        new SourceTreePath(
                            PBXReference.SourceTree.SDKROOT,
                            Paths.get("Library.framework"),
                            Optional.empty()))))
            .build();

    BuildTarget testTarget = BuildTarget.builder(rootPath, "//foo", "xctest").build();
    TargetNode<?, ?> testNode =
        AppleTestBuilder.createBuilder(testTarget)
            .setConfigs(configs)
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("fooTest.m"))))
            .setDeps(ImmutableSortedSet.of(libraryTarget))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(libraryNode, testNode), ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:xctest");

    ImmutableMap<String, String> settings = getBuildSettings(testTarget, target, "Debug");
    assertEquals(
        "headers "
            + "../buck-out/gen/_p/ptQfVNNRRE-priv/.hmap "
            + "../buck-out/gen/_p/ptQfVNNRRE-pub/.hmap "
            + "../buck-out/gen/_p/CwkbTNOBmb-pub/.hmap "
            + "../buck-out",
        settings.get("HEADER_SEARCH_PATHS"));
    assertEquals("libraries $BUILT_PRODUCTS_DIR", settings.get("LIBRARY_SEARCH_PATHS"));

    assertEquals("Should have exact number of build phases", 2, target.getBuildPhases().size());

    assertHasSingletonSourcesPhaseWithSourcesAndFlags(
        target, ImmutableMap.of("fooTest.m", Optional.empty()));

    ProjectGeneratorTestUtils.assertHasSingletonFrameworksPhaseWithFrameworkEntries(
        target, ImmutableList.of("$SDKROOT/Library.framework"));
  }

  @Test
  public void testAppleTestRule() throws IOException {
    BuildTarget testTarget = BuildTarget.builder(rootPath, "//foo", "xctest").build();
    TargetNode<?, ?> testNode =
        AppleTestBuilder.createBuilder(testTarget)
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(testNode));
    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:xctest");
    assertEquals(target.getProductType(), ProductType.UNIT_TEST);
    assertThat(target.isa(), equalTo("PBXNativeTarget"));
    PBXFileReference productReference = target.getProductReference();
    assertEquals("xctest.xctest", productReference.getName());
  }

  @Test
  public void testAppleBinaryRule() throws IOException {
    BuildTarget depTarget = BuildTarget.builder(rootPath, "//dep", "dep").build();
    TargetNode<?, ?> depNode =
        AppleLibraryBuilder.createBuilder(depTarget)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("e.m"))))
            .build();

    BuildTarget binaryTarget = BuildTarget.builder(rootPath, "//foo", "binary").build();
    TargetNode<?, ?> binaryNode =
        AppleBinaryBuilder.createBuilder(binaryTarget)
            .setConfigs(ImmutableSortedMap.of("Debug", ImmutableMap.of()))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(new FakeSourcePath("foo.m"), ImmutableList.of("-foo"))))
            .setExtraXcodeSources(ImmutableList.of(new FakeSourcePath("libsomething.a")))
            .setHeaders(ImmutableSortedSet.of(new FakeSourcePath("foo.h")))
            .setFrameworks(
                ImmutableSortedSet.of(
                    FrameworkPath.ofSourceTreePath(
                        new SourceTreePath(
                            PBXReference.SourceTree.SDKROOT,
                            Paths.get("Foo.framework"),
                            Optional.empty()))))
            .setDeps(ImmutableSortedSet.of(depTarget))
            .setHeaderPathPrefix(Optional.empty())
            .setPrefixHeader(Optional.empty())
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(depNode, binaryNode));
    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:binary");
    assertHasConfigurations(target, "Debug");
    assertEquals(target.getProductType(), ProductType.TOOL);
    assertEquals("Should have exact number of build phases", 2, target.getBuildPhases().size());
    assertHasSingletonSourcesPhaseWithSourcesAndFlags(
        target,
        ImmutableMap.of(
            "foo.m", Optional.of("-foo"),
            "libsomething.a", Optional.empty()));
    ProjectGeneratorTestUtils.assertHasSingletonFrameworksPhaseWithFrameworkEntries(
        target,
        ImmutableList.of(
            "$SDKROOT/Foo.framework",
            // Propagated library from deps.
            "$BUILT_PRODUCTS_DIR/libdep.a"));

    // this test does not have a dependency on any asset catalogs, so verify no build phase for them
    // exists.
    assertTrue(
        FluentIterable.from(target.getBuildPhases())
            .filter(PBXResourcesBuildPhase.class)
            .isEmpty());
  }

  @Test
  public void testAppleBundleRuleWithPreBuildScriptDependency() throws IOException {
    BuildTarget scriptTarget =
        BuildTarget.builder(rootPath, "//foo", "pre_build_script")
            .addFlavors(DEFAULT_FLAVOR)
            .build();
    TargetNode<?, ?> scriptNode =
        XcodePrebuildScriptBuilder.createBuilder(scriptTarget).setCmd("script.sh").build();

    BuildTarget resourceTarget = BuildTarget.builder(rootPath, "//foo", "resource").build();
    TargetNode<?, ?> resourceNode =
        AppleResourceBuilder.createBuilder(resourceTarget)
            .setFiles(ImmutableSet.of(new FakeSourcePath("bar.png")))
            .setDirs(ImmutableSet.of())
            .build();

    BuildTarget sharedLibraryTarget =
        BuildTarget.builder(rootPath, "//dep", "shared")
            .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
            .build();
    TargetNode<?, ?> sharedLibraryNode =
        AppleLibraryBuilder.createBuilder(sharedLibraryTarget)
            .setDeps(ImmutableSortedSet.of(resourceTarget))
            .build();

    BuildTarget bundleTarget = BuildTarget.builder(rootPath, "//foo", "bundle").build();
    TargetNode<?, ?> bundleNode =
        AppleBundleBuilder.createBuilder(bundleTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.BUNDLE))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(sharedLibraryTarget)
            .setDeps(ImmutableSortedSet.of(scriptTarget))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(scriptNode, resourceNode, sharedLibraryNode, bundleNode));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXTarget target = assertTargetExistsAndReturnTarget(project, "//foo:bundle");
    assertThat(target.getName(), equalTo("//foo:bundle"));
    assertThat(target.isa(), equalTo("PBXNativeTarget"));

    PBXShellScriptBuildPhase shellScriptBuildPhase =
        ProjectGeneratorTestUtils.getSingletonPhaseByType(target, PBXShellScriptBuildPhase.class);

    assertThat(shellScriptBuildPhase.getShellScript(), equalTo("script.sh"));

    // Assert that the pre-build script phase comes before resources are copied.
    assertThat(target.getBuildPhases().get(0), instanceOf(PBXShellScriptBuildPhase.class));

    assertThat(target.getBuildPhases().get(1), instanceOf(PBXResourcesBuildPhase.class));
  }

  @Test
  public void testAppleBundleRuleWithPostBuildScriptDependency() throws IOException {
    BuildTarget scriptTarget =
        BuildTarget.builder(rootPath, "//foo", "post_build_script")
            .addFlavors(DEFAULT_FLAVOR)
            .build();
    TargetNode<?, ?> scriptNode =
        XcodePostbuildScriptBuilder.createBuilder(scriptTarget).setCmd("script.sh").build();

    BuildTarget resourceTarget = BuildTarget.builder(rootPath, "//foo", "resource").build();
    TargetNode<?, ?> resourceNode =
        AppleResourceBuilder.createBuilder(resourceTarget)
            .setFiles(ImmutableSet.of(new FakeSourcePath("bar.png")))
            .setDirs(ImmutableSet.of())
            .build();

    BuildTarget sharedLibraryTarget =
        BuildTarget.builder(rootPath, "//dep", "shared")
            .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
            .build();
    TargetNode<?, ?> sharedLibraryNode =
        AppleLibraryBuilder.createBuilder(sharedLibraryTarget)
            .setDeps(ImmutableSortedSet.of(resourceTarget))
            .build();

    BuildTarget bundleTarget = BuildTarget.builder(rootPath, "//foo", "bundle").build();
    TargetNode<?, ?> bundleNode =
        AppleBundleBuilder.createBuilder(bundleTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.BUNDLE))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(sharedLibraryTarget)
            .setDeps(ImmutableSortedSet.of(scriptTarget))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(scriptNode, resourceNode, sharedLibraryNode, bundleNode));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXTarget target = assertTargetExistsAndReturnTarget(project, "//foo:bundle");
    assertThat(target.getName(), equalTo("//foo:bundle"));
    assertThat(target.isa(), equalTo("PBXNativeTarget"));

    PBXShellScriptBuildPhase shellScriptBuildPhase =
        ProjectGeneratorTestUtils.getSingletonPhaseByType(target, PBXShellScriptBuildPhase.class);

    assertThat(shellScriptBuildPhase.getShellScript(), equalTo("script.sh"));

    // Assert that the post-build script phase comes after resources are copied.
    assertThat(target.getBuildPhases().get(0), instanceOf(PBXResourcesBuildPhase.class));

    assertThat(target.getBuildPhases().get(1), instanceOf(PBXShellScriptBuildPhase.class));
  }

  @Test
  public void testAppleBundleRuleWithRNLibraryDependency() throws IOException {
    BuildTarget rnLibraryTarget =
        BuildTarget.builder(rootPath, "//foo", "rn_library").addFlavors(DEFAULT_FLAVOR).build();
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    ReactNativeBuckConfig buckConfig =
        new ReactNativeBuckConfig(
            FakeBuckConfig.builder()
                .setSections(
                    ImmutableMap.of(
                        "react-native",
                        ImmutableMap.of("packager_worker", "react-native/packager.sh")))
                .setFilesystem(filesystem)
                .build());
    TargetNode<?, ?> rnLibraryNode =
        IosReactNativeLibraryBuilder.builder(rnLibraryTarget, buckConfig)
            .setBundleName("Apps/Foo/FooBundle.js")
            .setEntryPath(new PathSourcePath(filesystem, Paths.get("js/FooApp.js")))
            .build();

    BuildTarget sharedLibraryTarget =
        BuildTarget.builder(rootPath, "//dep", "shared")
            .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
            .build();
    TargetNode<?, ?> sharedLibraryNode =
        AppleLibraryBuilder.createBuilder(sharedLibraryTarget).build();

    BuildTarget bundleTarget = BuildTarget.builder(rootPath, "//foo", "bundle").build();
    TargetNode<?, ?> bundleNode =
        AppleBundleBuilder.createBuilder(bundleTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.BUNDLE))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(sharedLibraryTarget)
            .setDeps(ImmutableSortedSet.of(rnLibraryTarget))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(rnLibraryNode, sharedLibraryNode, bundleNode));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXTarget target = assertTargetExistsAndReturnTarget(project, "//foo:bundle");
    assertThat(target.getName(), equalTo("//foo:bundle"));
    assertThat(target.isa(), equalTo("PBXNativeTarget"));

    PBXShellScriptBuildPhase shellScriptBuildPhase =
        ProjectGeneratorTestUtils.getSingletonPhaseByType(target, PBXShellScriptBuildPhase.class);

    assertThat(shellScriptBuildPhase.getShellScript(), startsWith("BASE_DIR="));
  }

  @Test
  public void testAppleBundleRuleForSharedLibraryFramework() throws IOException {
    BuildTarget sharedLibraryTarget =
        BuildTarget.builder(rootPath, "//dep", "shared")
            .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
            .build();
    TargetNode<?, ?> sharedLibraryNode =
        AppleLibraryBuilder.createBuilder(sharedLibraryTarget)
            .setConfigs(ImmutableSortedMap.of("Debug", ImmutableMap.of()))
            .build();

    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "bundle").build();
    TargetNode<?, ?> node =
        AppleBundleBuilder.createBuilder(buildTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(sharedLibraryTarget)
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(sharedLibraryNode, node), ImmutableSet.of());
    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXTarget target = assertTargetExistsAndReturnTarget(project, "//foo:bundle");
    assertEquals(target.getProductType(), ProductType.FRAMEWORK);
    assertThat(target.isa(), equalTo("PBXNativeTarget"));
    PBXFileReference productReference = target.getProductReference();
    assertEquals("bundle.framework", productReference.getName());
    assertEquals(Optional.of("wrapper.framework"), productReference.getExplicitFileType());

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals("framework", settings.get("WRAPPER_EXTENSION"));
  }

  @Test
  public void testAppleResourceWithVariantGroupSetsFileTypeBasedOnPath() throws IOException {
    BuildTarget resourceTarget =
        BuildTarget.builder(rootPath, "//foo", "resource").addFlavors(DEFAULT_FLAVOR).build();
    TargetNode<?, ?> resourceNode =
        AppleResourceBuilder.createBuilder(resourceTarget)
            .setFiles(ImmutableSet.of())
            .setDirs(ImmutableSet.of())
            .setVariants(ImmutableSet.of(new FakeSourcePath("Base.lproj/Bar.storyboard")))
            .build();
    BuildTarget fooLibraryTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> fooLibraryNode =
        AppleLibraryBuilder.createBuilder(fooLibraryTarget)
            .setDeps(ImmutableSortedSet.of(resourceTarget))
            .build();
    BuildTarget bundleTarget = BuildTarget.builder(rootPath, "//foo", "bundle").build();
    TargetNode<?, ?> bundleNode =
        AppleBundleBuilder.createBuilder(bundleTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.BUNDLE))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(fooLibraryTarget)
            .setDeps(ImmutableSortedSet.of(resourceTarget))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(fooLibraryNode, bundleNode, resourceNode), ImmutableSet.of());
    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXGroup targetGroup =
        project.getMainGroup().getOrCreateChildGroupByName(bundleTarget.getFullyQualifiedName());
    PBXGroup resourcesGroup = targetGroup.getOrCreateChildGroupByName("Resources");
    PBXVariantGroup storyboardGroup =
        (PBXVariantGroup) Iterables.get(resourcesGroup.getChildren(), 0);
    List<PBXReference> storyboardGroupChildren = storyboardGroup.getChildren();
    assertEquals(1, storyboardGroupChildren.size());
    assertTrue(storyboardGroupChildren.get(0) instanceof PBXFileReference);
    PBXFileReference baseStoryboardReference = (PBXFileReference) storyboardGroupChildren.get(0);

    assertEquals("Base", baseStoryboardReference.getName());

    // Make sure the file type is set from the path.
    assertEquals(Optional.of("file.storyboard"), baseStoryboardReference.getLastKnownFileType());
    assertEquals(Optional.empty(), baseStoryboardReference.getExplicitFileType());
  }

  @Test
  public void testAppleBundleRuleWithCustomXcodeProductType() throws IOException {
    BuildTarget sharedLibraryTarget =
        BuildTarget.builder(rootPath, "//dep", "shared")
            .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
            .build();
    TargetNode<?, ?> sharedLibraryNode =
        AppleLibraryBuilder.createBuilder(sharedLibraryTarget)
            .setConfigs(ImmutableSortedMap.of("Debug", ImmutableMap.of()))
            .build();

    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "custombundle").build();
    TargetNode<?, ?> node =
        AppleBundleBuilder.createBuilder(buildTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(sharedLibraryTarget)
            .setXcodeProductType(Optional.of("com.facebook.buck.niftyProductType"))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(sharedLibraryNode, node), ImmutableSet.of());
    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXTarget target = assertTargetExistsAndReturnTarget(project, "//foo:custombundle");
    assertEquals(target.getProductType(), ProductType.of("com.facebook.buck.niftyProductType"));
    assertThat(target.isa(), equalTo("PBXNativeTarget"));
    PBXFileReference productReference = target.getProductReference();
    assertEquals("custombundle.framework", productReference.getName());
    assertEquals(Optional.of("wrapper.framework"), productReference.getExplicitFileType());

    ImmutableMap<String, String> settings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals("framework", settings.get("WRAPPER_EXTENSION"));
  }

  @Test
  public void testAppleBundleRuleWithCustomXcodeProductNameFromConfigs() throws IOException {
    BuildTarget sharedLibraryTarget =
        BuildTarget.builder(rootPath, "//dep", "shared")
            .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
            .build();
    TargetNode<?, ?> sharedLibraryNode =
        AppleLibraryBuilder.createBuilder(sharedLibraryTarget)
            .setConfigs(
                ImmutableSortedMap.of("Debug", ImmutableMap.of("PRODUCT_NAME", "FancyFramework")))
            .build();

    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "custombundle").build();
    TargetNode<?, ?> node =
        AppleBundleBuilder.createBuilder(buildTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(sharedLibraryTarget)
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(sharedLibraryNode, node), ImmutableSet.of());
    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXTarget target = assertTargetExistsAndReturnTarget(project, "//foo:custombundle");

    ImmutableMap<String, String> buildSettings = getBuildSettings(buildTarget, target, "Debug");
    assertThat(buildSettings.get("PRODUCT_NAME"), Matchers.equalTo("FancyFramework"));
  }

  private void testRuleAddsReference(BuildTarget ruleTarget, TargetNode<?, ?> ruleNode, String path)
      throws IOException {
    BuildTarget libraryTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> libraryNode =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setDeps(ImmutableSortedSet.of(ruleTarget))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(ruleNode, libraryNode));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXGroup targetGroup =
        project.getMainGroup().getOrCreateChildGroupByName(libraryTarget.getFullyQualifiedName());
    PBXGroup resourcesGroup = targetGroup.getOrCreateChildGroupByName("Resources");

    assertThat(resourcesGroup.getChildren(), hasSize(1));

    PBXFileReference ruleReference =
        (PBXFileReference) Iterables.get(resourcesGroup.getChildren(), 0);
    assertEquals(path, ruleReference.getName());
  }

  @Test
  public void testCoreDataModelRuleAddsReference() throws IOException {
    BuildTarget modelTarget = BuildTarget.builder(rootPath, "//foo", "model").build();
    TargetNode<?, ?> modelNode =
        CoreDataModelBuilder.createBuilder(modelTarget)
            .setPath(new FakeSourcePath("foo.xcdatamodel").getRelativePath())
            .build();
    testRuleAddsReference(modelTarget, modelNode, "foo.xcdatamodel");
  }

  @Test
  public void testSceneKitAssetsRuleAddsReference() throws IOException {
    BuildTarget target = BuildTarget.builder(rootPath, "//foo", "scenekitasset").build();
    TargetNode<?, ?> node =
        SceneKitAssetsBuilder.createBuilder(target)
            .setPath(new FakeSourcePath("foo.scnassets").getRelativePath())
            .build();
    testRuleAddsReference(target, node, "foo.scnassets");
  }

  @Test
  public void testCodeSignEntitlementsAddsReference() throws IOException {
    BuildTarget libraryTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    BuildTarget bundleTarget = BuildTarget.builder(rootPath, "//foo", "bundle").build();

    TargetNode<?, ?> libraryNode =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setExportedHeaders(ImmutableSortedSet.of(new FakeSourcePath("foo.h")))
            .build();

    TargetNode<?, ?> bundleNode =
        AppleBundleBuilder.createBuilder(bundleTarget)
            .setBinary(libraryTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.APP))
            .setInfoPlist(new FakeSourcePath(("Info.plist")))
            .setInfoPlistSubstitutions(ImmutableMap.of("CODE_SIGN_ENTITLEMENTS", "foo.plist"))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(libraryNode, bundleNode));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXGroup targetGroup =
        project.getMainGroup().getOrCreateChildGroupByName(bundleTarget.getFullyQualifiedName());

    assertThat(targetGroup.getChildren(), hasSize(3));

    PBXFileReference ruleReference = (PBXFileReference) Iterables.get(targetGroup.getChildren(), 2);
    assertEquals("foo.plist", ruleReference.getName());
  }

  @Test
  public void testAppleWatchTarget() throws IOException {
    BuildTarget watchAppBinaryTarget =
        BuildTarget.builder(rootPath, "//foo", "WatchAppBinary").build();
    TargetNode<?, ?> watchAppBinaryNode =
        AppleBinaryBuilder.createBuilder(watchAppBinaryTarget).build();

    BuildTarget watchAppTarget =
        BuildTarget.builder(rootPath, "//foo", "WatchApp").addFlavors(DEFAULT_FLAVOR).build();
    TargetNode<?, ?> watchAppNode =
        AppleBundleBuilder.createBuilder(watchAppTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.APP))
            .setXcodeProductType(Optional.of("com.apple.product-type.application.watchapp2"))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(watchAppBinaryTarget)
            .build();

    BuildTarget hostAppBinaryTarget =
        BuildTarget.builder(rootPath, "//foo", "HostAppBinary").build();
    TargetNode<?, ?> hostAppBinaryNode =
        AppleBinaryBuilder.createBuilder(hostAppBinaryTarget).build();

    BuildTarget hostAppTarget = BuildTarget.builder(rootPath, "//foo", "HostApp").build();
    TargetNode<?, ?> hostAppNode =
        AppleBundleBuilder.createBuilder(hostAppTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.APP))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(hostAppBinaryTarget)
            .setDeps(ImmutableSortedSet.of(watchAppTarget))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(watchAppNode, watchAppBinaryNode, hostAppNode, hostAppBinaryNode));
    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:HostApp");
    assertEquals(target.getProductType(), ProductType.APPLICATION);

    ProjectGeneratorTestUtils.assertHasSingletonCopyFilesPhaseWithFileEntries(
        target, ImmutableList.of("$BUILT_PRODUCTS_DIR/WatchApp.app"));

    PBXCopyFilesBuildPhase copyBuildPhase =
        ProjectGeneratorTestUtils.getSingletonPhaseByType(target, PBXCopyFilesBuildPhase.class);
    assertEquals(
        copyBuildPhase.getDstSubfolderSpec(),
        CopyFilePhaseDestinationSpec.builder()
            .setDestination(PBXCopyFilesBuildPhase.Destination.PRODUCTS)
            .setPath("$(CONTENTS_FOLDER_PATH)/Watch")
            .build());
  }

  @Test
  public void ruleToTargetMapContainsPBXTarget() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        AppleLibraryBuilder.createBuilder(buildTarget)
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(new FakeSourcePath("foo.m"), ImmutableList.of("-foo")),
                    SourceWithFlags.of(new FakeSourcePath("bar.m"))))
            .setHeaders(ImmutableSortedSet.of(new FakeSourcePath("foo.h")))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(node));

    projectGenerator.createXcodeProjects();

    assertEquals(
        buildTarget,
        Iterables.getOnlyElement(projectGenerator.getBuildTargetToGeneratedTargetMap().keySet()));

    PBXTarget target =
        Iterables.getOnlyElement(projectGenerator.getBuildTargetToGeneratedTargetMap().values());
    assertHasSingletonSourcesPhaseWithSourcesAndFlags(
        target,
        ImmutableMap.of(
            "foo.m", Optional.of("-foo"),
            "bar.m", Optional.empty()));
  }

  @Test
  public void generatedGidsForTargetsAreStable() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "foo").build();
    TargetNode<?, ?> node = AppleLibraryBuilder.createBuilder(buildTarget).build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(node));
    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:foo");
    String expectedGID =
        String.format("%08X%08X%08X", target.isa().hashCode(), target.getName().hashCode(), 0);
    assertEquals(
        "expected GID has correct value (value from which it's derived have not changed)",
        "E66DC04E2245423200000000",
        expectedGID);
    assertEquals("generated GID is same as expected", expectedGID, target.getGlobalID());
  }

  @Test
  public void stopsLinkingRecursiveDependenciesAtSharedLibraries() throws IOException {
    BuildTarget dependentStaticLibraryTarget =
        BuildTarget.builder(rootPath, "//dep", "static").build();
    TargetNode<?, ?> dependentStaticLibraryNode =
        AppleLibraryBuilder.createBuilder(dependentStaticLibraryTarget).build();

    BuildTarget dependentSharedLibraryTarget =
        BuildTarget.builder(rootPath, "//dep", "shared")
            .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
            .build();
    TargetNode<?, ?> dependentSharedLibraryNode =
        AppleLibraryBuilder.createBuilder(dependentSharedLibraryTarget)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("empty.m"))))
            .setDeps(ImmutableSortedSet.of(dependentStaticLibraryTarget))
            .build();

    BuildTarget libraryTarget =
        BuildTarget.builder(rootPath, "//foo", "library")
            .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
            .build();
    TargetNode<?, ?> libraryNode =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setDeps(ImmutableSortedSet.of(dependentSharedLibraryTarget))
            .build();

    BuildTarget bundleTarget = BuildTarget.builder(rootPath, "//foo", "final").build();
    TargetNode<?, ?> bundleNode =
        AppleBundleBuilder.createBuilder(bundleTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.BUNDLE))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(libraryTarget)
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(
                dependentStaticLibraryNode, dependentSharedLibraryNode, libraryNode, bundleNode));
    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:final");
    assertEquals(target.getProductType(), ProductType.BUNDLE);
    assertEquals("Should have exact number of build phases ", 2, target.getBuildPhases().size());
    ProjectGeneratorTestUtils.assertHasSingletonFrameworksPhaseWithFrameworkEntries(
        target, ImmutableList.of("$BUILT_PRODUCTS_DIR/libshared.dylib"));
  }

  @Test
  public void stopsLinkingRecursiveDependenciesAtBundles() throws IOException {
    BuildTarget dependentStaticLibraryTarget =
        BuildTarget.builder(rootPath, "//dep", "static").build();
    TargetNode<?, ?> dependentStaticLibraryNode =
        AppleLibraryBuilder.createBuilder(dependentStaticLibraryTarget).build();

    BuildTarget dependentSharedLibraryTarget =
        BuildTarget.builder(rootPath, "//dep", "shared")
            .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
            .build();
    TargetNode<?, ?> dependentSharedLibraryNode =
        AppleLibraryBuilder.createBuilder(dependentSharedLibraryTarget)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("e.m"))))
            .setDeps(ImmutableSortedSet.of(dependentStaticLibraryTarget))
            .build();

    BuildTarget dependentFrameworkTarget =
        BuildTarget.builder(rootPath, "//dep", "framework").build();
    TargetNode<?, ?> dependentFrameworkNode =
        AppleBundleBuilder.createBuilder(dependentFrameworkTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(dependentSharedLibraryTarget)
            .build();

    BuildTarget libraryTarget =
        BuildTarget.builder(rootPath, "//foo", "library")
            .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
            .build();
    TargetNode<?, ?> libraryNode =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setDeps(ImmutableSortedSet.of(dependentFrameworkTarget))
            .build();

    BuildTarget bundleTarget = BuildTarget.builder(rootPath, "//foo", "final").build();
    TargetNode<?, ?> bundleNode =
        AppleBundleBuilder.createBuilder(bundleTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.BUNDLE))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(libraryTarget)
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(
                dependentStaticLibraryNode,
                dependentSharedLibraryNode,
                dependentFrameworkNode,
                libraryNode,
                bundleNode));
    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:final");
    assertEquals(target.getProductType(), ProductType.BUNDLE);
    assertEquals("Should have exact number of build phases ", 2, target.getBuildPhases().size());
    ProjectGeneratorTestUtils.assertHasSingletonFrameworksPhaseWithFrameworkEntries(
        target, ImmutableList.of("$BUILT_PRODUCTS_DIR/framework.framework"));
  }

  @Test
  public void stopsCopyingRecursiveDependenciesAtBundles() throws IOException {
    BuildTarget dependentStaticLibraryTarget =
        BuildTarget.builder(rootPath, "//dep", "static").build();
    TargetNode<?, ?> dependentStaticLibraryNode =
        AppleLibraryBuilder.createBuilder(dependentStaticLibraryTarget).build();

    BuildTarget dependentStaticFrameworkTarget =
        BuildTarget.builder(rootPath, "//dep", "static-framework").build();
    TargetNode<?, ?> dependentStaticFrameworkNode =
        AppleBundleBuilder.createBuilder(dependentStaticFrameworkTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(dependentStaticLibraryTarget)
            .build();

    BuildTarget dependentSharedLibraryTarget =
        BuildTarget.builder(rootPath, "//dep", "shared")
            .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
            .build();
    TargetNode<?, ?> dependentSharedLibraryNode =
        AppleLibraryBuilder.createBuilder(dependentSharedLibraryTarget)
            .setDeps(ImmutableSortedSet.of(dependentStaticFrameworkTarget))
            .build();

    BuildTarget dependentFrameworkTarget =
        BuildTarget.builder(rootPath, "//dep", "framework").build();
    TargetNode<?, ?> dependentFrameworkNode =
        AppleBundleBuilder.createBuilder(dependentFrameworkTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(dependentSharedLibraryTarget)
            .build();

    BuildTarget libraryTarget =
        BuildTarget.builder(rootPath, "//foo", "library")
            .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
            .build();
    TargetNode<?, ?> libraryNode =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("e.m"))))
            .setDeps(ImmutableSortedSet.of(dependentFrameworkTarget))
            .build();

    BuildTarget bundleTarget = BuildTarget.builder(rootPath, "//foo", "final").build();
    TargetNode<?, ?> bundleNode =
        AppleBundleBuilder.createBuilder(bundleTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.BUNDLE))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(libraryTarget)
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            // ant needs this to be explicit
            ImmutableSet.of(
                dependentStaticLibraryNode,
                dependentStaticFrameworkNode,
                dependentSharedLibraryNode,
                dependentFrameworkNode,
                libraryNode,
                bundleNode));
    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:final");
    assertEquals(target.getProductType(), ProductType.BUNDLE);
    assertEquals("Should have exact number of build phases ", 2, target.getBuildPhases().size());
    ProjectGeneratorTestUtils.assertHasSingletonCopyFilesPhaseWithFileEntries(
        target, ImmutableList.of("$BUILT_PRODUCTS_DIR/framework.framework"));
  }

  @Test
  public void bundlesDontLinkTheirOwnBinary() throws IOException {
    BuildTarget libraryTarget =
        BuildTarget.builder(rootPath, "//foo", "library")
            .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
            .build();
    TargetNode<?, ?> libraryNode = AppleLibraryBuilder.createBuilder(libraryTarget).build();

    BuildTarget bundleTarget = BuildTarget.builder(rootPath, "//foo", "final").build();
    TargetNode<?, ?> bundleNode =
        AppleBundleBuilder.createBuilder(bundleTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.BUNDLE))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(libraryTarget)
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(libraryNode, bundleNode));
    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:final");
    assertEquals(target.getProductType(), ProductType.BUNDLE);
    assertEquals("Should have exact number of build phases ", 0, target.getBuildPhases().size());
  }

  @Test
  public void resourcesInDependenciesPropagatesToBundles() throws IOException {
    BuildTarget resourceTarget = BuildTarget.builder(rootPath, "//foo", "res").build();
    TargetNode<?, ?> resourceNode =
        AppleResourceBuilder.createBuilder(resourceTarget)
            .setFiles(ImmutableSet.of(new FakeSourcePath("bar.png")))
            .setDirs(ImmutableSet.of(new FakeSourcePath("foodir")))
            .build();

    BuildTarget libraryTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> libraryNode =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setDeps(ImmutableSortedSet.of(resourceTarget))
            .build();

    BuildTarget bundleLibraryTarget = BuildTarget.builder(rootPath, "//foo", "bundlelib").build();
    TargetNode<?, ?> bundleLibraryNode =
        AppleLibraryBuilder.createBuilder(bundleLibraryTarget)
            .setDeps(ImmutableSortedSet.of(libraryTarget))
            .build();

    BuildTarget bundleTarget = BuildTarget.builder(rootPath, "//foo", "bundle").build();
    TargetNode<?, ?> bundleNode =
        AppleBundleBuilder.createBuilder(bundleTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.BUNDLE))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(bundleLibraryTarget)
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(resourceNode, libraryNode, bundleLibraryNode, bundleNode));
    projectGenerator.createXcodeProjects();

    PBXProject generatedProject = projectGenerator.getGeneratedProject();
    PBXTarget target = assertTargetExistsAndReturnTarget(generatedProject, "//foo:bundle");
    assertHasSingletonResourcesPhaseWithEntries(target, "bar.png", "foodir");
  }

  @Test
  public void assetCatalogsInDependenciesPropogatesToBundles() throws IOException {
    BuildTarget assetCatalogTarget =
        BuildTarget.builder(rootPath, "//foo", "asset_catalog").build();
    TargetNode<?, ?> assetCatalogNode =
        AppleAssetCatalogBuilder.createBuilder(assetCatalogTarget)
            .setDirs(ImmutableSortedSet.of(new FakeSourcePath("AssetCatalog.xcassets")))
            .build();

    BuildTarget libraryTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> libraryNode =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setDeps(ImmutableSortedSet.of(assetCatalogTarget))
            .build();

    BuildTarget bundleLibraryTarget = BuildTarget.builder(rootPath, "//foo", "bundlelib").build();
    TargetNode<?, ?> bundleLibraryNode =
        AppleLibraryBuilder.createBuilder(bundleLibraryTarget)
            .setDeps(ImmutableSortedSet.of(libraryTarget))
            .build();

    BuildTarget bundleTarget = BuildTarget.builder(rootPath, "//foo", "bundle").build();
    TargetNode<?, ?> bundleNode =
        AppleBundleBuilder.createBuilder(bundleTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.BUNDLE))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(bundleLibraryTarget)
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(assetCatalogNode, libraryNode, bundleLibraryNode, bundleNode));
    projectGenerator.createXcodeProjects();

    PBXProject generatedProject = projectGenerator.getGeneratedProject();
    PBXTarget target = assertTargetExistsAndReturnTarget(generatedProject, "//foo:bundle");
    assertHasSingletonResourcesPhaseWithEntries(target, "AssetCatalog.xcassets");
  }

  @Test
  public void generatedTargetConfigurationHasRepoRootSet() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "rule").build();
    TargetNode<?, ?> node =
        AppleLibraryBuilder.createBuilder(buildTarget)
            .setConfigs(ImmutableSortedMap.of("Debug", ImmutableMap.of()))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(node), ImmutableSet.of());
    projectGenerator.createXcodeProjects();

    PBXProject generatedProject = projectGenerator.getGeneratedProject();
    ImmutableMap<String, String> settings =
        getBuildSettings(buildTarget, generatedProject.getTargets().get(0), "Debug");
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
    BuildTarget buildTarget1 = BuildTarget.builder(rootPath, "//foo", "rule1").build();
    TargetNode<?, ?> node1 =
        AppleLibraryBuilder.createBuilder(buildTarget1)
            .setConfigs(
                ImmutableSortedMap.of(
                    "Conf1", ImmutableMap.of(),
                    "Conf2", ImmutableMap.of()))
            .build();

    BuildTarget buildTarget2 = BuildTarget.builder(rootPath, "//foo", "rule2").build();
    TargetNode<?, ?> node2 =
        AppleLibraryBuilder.createBuilder(buildTarget2)
            .setConfigs(
                ImmutableSortedMap.of(
                    "Conf2", ImmutableMap.of(),
                    "Conf3", ImmutableMap.of()))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(node1, node2));
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
    BuildTarget buildTarget =
        BuildTarget.builder(rootPath, "//foo", "rule")
            .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
            .build();
    TargetNode<?, ?> node =
        AppleLibraryBuilder.createBuilder(buildTarget)
            .setConfigs(ImmutableSortedMap.of("Debug", ImmutableMap.of()))
            .setLibraries(
                ImmutableSortedSet.of(
                    FrameworkPath.ofSourceTreePath(
                        new SourceTreePath(
                            PBXReference.SourceTree.BUILT_PRODUCTS_DIR,
                            Paths.get("libfoo.a"),
                            Optional.empty())),
                    FrameworkPath.ofSourceTreePath(
                        new SourceTreePath(
                            PBXReference.SourceTree.SDKROOT,
                            Paths.get("libfoo.a"),
                            Optional.empty())),
                    FrameworkPath.ofSourceTreePath(
                        new SourceTreePath(
                            PBXReference.SourceTree.SOURCE_ROOT,
                            Paths.get("libfoo.a"),
                            Optional.empty()))))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(node));
    projectGenerator.createXcodeProjects();

    PBXProject generatedProject = projectGenerator.getGeneratedProject();
    PBXTarget target = assertTargetExistsAndReturnTarget(generatedProject, "//foo:rule#shared");
    ProjectGeneratorTestUtils.assertHasSingletonFrameworksPhaseWithFrameworkEntries(
        target,
        ImmutableList.of(
            "$BUILT_PRODUCTS_DIR/libfoo.a", "$SDKROOT/libfoo.a", "$SOURCE_ROOT/libfoo.a"));
  }

  @Test
  public void testGeneratedProjectIsNotReadOnlyIfOptionNotSpecified() throws IOException {
    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    assertTrue(fakeProjectFilesystem.getFileAttributesAtPath(OUTPUT_PROJECT_FILE_PATH).isEmpty());
  }

  @Test
  public void testGeneratedProjectIsReadOnlyIfOptionSpecified() throws IOException {
    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(), ImmutableSet.of(ProjectGenerator.Option.GENERATE_READ_ONLY_FILES));

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
    assertEquals(expectedAttribute.value(), actualAttribute.value());
  }

  @Test
  public void projectIsRewrittenIfContentsHaveChanged() throws IOException {
    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(ImmutableSet.of());

    clock.setCurrentTimeMillis(49152);
    projectGenerator.createXcodeProjects();
    assertThat(
        projectFilesystem.getLastModifiedTime(OUTPUT_PROJECT_FILE_PATH),
        equalTo(FileTime.fromMillis(49152L)));

    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "foo").build();
    TargetNode<?, ?> node = AppleLibraryBuilder.createBuilder(buildTarget).build();
    ProjectGenerator projectGenerator2 =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(node));

    clock.setCurrentTimeMillis(64738);
    projectGenerator2.createXcodeProjects();
    assertThat(
        projectFilesystem.getLastModifiedTime(OUTPUT_PROJECT_FILE_PATH),
        equalTo(FileTime.fromMillis(64738L)));
  }

  @Test
  public void projectIsNotRewrittenIfContentsHaveNotChanged() throws IOException {
    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(ImmutableSet.of());

    clock.setCurrentTimeMillis(49152);
    projectGenerator.createXcodeProjects();
    assertThat(
        projectFilesystem.getLastModifiedTime(OUTPUT_PROJECT_FILE_PATH),
        equalTo(FileTime.fromMillis(49152L)));

    ProjectGenerator projectGenerator2 =
        createProjectGeneratorForCombinedProject(ImmutableSet.of());

    clock.setCurrentTimeMillis(64738);
    projectGenerator2.createXcodeProjects();
    assertThat(
        projectFilesystem.getLastModifiedTime(OUTPUT_PROJECT_FILE_PATH),
        equalTo(FileTime.fromMillis(49152L)));
  }

  @Test
  public void nonexistentResourceDirectoryShouldThrow() throws IOException {
    ImmutableSet<TargetNode<?, ?>> nodes =
        setupSimpleLibraryWithResources(
            ImmutableSet.of(), ImmutableSet.of(new FakeSourcePath("nonexistent-directory")));

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "nonexistent-directory specified in the dirs parameter of //foo:res is not a directory");

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(nodes);
    projectGenerator.createXcodeProjects();
  }

  @Test
  public void nonexistentResourceFileShouldThrow() throws IOException {
    ImmutableSet<TargetNode<?, ?>> nodes =
        setupSimpleLibraryWithResources(
            ImmutableSet.of(new FakeSourcePath("nonexistent-file.png")), ImmutableSet.of());

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "nonexistent-file.png specified in the files parameter of //foo:res is not a regular file");

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(nodes);
    projectGenerator.createXcodeProjects();
  }

  @Test
  public void usingFileAsResourceDirectoryShouldThrow() throws IOException {
    ImmutableSet<TargetNode<?, ?>> nodes =
        setupSimpleLibraryWithResources(
            ImmutableSet.of(), ImmutableSet.of(new FakeSourcePath("bar.png")));

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage("bar.png specified in the dirs parameter of //foo:res is not a directory");

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(nodes);
    projectGenerator.createXcodeProjects();
  }

  @Test
  public void usingDirectoryAsResourceFileShouldThrow() throws IOException {
    ImmutableSet<TargetNode<?, ?>> nodes =
        setupSimpleLibraryWithResources(
            ImmutableSet.of(new FakeSourcePath("foodir")), ImmutableSet.of());

    thrown.expect(HumanReadableException.class);
    thrown.expectMessage(
        "foodir specified in the files parameter of //foo:res is not a regular file");

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(nodes);
    projectGenerator.createXcodeProjects();
  }

  @Test
  public void usingBuildTargetSourcePathInResourceDirsOrFilesDoesNotThrow() throws IOException {
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//some:rule");
    SourcePath sourcePath = new DefaultBuildTargetSourcePath(buildTarget);
    TargetNode<?, ?> generatingTarget = ExportFileBuilder.newExportFileBuilder(buildTarget).build();

    ImmutableSet<TargetNode<?, ?>> nodes =
        FluentIterable.from(
                setupSimpleLibraryWithResources(
                    ImmutableSet.of(sourcePath), ImmutableSet.of(sourcePath)))
            .append(generatingTarget)
            .toSet();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(nodes);
    projectGenerator.createXcodeProjects();
  }

  private BuckEventBus getFakeBuckEventBus() {
    return BuckEventBusFactory.newInstance(new IncrementingFakeClock(TimeUnit.SECONDS.toNanos(1)));
  }

  @Test
  public void testResolvingExportFile() throws IOException {
    BuildTarget source1Target = BuildTarget.builder(rootPath, "//Vendor", "source1").build();
    BuildTarget source2Target = BuildTarget.builder(rootPath, "//Vendor", "source2").build();
    BuildTarget source2RefTarget = BuildTarget.builder(rootPath, "//Vendor", "source2ref").build();
    BuildTarget source3Target = BuildTarget.builder(rootPath, "//Vendor", "source3").build();
    BuildTarget headerTarget = BuildTarget.builder(rootPath, "//Vendor", "header").build();
    BuildTarget libTarget = BuildTarget.builder(rootPath, "//Libraries", "foo").build();

    TargetNode<ExportFileDescriptionArg, ?> source1 =
        ExportFileBuilder.newExportFileBuilder(source1Target)
            .setSrc(new PathSourcePath(projectFilesystem, Paths.get("Vendor/sources/source1")))
            .build();

    TargetNode<ExportFileDescriptionArg, ?> source2 =
        ExportFileBuilder.newExportFileBuilder(source2Target)
            .setSrc(new PathSourcePath(projectFilesystem, Paths.get("Vendor/source2")))
            .build();

    TargetNode<ExportFileDescriptionArg, ?> source2Ref =
        ExportFileBuilder.newExportFileBuilder(source2RefTarget)
            .setSrc(new DefaultBuildTargetSourcePath(source2Target))
            .build();

    TargetNode<ExportFileDescriptionArg, ?> source3 =
        ExportFileBuilder.newExportFileBuilder(source3Target).build();

    TargetNode<ExportFileDescriptionArg, ?> header =
        ExportFileBuilder.newExportFileBuilder(headerTarget).build();

    TargetNode<AppleLibraryDescriptionArg, ?> library =
        AppleLibraryBuilder.createBuilder(libTarget)
            .setConfigs(ImmutableSortedMap.of("Debug", ImmutableMap.of()))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(new DefaultBuildTargetSourcePath(source1Target)),
                    SourceWithFlags.of(new DefaultBuildTargetSourcePath(source2RefTarget)),
                    SourceWithFlags.of(new DefaultBuildTargetSourcePath(source3Target))))
            .setPrefixHeader(Optional.of(new DefaultBuildTargetSourcePath(headerTarget)))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(source1, source2, source2Ref, source3, header, library));

    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(
            projectGenerator.getGeneratedProject(), libTarget.toString());

    assertHasSingletonSourcesPhaseWithSourcesAndFlags(
        target,
        ImmutableMap.of(
            "Vendor/sources/source1", Optional.empty(),
            "Vendor/source2", Optional.empty(),
            "Vendor/source3", Optional.empty()));

    ImmutableMap<String, String> settings = getBuildSettings(libTarget, target, "Debug");
    assertEquals("../Vendor/header", settings.get("GCC_PREFIX_HEADER"));
  }

  @Test
  public void applicationTestUsesHostAppAsTestHostAndBundleLoader() throws IOException {
    BuildTarget hostAppBinaryTarget =
        BuildTarget.builder(rootPath, "//foo", "HostAppBinary").build();
    TargetNode<?, ?> hostAppBinaryNode =
        AppleBinaryBuilder.createBuilder(hostAppBinaryTarget).build();

    BuildTarget hostAppTarget = BuildTarget.builder(rootPath, "//foo", "HostApp").build();
    TargetNode<?, ?> hostAppNode =
        AppleBundleBuilder.createBuilder(hostAppTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.APP))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(hostAppBinaryTarget)
            .build();

    BuildTarget testTarget = BuildTarget.builder(rootPath, "//foo", "AppTest").build();
    TargetNode<?, ?> testNode =
        AppleTestBuilder.createBuilder(testTarget)
            .setConfigs(ImmutableSortedMap.of("Debug", ImmutableMap.of()))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setTestHostApp(Optional.of(hostAppTarget))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(hostAppBinaryNode, hostAppNode, testNode), ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    PBXTarget testPBXTarget =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:AppTest");

    ImmutableMap<String, String> settings = getBuildSettings(testTarget, testPBXTarget, "Debug");
    // Check starts with as the remainder depends on the bundle style at build time.
    assertTrue(settings.get("BUNDLE_LOADER").startsWith("$BUILT_PRODUCTS_DIR/./HostApp.app/"));
    assertEquals("$(BUNDLE_LOADER)", settings.get("TEST_HOST"));
  }

  @Test
  public void uiTestUsesHostAppAsTarget() throws IOException {
    BuildTarget hostAppBinaryTarget =
        BuildTarget.builder(rootPath, "//foo", "HostAppBinary").build();
    TargetNode<?, ?> hostAppBinaryNode =
        AppleBinaryBuilder.createBuilder(hostAppBinaryTarget).build();

    BuildTarget hostAppTarget = BuildTarget.builder(rootPath, "//foo", "HostApp").build();
    TargetNode<?, ?> hostAppNode =
        AppleBundleBuilder.createBuilder(hostAppTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.APP))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(hostAppBinaryTarget)
            .build();

    BuildTarget testTarget = BuildTarget.builder(rootPath, "//foo", "AppTest").build();
    TargetNode<?, ?> testNode =
        AppleTestBuilder.createBuilder(testTarget)
            .setConfigs(ImmutableSortedMap.of("Debug", ImmutableMap.of()))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setTestHostApp(Optional.of(hostAppTarget))
            .isUiTest(true)
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(hostAppBinaryNode, hostAppNode, testNode), ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    PBXTarget testPBXTarget =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:AppTest");
    assertEquals(testPBXTarget.getProductType(), ProductType.UI_TEST);

    ImmutableMap<String, String> settings = getBuildSettings(testTarget, testPBXTarget, "Debug");
    // Check starts with as the remainder depends on the bundle style at build time.
    assertEquals(settings.get("TEST_TARGET_NAME"), "//foo:HostApp");
  }

  @Test
  public void applicationTestDoesNotCopyHostAppBundleIntoTestBundle() throws IOException {
    BuildTarget hostAppBinaryTarget =
        BuildTarget.builder(rootPath, "//foo", "HostAppBinary").build();
    TargetNode<?, ?> hostAppBinaryNode =
        AppleBinaryBuilder.createBuilder(hostAppBinaryTarget).build();

    BuildTarget hostAppTarget = BuildTarget.builder(rootPath, "//foo", "HostApp").build();
    TargetNode<?, ?> hostAppNode =
        AppleBundleBuilder.createBuilder(hostAppTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.APP))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(hostAppBinaryTarget)
            .build();

    BuildTarget testTarget = BuildTarget.builder(rootPath, "//foo", "AppTest").build();
    TargetNode<?, ?> testNode =
        AppleTestBuilder.createBuilder(testTarget)
            .setConfigs(ImmutableSortedMap.of("Debug", ImmutableMap.of()))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setTestHostApp(Optional.of(hostAppTarget))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(hostAppBinaryNode, hostAppNode, testNode), ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    PBXTarget testPBXTarget =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:AppTest");

    // for this test phases should be empty - there should be no copy phases in particular
    assertThat(testPBXTarget.getBuildPhases().size(), Matchers.equalTo(0));
  }

  @Test
  public void cxxFlagsPropagatedToConfig() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        AppleLibraryBuilder.createBuilder(buildTarget)
            .setLangPreprocessorFlags(
                ImmutableMap.of(
                    CxxSource.Type.C, ImmutableList.of("-std=gnu11"),
                    CxxSource.Type.OBJC, ImmutableList.of("-std=gnu11", "-fobjc-arc"),
                    CxxSource.Type.CXX, ImmutableList.of("-std=c++11", "-stdlib=libc++"),
                    CxxSource.Type.OBJCXX,
                        ImmutableList.of("-std=c++11", "-stdlib=libc++", "-fobjc-arc")))
            .setConfigs(ImmutableSortedMap.of("Debug", ImmutableMap.of()))
            .setSrcs(
                ImmutableSortedSet.of(
                    SourceWithFlags.of(new FakeSourcePath("foo1.m")),
                    SourceWithFlags.of(new FakeSourcePath("foo2.mm")),
                    SourceWithFlags.of(new FakeSourcePath("foo3.c")),
                    SourceWithFlags.of(new FakeSourcePath("foo4.cc"))))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(node));

    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:lib");

    PBXSourcesBuildPhase sourcesBuildPhase =
        ProjectGeneratorTestUtils.getSingletonPhaseByType(target, PBXSourcesBuildPhase.class);

    ImmutableMap<String, String> expected =
        ImmutableMap.of(
            "foo1.m", "-std=gnu11 -fobjc-arc",
            "foo2.mm", "-std=c++11 -stdlib=libc++ -fobjc-arc",
            "foo3.c", "-std=gnu11",
            "foo4.cc", "-std=c++11 -stdlib=libc++");

    for (PBXBuildFile file : sourcesBuildPhase.getFiles()) {
      String fileName = file.getFileRef().getName();
      NSDictionary buildFileSettings = file.getSettings().get();
      NSString compilerFlags = (NSString) buildFileSettings.get("COMPILER_FLAGS");
      assertNotNull("Build file settings should have COMPILER_FLAGS entry", compilerFlags);
      assertEquals(compilerFlags.toString(), expected.get(fileName));
    }
  }

  @Test
  public void testConfiglessAppleTargetGetsDefaultBuildConfigurations() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        AppleLibraryBuilder.createBuilder(buildTarget)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("foo.mm"))))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(node));

    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:lib");

    assertHasConfigurations(target, "Debug", "Release", "Profile");

    ImmutableMap<String, String> debugSettings =
        ProjectGeneratorTestUtils.getBuildSettings(projectFilesystem, buildTarget, target, "Debug");
    assertThat(debugSettings.size(), Matchers.greaterThan(0));

    ImmutableMap<String, String> profileSettings =
        ProjectGeneratorTestUtils.getBuildSettings(
            projectFilesystem, buildTarget, target, "Profile");
    assertThat(debugSettings, Matchers.equalTo(profileSettings));

    ImmutableMap<String, String> releaseSettings =
        ProjectGeneratorTestUtils.getBuildSettings(
            projectFilesystem, buildTarget, target, "Release");
    assertThat(debugSettings, Matchers.equalTo(releaseSettings));
  }

  @Test
  public void testAssetCatalogsUnderLibraryNotTest() throws IOException {
    BuildTarget libraryTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    BuildTarget testTarget = BuildTarget.builder(rootPath, "//foo", "test").build();
    BuildTarget assetCatalogTarget =
        BuildTarget.builder(rootPath, "//foo", "asset_catalog").build();

    TargetNode<?, ?> libraryNode =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setTests(ImmutableSortedSet.of(testTarget))
            .setDeps(ImmutableSortedSet.of(assetCatalogTarget))
            .build();
    TargetNode<?, ?> testNode =
        AppleTestBuilder.createBuilder(testTarget)
            .setConfigs(ImmutableSortedMap.of("Default", ImmutableMap.of()))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setDeps(ImmutableSortedSet.of(libraryTarget))
            .build();
    TargetNode<?, ?> assetCatalogNode =
        AppleAssetCatalogBuilder.createBuilder(assetCatalogTarget)
            .setDirs(ImmutableSortedSet.of(new FakeSourcePath("AssetCatalog.xcassets")))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(libraryNode, testNode, assetCatalogNode),
            ImmutableSet.of(ProjectGenerator.Option.USE_SHORT_NAMES_FOR_TARGETS));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXGroup mainGroup = project.getMainGroup();

    PBXTarget fooLibTarget = assertTargetExistsAndReturnTarget(project, "lib");
    assertTrue(
        FluentIterable.from(fooLibTarget.getBuildPhases())
            .filter(PBXResourcesBuildPhase.class)
            .isEmpty());
    PBXGroup libResourcesGroup =
        mainGroup.getOrCreateChildGroupByName("lib").getOrCreateChildGroupByName("Resources");
    PBXFileReference assetCatalogFile = (PBXFileReference) libResourcesGroup.getChildren().get(0);
    assertEquals("AssetCatalog.xcassets", assetCatalogFile.getName());

    PBXTarget fooTestTarget = assertTargetExistsAndReturnTarget(project, "test");
    PBXResourcesBuildPhase resourcesBuildPhase =
        ProjectGeneratorTestUtils.getSingletonPhaseByType(
            fooTestTarget, PBXResourcesBuildPhase.class);
    assertThat(resourcesBuildPhase.getFiles(), hasSize(1));
    assertThat(
        assertFileRefIsRelativeAndResolvePath(resourcesBuildPhase.getFiles().get(0).getFileRef()),
        equalTo(projectFilesystem.resolve("AssetCatalog.xcassets").toString()));
    PBXGroup testResourcesGroup =
        mainGroup.getOrCreateChildGroupByName("test").getOrCreateChildGroupByName("Resources");
    assetCatalogFile = (PBXFileReference) testResourcesGroup.getChildren().get(0);
    assertEquals("AssetCatalog.xcassets", assetCatalogFile.getName());
  }

  @Test
  public void testResourcesUnderLibrary() throws IOException {
    BuildTarget fileTarget = BuildTarget.builder(rootPath, "//foo", "file").build();
    BuildTarget resourceTarget = BuildTarget.builder(rootPath, "//foo", "res").build();
    BuildTarget libraryTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();

    TargetNode<?, ?> fileNode = ExportFileBuilder.newExportFileBuilder(fileTarget).build();
    TargetNode<?, ?> resourceNode =
        AppleResourceBuilder.createBuilder(resourceTarget)
            .setDirs(ImmutableSet.of())
            .setFiles(ImmutableSet.of(new DefaultBuildTargetSourcePath(fileTarget)))
            .build();
    TargetNode<?, ?> libraryNode =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setDeps(ImmutableSortedSet.of(resourceTarget))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(fileNode, resourceNode, libraryNode));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXGroup mainGroup = project.getMainGroup();

    PBXGroup resourcesGroup =
        mainGroup.getOrCreateDescendantGroupByPath(ImmutableList.of("//foo:lib", "Resources"));
    PBXFileReference resource = (PBXFileReference) Iterables.get(resourcesGroup.getChildren(), 0);
    assertThat(resource.getName(), equalTo("file"));
  }

  @Test
  public void resourceDirectoriesHaveFolderType() throws IOException {
    BuildTarget directoryTarget = BuildTarget.builder(rootPath, "//foo", "dir").build();
    BuildTarget resourceTarget = BuildTarget.builder(rootPath, "//foo", "res").build();
    BuildTarget libraryTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();

    TargetNode<?, ?> directoryNode =
        ExportFileBuilder.newExportFileBuilder(directoryTarget).build();
    TargetNode<?, ?> resourceNode =
        AppleResourceBuilder.createBuilder(resourceTarget)
            .setDirs(ImmutableSet.of(new DefaultBuildTargetSourcePath(directoryTarget)))
            .setFiles(ImmutableSet.of())
            .build();
    TargetNode<?, ?> libraryNode =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setDeps(ImmutableSortedSet.of(resourceTarget))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(directoryNode, resourceNode, libraryNode));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXGroup mainGroup = project.getMainGroup();

    PBXGroup resourcesGroup =
        mainGroup.getOrCreateDescendantGroupByPath(ImmutableList.of("//foo:lib", "Resources"));
    PBXFileReference resource = (PBXFileReference) Iterables.get(resourcesGroup.getChildren(), 0);
    assertThat(resource.getName(), equalTo("dir"));
    assertThat(resource.getExplicitFileType(), equalTo(Optional.of("folder")));
  }

  @Test
  public void resourceDirectoriesDontHaveFolderTypeIfTheyCanHaveAMoreSpecificType()
      throws IOException {
    BuildTarget directoryTarget = BuildTarget.builder(rootPath, "//foo", "dir.iconset").build();
    BuildTarget resourceTarget = BuildTarget.builder(rootPath, "//foo", "res").build();
    BuildTarget libraryTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();

    TargetNode<?, ?> directoryNode =
        ExportFileBuilder.newExportFileBuilder(directoryTarget).build();
    TargetNode<?, ?> resourceNode =
        AppleResourceBuilder.createBuilder(resourceTarget)
            .setDirs(ImmutableSet.of(new DefaultBuildTargetSourcePath(directoryTarget)))
            .setFiles(ImmutableSet.of())
            .build();
    TargetNode<?, ?> libraryNode =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setDeps(ImmutableSortedSet.of(resourceTarget))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(directoryNode, resourceNode, libraryNode));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXGroup mainGroup = project.getMainGroup();

    PBXGroup resourcesGroup =
        mainGroup.getOrCreateDescendantGroupByPath(ImmutableList.of("//foo:lib", "Resources"));
    PBXFileReference resource = (PBXFileReference) Iterables.get(resourcesGroup.getChildren(), 0);
    assertThat(resource.getName(), equalTo("dir.iconset"));
    assertThat(resource.getExplicitFileType(), not(equalTo(Optional.of("folder"))));
  }

  @Test
  public void testAppleLibraryWithoutHeaderMaps() throws IOException {
    ImmutableSortedMap<String, ImmutableMap<String, String>> configs =
        ImmutableSortedMap.of("Debug", ImmutableMap.<String, String>of());

    BuildTarget libraryTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> libraryNode =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setConfigs(configs)
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("foo.m"))))
            .build();

    BuildTarget testTarget = BuildTarget.builder(rootPath, "//foo", "xctest").build();
    TargetNode<?, ?> testNode =
        AppleTestBuilder.createBuilder(testTarget)
            .setConfigs(configs)
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setSrcs(ImmutableSortedSet.of(SourceWithFlags.of(new FakeSourcePath("fooTest.m"))))
            .setDeps(ImmutableSortedSet.of(libraryTarget))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(libraryNode, testNode),
            ImmutableSet.of(ProjectGenerator.Option.DISABLE_HEADER_MAPS));

    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:xctest");

    ImmutableMap<String, String> settings = getBuildSettings(testTarget, target, "Debug");
    assertEquals(
        "$(inherited) "
            + "../buck-out/gen/_p/ptQfVNNRRE-priv "
            + "../buck-out/gen/_p/ptQfVNNRRE-pub "
            + "../buck-out/gen/_p/CwkbTNOBmb-pub "
            + "../buck-out",
        settings.get("HEADER_SEARCH_PATHS"));
  }

  @Test
  public void testFrameworkBundleDepIsNotCopiedToFrameworkBundle() throws IOException {
    BuildTarget framework2Target =
        BuildTarget.builder(rootPath, "//foo", "framework_2")
            .addFlavors(DEFAULT_FLAVOR, CxxDescriptionEnhancer.SHARED_FLAVOR)
            .build();
    BuildTarget framework2BinaryTarget =
        BuildTarget.builder(rootPath, "//foo", "framework_2_bin")
            .addFlavors(DEFAULT_FLAVOR, CxxDescriptionEnhancer.SHARED_FLAVOR)
            .build();
    TargetNode<?, ?> framework2BinaryNode =
        AppleLibraryBuilder.createBuilder(framework2BinaryTarget).build();
    TargetNode<?, ?> framework2Node =
        AppleBundleBuilder.createBuilder(framework2Target)
            .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(framework2BinaryTarget)
            .build();

    BuildTarget framework1Target =
        BuildTarget.builder(rootPath, "//foo", "framework_1")
            .addFlavors(DEFAULT_FLAVOR, CxxDescriptionEnhancer.SHARED_FLAVOR)
            .build();
    BuildTarget framework1BinaryTarget =
        BuildTarget.builder(rootPath, "//foo", "framework_1_bin")
            .addFlavors(DEFAULT_FLAVOR, CxxDescriptionEnhancer.SHARED_FLAVOR)
            .build();
    TargetNode<?, ?> framework1BinaryNode =
        AppleLibraryBuilder.createBuilder(framework1BinaryTarget).build();
    TargetNode<?, ?> framework1Node =
        AppleBundleBuilder.createBuilder(framework1Target)
            .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(framework1BinaryTarget)
            .setDeps(ImmutableSortedSet.of(framework2Target))
            .build();

    BuildTarget sharedLibraryTarget =
        BuildTarget.builder(rootPath, "//dep", "shared")
            .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
            .build();
    TargetNode<?, ?> sharedLibraryNode =
        AppleLibraryBuilder.createBuilder(sharedLibraryTarget).build();

    BuildTarget bundleTarget = BuildTarget.builder(rootPath, "//foo", "bundle").build();
    TargetNode<?, ?> bundleNode =
        AppleBundleBuilder.createBuilder(bundleTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.BUNDLE))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(sharedLibraryTarget)
            .setDeps(ImmutableSortedSet.of(framework1Target))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(
                framework1Node,
                framework2Node,
                framework1BinaryNode,
                framework2BinaryNode,
                sharedLibraryNode,
                bundleNode),
            ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(
            projectGenerator.getGeneratedProject(), "//foo:framework_1#default,shared");
    assertEquals(target.getProductType(), ProductType.FRAMEWORK);
    for (PBXBuildPhase buildPhase : target.getBuildPhases()) {
      if (buildPhase instanceof PBXCopyFilesBuildPhase) {
        PBXCopyFilesBuildPhase copyFilesBuildPhase = (PBXCopyFilesBuildPhase) buildPhase;
        assertThat(
            copyFilesBuildPhase.getDstSubfolderSpec().getDestination(),
            Matchers.not(PBXCopyFilesBuildPhase.Destination.FRAMEWORKS));
      }
    }
  }

  @Test
  public void testAppBundleContainsAllTransitiveFrameworkDeps() throws IOException {
    BuildTarget framework2Target =
        BuildTarget.builder(rootPath, "//foo", "framework_2")
            .addFlavors(DEFAULT_FLAVOR, CxxDescriptionEnhancer.SHARED_FLAVOR)
            .build();
    BuildTarget framework2BinaryTarget =
        BuildTarget.builder(rootPath, "//foo", "framework_2_bin")
            .addFlavors(DEFAULT_FLAVOR, CxxDescriptionEnhancer.SHARED_FLAVOR)
            .build();
    TargetNode<?, ?> framework2BinaryNode =
        AppleLibraryBuilder.createBuilder(framework2BinaryTarget).build();
    TargetNode<?, ?> framework2Node =
        AppleBundleBuilder.createBuilder(framework2Target)
            .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(framework2BinaryTarget)
            .setProductName(Optional.of("framework_2_override"))
            .build();

    BuildTarget framework1Target =
        BuildTarget.builder(rootPath, "//foo", "framework_1")
            .addFlavors(DEFAULT_FLAVOR, CxxDescriptionEnhancer.SHARED_FLAVOR)
            .build();
    BuildTarget framework1BinaryTarget =
        BuildTarget.builder(rootPath, "//foo", "framework_1_bin")
            .addFlavors(DEFAULT_FLAVOR, CxxDescriptionEnhancer.SHARED_FLAVOR)
            .build();
    TargetNode<?, ?> framework1BinaryNode =
        AppleLibraryBuilder.createBuilder(framework1BinaryTarget).build();
    TargetNode<?, ?> framework1Node =
        AppleBundleBuilder.createBuilder(framework1Target)
            .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(framework1BinaryTarget)
            .setDeps(ImmutableSortedSet.of(framework2Target))
            .build();

    BuildTarget framework1FlavoredTarget =
        BuildTarget.builder(rootPath, "//foo", "framework_1")
            .addFlavors(
                DEFAULT_FLAVOR,
                CxxDescriptionEnhancer.SHARED_FLAVOR,
                InternalFlavor.of("iphoneos-arm64"))
            .build();
    TargetNode<?, ?> framework1FlavoredNode =
        AppleBundleBuilder.createBuilder(framework1FlavoredTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(framework1BinaryTarget)
            .setDeps(ImmutableSortedSet.of(framework2Target))
            .build();

    BuildTarget sharedLibraryTarget =
        BuildTarget.builder(rootPath, "//dep", "shared")
            .addFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
            .build();
    TargetNode<?, ?> binaryNode = AppleBinaryBuilder.createBuilder(sharedLibraryTarget).build();

    BuildTarget bundleTarget = BuildTarget.builder(rootPath, "//foo", "bundle").build();
    TargetNode<?, ?> bundleNode =
        AppleBundleBuilder.createBuilder(bundleTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.APP))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(sharedLibraryTarget)
            .setDeps(ImmutableSortedSet.of(framework1Target, framework1FlavoredTarget))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            ImmutableSet.of(
                framework1Node,
                framework1FlavoredNode,
                framework2Node,
                framework1BinaryNode,
                framework2BinaryNode,
                binaryNode,
                bundleNode),
            ImmutableSet.of());

    projectGenerator.createXcodeProjects();

    PBXTarget target =
        assertTargetExistsAndReturnTarget(projectGenerator.getGeneratedProject(), "//foo:bundle");
    assertEquals(target.getProductType(), ProductType.APPLICATION);
    assertThat(target.getBuildPhases().size(), Matchers.equalTo(1));

    PBXBuildPhase buildPhase = target.getBuildPhases().get(0);
    assertThat(buildPhase instanceof PBXCopyFilesBuildPhase, Matchers.equalTo(true));

    PBXCopyFilesBuildPhase copyFilesBuildPhase = (PBXCopyFilesBuildPhase) buildPhase;
    assertThat(copyFilesBuildPhase.getFiles().size(), Matchers.equalTo(2));

    ImmutableSet<String> frameworkNames =
        FluentIterable.from(copyFilesBuildPhase.getFiles())
            .transform(input -> input.getFileRef().getName())
            .toSortedSet(Ordering.natural());
    assertThat(
        frameworkNames,
        Matchers.equalToObject(
            ImmutableSortedSet.of("framework_1.framework", "framework_2_override.framework")));
  }

  @Test
  public void testAppBundleDoesntLinkFrameworkWrappedWithResource() throws Exception {
    BuildTarget frameworkTarget =
        BuildTarget.builder(rootPath, "//foo", "framework")
            .addFlavors(
                FakeAppleRuleDescriptions.DEFAULT_MACOSX_X86_64_PLATFORM.getFlavor(),
                CxxDescriptionEnhancer.SHARED_FLAVOR)
            .build();
    BuildTarget frameworkBinaryTarget =
        BuildTarget.builder(rootPath, "//foo", "framework_bin")
            .addFlavors(
                FakeAppleRuleDescriptions.DEFAULT_MACOSX_X86_64_PLATFORM.getFlavor(),
                CxxDescriptionEnhancer.SHARED_FLAVOR)
            .build();
    TargetNode<?, ?> frameworkBinaryNode =
        AppleLibraryBuilder.createBuilder(frameworkBinaryTarget).build();
    TargetNode<?, ?> frameworkNode =
        AppleBundleBuilder.createBuilder(frameworkTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(frameworkBinaryTarget)
            .build();
    BuildTarget resourceTarget = BuildTarget.builder(rootPath, "//foo", "res").build();
    SourcePath sourcePath = new DefaultBuildTargetSourcePath(frameworkTarget);
    TargetNode<?, ?> resourceNode =
        AppleResourceBuilder.createBuilder(resourceTarget)
            .setFiles(ImmutableSet.of())
            .setDirs(ImmutableSet.of(sourcePath))
            .build();
    BuildTarget binaryTarget = BuildTarget.builder(rootPath, "//foo", "bin").build();
    TargetNode<?, ?> binaryNode =
        AppleBinaryBuilder.createBuilder(binaryTarget)
            .setDeps(ImmutableSortedSet.of(resourceTarget))
            .build();
    BuildTarget bundleTarget =
        BuildTarget.builder(rootPath, "//foo", "bundle")
            .addFlavors(FakeAppleRuleDescriptions.DEFAULT_MACOSX_X86_64_PLATFORM.getFlavor())
            .build();
    TargetNode<?, ?> bundleNode =
        AppleBundleBuilder.createBuilder(bundleTarget)
            .setExtension(Either.ofLeft(AppleBundleExtension.APP))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setBinary(binaryTarget)
            .build();
    ImmutableSet<TargetNode<?, ?>> nodes =
        ImmutableSet.of(frameworkBinaryNode, frameworkNode, resourceNode, binaryNode, bundleNode);
    final TargetGraph targetGraph = TargetGraphFactory.newInstance(ImmutableSet.copyOf(nodes));
    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(
            nodes, ImmutableSet.of(), getBuildRuleResolverWithRulesForNodeFunction(targetGraph));
    projectGenerator.createXcodeProjects();
    PBXTarget target =
        assertTargetExistsAndReturnTarget(
            projectGenerator.getGeneratedProject(), bundleTarget.getFullyQualifiedName());
    assertEquals(target.getProductType(), ProductType.APPLICATION);
    for (PBXBuildPhase buildPhase : target.getBuildPhases()) {
      assertFalse(buildPhase instanceof PBXCopyFilesBuildPhase);
    }
    assertThat(target.getBuildPhases().size(), Matchers.equalTo(1));
  }

  @Test
  public void testGeneratedProjectStructureAndSettingsWithBridgingHeader() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        AppleLibraryBuilder.createBuilder(buildTarget)
            .setConfigs(ImmutableSortedMap.of("Debug", ImmutableMap.of()))
            .setSrcs(ImmutableSortedSet.of())
            .setBridgingHeader(Optional.of(new FakeSourcePath("BridgingHeader/header1.h")))
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(node));

    projectGenerator.createXcodeProjects();

    // check if bridging header file existing in the project structure
    PBXProject project = projectGenerator.getGeneratedProject();
    PBXGroup targetGroup =
        project.getMainGroup().getOrCreateChildGroupByName(buildTarget.getFullyQualifiedName());
    PBXGroup sourcesGroup = targetGroup.getOrCreateChildGroupByName("Sources");

    assertThat(sourcesGroup.getChildren(), hasSize(1));

    PBXFileReference fileRefBridgingHeader =
        (PBXFileReference) Iterables.get(sourcesGroup.getChildren(), 0);
    assertEquals("header1.h", fileRefBridgingHeader.getName());

    // check for bridging header build setting
    PBXTarget target = assertTargetExistsAndReturnTarget(project, "//foo:lib");
    ImmutableMap<String, String> buildSettings = getBuildSettings(buildTarget, target, "Debug");
    assertEquals(
        "$(SRCROOT)/../BridgingHeader/header1.h", buildSettings.get("SWIFT_OBJC_BRIDGING_HEADER"));
  }

  @Test
  public void testGeneratedProjectSettingForSwiftVersion() throws IOException {
    BuildTarget buildTarget = BuildTarget.builder(rootPath, "//foo", "lib").build();
    TargetNode<?, ?> node =
        AppleLibraryBuilder.createBuilder(buildTarget)
            .setConfigs(ImmutableSortedMap.of("Debug", ImmutableMap.of()))
            .setSrcs(ImmutableSortedSet.of())
            .build();

    ProjectGenerator projectGenerator =
        createProjectGeneratorForCombinedProject(ImmutableSet.of(node));
    projectGenerator.createXcodeProjects();
    PBXProject project = projectGenerator.getGeneratedProject();

    PBXTarget target = assertTargetExistsAndReturnTarget(project, "//foo:lib");
    ImmutableMap<String, String> buildSettings = getBuildSettings(buildTarget, target, "Debug");
    assertThat(buildSettings.get("SWIFT_VERSION"), equalTo("1.23"));
  }

  @Test
  public void testMergedHeaderMap() throws IOException {
    BuildTarget lib1Target = BuildTarget.builder(rootPath, "//foo", "lib1").build();
    BuildTarget lib2Target = BuildTarget.builder(rootPath, "//bar", "lib2").build();
    BuildTarget lib3Target = BuildTarget.builder(rootPath, "//foo", "lib3").build();
    BuildTarget testTarget = BuildTarget.builder(rootPath, "//foo", "test").build();
    BuildTarget lib4Target = BuildTarget.builder(rootPath, "//foo", "lib4").build();

    TargetNode<?, ?> lib1Node =
        AppleLibraryBuilder.createBuilder(lib1Target)
            .setConfigs(ImmutableSortedMap.of("Default", ImmutableMap.of()))
            .setExportedHeaders(ImmutableSortedSet.of(new FakeSourcePath("lib1.h")))
            .setDeps(ImmutableSortedSet.of(lib2Target))
            .setTests(ImmutableSortedSet.of(testTarget))
            .build();

    TargetNode<?, ?> lib2Node =
        AppleLibraryBuilder.createBuilder(lib2Target)
            .setConfigs(ImmutableSortedMap.of("Default", ImmutableMap.of()))
            .setExportedHeaders(ImmutableSortedSet.of(new FakeSourcePath("lib2.h")))
            .build();

    TargetNode<?, ?> lib3Node =
        AppleLibraryBuilder.createBuilder(lib3Target)
            .setConfigs(ImmutableSortedMap.of("Default", ImmutableMap.of()))
            .setExportedHeaders(ImmutableSortedSet.of(new FakeSourcePath("lib3.h")))
            .build();

    TargetNode<?, ?> testNode =
        AppleTestBuilder.createBuilder(testTarget)
            .setConfigs(ImmutableSortedMap.of("Default", ImmutableMap.of()))
            .setInfoPlist(new FakeSourcePath("Info.plist"))
            .setDeps(ImmutableSortedSet.of(lib1Target, lib4Target))
            .build();

    TargetNode<?, ?> lib4Node =
        AppleLibraryBuilder.createBuilder(lib4Target)
            .setConfigs(ImmutableSortedMap.of("Default", ImmutableMap.of()))
            .setExportedHeaders(ImmutableSortedSet.of(new FakeSourcePath("lib4.h")))
            .build();

    final TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            ImmutableSet.of(lib1Node, lib2Node, lib3Node, testNode, lib4Node));
    final AppleDependenciesCache cache = new AppleDependenciesCache(targetGraph);

    ProjectGenerator projectGeneratorLib2 =
        new ProjectGenerator(
            targetGraph,
            cache,
            ImmutableSet.of(lib2Target),
            projectCell,
            OUTPUT_DIRECTORY,
            PROJECT_NAME,
            "BUCK",
            ImmutableSet.of(ProjectGenerator.Option.MERGE_HEADER_MAPS),
            false, /* isMainProject */
            Optional.of(lib1Target),
            ImmutableSet.of(lib1Target, lib4Target),
            FocusedModuleTargetMatcher.noFocus(),
            DEFAULT_PLATFORM,
            getBuildRuleResolverNodeFunction(targetGraph),
            getFakeBuckEventBus(),
            halideBuckConfig,
            cxxBuckConfig,
            swiftBuckConfig);

    projectGeneratorLib2.createXcodeProjects();

    // The merged header map should not generated at this point.
    Path hmapPath = Paths.get("buck-out/gen/_p/pub-hmap/.hmap");
    assertFalse(hmapPath.toString() + " should NOT exist.", projectFilesystem.isFile(hmapPath));
    // Checks the content of the header search paths.
    PBXProject project2 = projectGeneratorLib2.getGeneratedProject();
    PBXTarget testPBXTarget2 = assertTargetExistsAndReturnTarget(project2, "//bar:lib2");

    ImmutableMap<String, String> buildSettings2 =
        getBuildSettings(lib2Target, testPBXTarget2, "Default");

    assertEquals(
        "test binary should use header symlink trees for both public and non-public headers "
            + "of the tested library in HEADER_SEARCH_PATHS",
        "$(inherited) "
            + "../buck-out/gen/_p/YAYFR3hXIb-priv/.hmap "
            + "../buck-out/gen/_p/pub-hmap/.hmap "
            + "../buck-out",
        buildSettings2.get("HEADER_SEARCH_PATHS"));

    ProjectGenerator projectGeneratorLib1 =
        new ProjectGenerator(
            targetGraph,
            cache,
            ImmutableSet.of(lib1Target, testTarget), /* lib3Target not included on purpose */
            projectCell,
            OUTPUT_DIRECTORY,
            PROJECT_NAME,
            "BUCK",
            ImmutableSet.of(ProjectGenerator.Option.MERGE_HEADER_MAPS),
            true, /* isMainProject */
            Optional.of(lib1Target),
            ImmutableSet.of(lib1Target, lib4Target),
            FocusedModuleTargetMatcher.noFocus(),
            DEFAULT_PLATFORM,
            getBuildRuleResolverNodeFunction(targetGraph),
            getFakeBuckEventBus(),
            halideBuckConfig,
            cxxBuckConfig,
            swiftBuckConfig);

    projectGeneratorLib1.createXcodeProjects();

    // The merged header map should not generated at this point.
    assertTrue(hmapPath.toString() + " should exist.", projectFilesystem.isFile(hmapPath));
    assertThatHeaderMapWithoutSymLinksContains(
        Paths.get("buck-out/gen/_p/pub-hmap"),
        ImmutableMap.of(
            "lib1/lib1.h",
            "buck-out/gen/_p/WNl0jZWMBk-pub/lib1/lib1.h",
            "lib2/lib2.h",
            "buck-out/gen/_p/YAYFR3hXIb-pub/lib2/lib2.h",
            "lib4/lib4.h",
            "buck-out/gen/_p/nmnbF8ID6C-pub/lib4/lib4.h"));
    // Checks the content of the header search paths.
    PBXProject project1 = projectGeneratorLib1.getGeneratedProject();

    // For //foo:lib1
    PBXTarget testPBXTarget1 = assertTargetExistsAndReturnTarget(project1, "//foo:lib1");

    ImmutableMap<String, String> buildSettings1 =
        getBuildSettings(lib1Target, testPBXTarget1, "Default");

    assertEquals(
        "test binary should use header symlink trees for both public and non-public headers "
            + "of the tested library in HEADER_SEARCH_PATHS",
        "$(inherited) "
            + "../buck-out/gen/_p/WNl0jZWMBk-priv/.hmap "
            + "../buck-out/gen/_p/pub-hmap/.hmap "
            + "../buck-out",
        buildSettings1.get("HEADER_SEARCH_PATHS"));

    // For //foo:test
    PBXTarget testPBXTargetTest = assertTargetExistsAndReturnTarget(project1, "//foo:test");

    ImmutableMap<String, String> buildSettingsTest =
        getBuildSettings(testTarget, testPBXTargetTest, "Default");

    assertEquals(
        "test binary should use header symlink trees for both public and non-public headers "
            + "of the tested library in HEADER_SEARCH_PATHS",
        "$(inherited) "
            + "../buck-out/gen/_p/LpygK8zq5F-priv/.hmap "
            + "../buck-out/gen/_p/pub-hmap/.hmap "
            + "../buck-out/gen/_p/WNl0jZWMBk-priv/.hmap "
            + "../buck-out",
        buildSettingsTest.get("HEADER_SEARCH_PATHS"));
  }

  private ProjectGenerator createProjectGeneratorForCombinedProject(
      Collection<TargetNode<?, ?>> nodes) {
    return createProjectGeneratorForCombinedProject(nodes, ImmutableSet.of());
  }

  private ProjectGenerator createProjectGeneratorForCombinedProject(
      Collection<TargetNode<?, ?>> nodes,
      ImmutableSet<ProjectGenerator.Option> projectGeneratorOptions) {
    final TargetGraph targetGraph = TargetGraphFactory.newInstance(ImmutableSet.copyOf(nodes));
    return createProjectGeneratorForCombinedProject(
        nodes, projectGeneratorOptions, getBuildRuleResolverNodeFunction(targetGraph));
  }

  private ProjectGenerator createProjectGeneratorForCombinedProject(
      Collection<TargetNode<?, ?>> nodes,
      ImmutableSet<ProjectGenerator.Option> projectGeneratorOptions,
      Function<? super TargetNode<?, ?>, BuildRuleResolver> buildRuleResolverForNode) {
    ImmutableSet<BuildTarget> initialBuildTargets =
        nodes.stream().map(TargetNode::getBuildTarget).collect(MoreCollectors.toImmutableSet());

    final TargetGraph targetGraph = TargetGraphFactory.newInstance(ImmutableSet.copyOf(nodes));
    final AppleDependenciesCache cache = new AppleDependenciesCache(targetGraph);
    return new ProjectGenerator(
        targetGraph,
        cache,
        initialBuildTargets,
        projectCell,
        OUTPUT_DIRECTORY,
        PROJECT_NAME,
        "BUCK",
        projectGeneratorOptions,
        false,
        Optional.empty(),
        ImmutableSet.of(),
        FocusedModuleTargetMatcher.noFocus(),
        DEFAULT_PLATFORM,
        buildRuleResolverForNode,
        getFakeBuckEventBus(),
        halideBuckConfig,
        cxxBuckConfig,
        swiftBuckConfig);
  }

  private Function<TargetNode<?, ?>, BuildRuleResolver> getBuildRuleResolverNodeFunction(
      final TargetGraph targetGraph) {
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    AbstractBottomUpTraversal<TargetNode<?, ?>, RuntimeException> bottomUpTraversal =
        new AbstractBottomUpTraversal<TargetNode<?, ?>, RuntimeException>(targetGraph) {
          @Override
          @SuppressWarnings("PMD.EmptyCatchBlock")
          public void visit(TargetNode<?, ?> node) {
            try {
              resolver.requireRule(node.getBuildTarget());
            } catch (Exception e) {
              // NOTE(agallagher): A large number of the tests appear to setup their target nodes
              // incorrectly, causing action graph creation to fail with lots of missing expected
              // Apple C/C++ platform flavors.  This is gross, but to support tests that need a
              // complete sub-action graph, just skip over the errors.
            }
          }
        };
    bottomUpTraversal.traverse();
    return input -> resolver;
  }

  private Function<TargetNode<?, ?>, BuildRuleResolver>
      getBuildRuleResolverWithRulesForNodeFunction(final TargetGraph targetGraph)
          throws NoSuchBuildTargetException {
    final BuildRuleResolver ruleResolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());
    for (TargetNode<?, ?> node : targetGraph.getNodes()) {
      ruleResolver.requireRule(node.getBuildTarget());
      ruleResolver.requireRule(node.getBuildTarget().withFlavors());
    }
    return input -> ruleResolver;
  }

  private ImmutableSet<TargetNode<?, ?>> setupSimpleLibraryWithResources(
      ImmutableSet<SourcePath> resourceFiles, ImmutableSet<SourcePath> resourceDirectories) {
    BuildTarget resourceTarget = BuildTarget.builder(rootPath, "//foo", "res").build();
    TargetNode<?, ?> resourceNode =
        AppleResourceBuilder.createBuilder(resourceTarget)
            .setFiles(resourceFiles)
            .setDirs(resourceDirectories)
            .build();

    BuildTarget libraryTarget = BuildTarget.builder(rootPath, "//foo", "foo").build();
    TargetNode<?, ?> libraryNode =
        AppleLibraryBuilder.createBuilder(libraryTarget)
            .setDeps(ImmutableSortedSet.of(resourceTarget))
            .build();

    return ImmutableSet.of(resourceNode, libraryNode);
  }

  private String assertFileRefIsRelativeAndResolvePath(PBXReference fileRef) {
    assert (!fileRef.getPath().startsWith("/"));
    assertEquals(
        "file path should be relative to project directory",
        PBXReference.SourceTree.SOURCE_ROOT,
        fileRef.getSourceTree());
    return projectFilesystem
        .resolve(OUTPUT_DIRECTORY)
        .resolve(fileRef.getPath())
        .normalize()
        .toString();
  }

  private void assertHasConfigurations(PBXTarget target, String... names) {
    Map<String, XCBuildConfiguration> buildConfigurationMap =
        target.getBuildConfigurationList().getBuildConfigurationsByName().asMap();
    assertEquals(
        "Configuration list has expected number of entries",
        names.length,
        buildConfigurationMap.size());

    for (String name : names) {
      XCBuildConfiguration configuration = buildConfigurationMap.get(name);

      assertNotNull("Configuration entry exists", configuration);
      assertEquals("Configuration name is same as key", name, configuration.getName());
      assertTrue(
          "Configuration has xcconfig file",
          configuration.getBaseConfigurationReference().getPath().endsWith(".xcconfig"));
    }
  }

  private void assertKeepsConfigurationsInMainGroup(PBXProject project, PBXTarget target) {
    Map<String, XCBuildConfiguration> buildConfigurationMap =
        target.getBuildConfigurationList().getBuildConfigurationsByName().asMap();

    PBXGroup configsGroup =
        project
            .getMainGroup()
            .getOrCreateChildGroupByName("Configurations")
            .getOrCreateChildGroupByName("Buck (Do Not Modify)");

    assertNotNull("Configuration group exists", configsGroup);

    List<PBXReference> configReferences = configsGroup.getChildren();
    assertFalse("Configuration file references exist", configReferences.isEmpty());

    for (XCBuildConfiguration configuration : buildConfigurationMap.values()) {
      String path = configuration.getBaseConfigurationReference().getPath();

      PBXReference foundReference = null;
      for (PBXReference reference : configReferences) {
        assertTrue(
            "References in the configuration group should point to xcconfigs",
            reference.getPath().endsWith(".xcconfig"));

        if (reference.getPath().equals(path)) {
          foundReference = reference;
          break;
        }
      }

      assertNotNull(
          "File reference for configuration " + path + " should be in main group", foundReference);
    }
  }

  private void assertHasSingletonSourcesPhaseWithSourcesAndFlags(
      PBXTarget target, ImmutableMap<String, Optional<String>> sourcesAndFlags) {

    PBXSourcesBuildPhase sourcesBuildPhase =
        ProjectGeneratorTestUtils.getSingletonPhaseByType(target, PBXSourcesBuildPhase.class);

    assertEquals(
        "Sources build phase should have correct number of sources",
        sourcesAndFlags.size(),
        sourcesBuildPhase.getFiles().size());

    // map keys to absolute paths
    ImmutableMap.Builder<String, Optional<String>> absolutePathFlagMapBuilder =
        ImmutableMap.builder();
    for (Map.Entry<String, Optional<String>> name : sourcesAndFlags.entrySet()) {
      absolutePathFlagMapBuilder.put(
          projectFilesystem
              .getRootPath()
              .resolve(name.getKey())
              .toAbsolutePath()
              .normalize()
              .toString(),
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
            flags.get(),
            compilerFlags.getContent());
      } else {
        assertFalse(
            "Build file should not have settings dictionary", file.getSettings().isPresent());
      }
    }
  }

  private void assertSourcesNotInSourcesPhase(PBXTarget target, ImmutableSet<String> sources) {
    ImmutableSet.Builder<String> absoluteSourcesBuilder = ImmutableSet.builder();
    for (String name : sources) {
      absoluteSourcesBuilder.add(
          projectFilesystem.getRootPath().resolve(name).toAbsolutePath().normalize().toString());
    }

    Iterable<PBXBuildPhase> buildPhases =
        Iterables.filter(target.getBuildPhases(), PBXSourcesBuildPhase.class::isInstance);
    if (Iterables.size(buildPhases) == 0) {
      return;
    }

    ImmutableSet<String> absoluteSources = absoluteSourcesBuilder.build();
    PBXSourcesBuildPhase sourcesBuildPhase =
        (PBXSourcesBuildPhase) Iterables.getOnlyElement(buildPhases);
    for (PBXBuildFile file : sourcesBuildPhase.getFiles()) {
      String filePath = assertFileRefIsRelativeAndResolvePath(file.getFileRef());
      assertFalse(
          "Build phase should not contain this file " + filePath,
          absoluteSources.contains(filePath));
    }
  }

  private void assertHasSingletonResourcesPhaseWithEntries(PBXTarget target, String... resources) {
    PBXResourcesBuildPhase buildPhase =
        ProjectGeneratorTestUtils.getSingletonPhaseByType(target, PBXResourcesBuildPhase.class);
    assertEquals(
        "Resources phase should have right number of elements",
        resources.length,
        buildPhase.getFiles().size());

    ImmutableSet.Builder<String> expectedResourceSetBuilder = ImmutableSet.builder();
    for (String resource : resources) {
      expectedResourceSetBuilder.add(
          projectFilesystem
              .getRootPath()
              .resolve(resource)
              .toAbsolutePath()
              .normalize()
              .toString());
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
}
