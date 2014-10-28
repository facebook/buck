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

import static com.facebook.buck.apple.xcode.ProjectGeneratorTestUtils.assertHasSingletonFrameworksPhaseWithFrameworkEntries;
import static com.facebook.buck.apple.xcode.ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget;
import static com.facebook.buck.apple.xcode.ProjectGeneratorTestUtils.createAppleBundleBuildRule;
import static com.facebook.buck.apple.xcode.ProjectGeneratorTestUtils.createBuildRuleWithDefaults;
import static com.facebook.buck.apple.xcode.ProjectGeneratorTestUtils.createDescriptionArgWithDefaults;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.hamcrest.xml.HasXPath.hasXPath;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.dd.plist.NSDictionary;
import com.dd.plist.NSString;
import com.facebook.buck.apple.AppleAssetCatalogDescription;
import com.facebook.buck.apple.AppleBinaryDescription;
import com.facebook.buck.apple.AppleBundleDescription;
import com.facebook.buck.apple.AppleBundleExtension;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleLibraryDescription;
import com.facebook.buck.apple.AppleNativeTargetDescriptionArg;
import com.facebook.buck.apple.AppleResourceDescription;
import com.facebook.buck.apple.AppleTestDescription;
import com.facebook.buck.apple.CoreDataModelDescription;
import com.facebook.buck.apple.IosPostprocessResourcesDescription;
import com.facebook.buck.apple.XcodeNativeDescription;
import com.facebook.buck.apple.clang.HeaderMap;
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
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.rules.coercer.AppleSource;
import com.facebook.buck.rules.coercer.Pair;
import com.facebook.buck.rules.coercer.XcodeRuleConfiguration;
import com.facebook.buck.rules.coercer.XcodeRuleConfigurationLayer;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.timing.SettableFakeClock;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;

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
  private ExecutionContext executionContext;
  private AppleLibraryDescription appleLibraryDescription;
  private AppleTestDescription appleTestDescription;
  private IosPostprocessResourcesDescription iosPostprocessResourcesDescription;
  private AppleResourceDescription appleResourceDescription;
  private AppleBundleDescription appleBundleDescription;
  private AppleBinaryDescription appleBinaryDescription;
  private CoreDataModelDescription coreDataModelDescription;
  private XcodeNativeDescription xcodeNativeDescription;

  @Before
  public void setUp() throws IOException {
    clock = new SettableFakeClock(0, 0);
    fakeProjectFilesystem = new FakeProjectFilesystem(clock);
    projectFilesystem = fakeProjectFilesystem;
    executionContext = TestExecutionContext.newInstance();
    AppleConfig appleConfig = new AppleConfig(new FakeBuckConfig());
    appleLibraryDescription = new AppleLibraryDescription(appleConfig);
    appleTestDescription = new AppleTestDescription();
    iosPostprocessResourcesDescription = new IosPostprocessResourcesDescription();
    appleResourceDescription = new AppleResourceDescription();
    appleBundleDescription = new AppleBundleDescription();
    appleBinaryDescription = new AppleBinaryDescription(appleConfig);
    coreDataModelDescription = new CoreDataModelDescription();
    xcodeNativeDescription = new XcodeNativeDescription();

    // Add support files needed by project generation to fake filesystem.
    projectFilesystem.writeContentsToPath(
        "",
        Paths.get(ProjectGenerator.PATH_TO_ASSET_CATALOG_BUILD_PHASE_SCRIPT));
    projectFilesystem.writeContentsToPath(
        "",
        Paths.get(ProjectGenerator.PATH_TO_ASSET_CATALOG_COMPILER));
  }

  @Test
  public void testProjectStructureForEmptyProject() throws IOException {
    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<BuildRule>of(),
        ImmutableSet.<BuildTarget>of());

    Path outputWorkspaceBundlePath = OUTPUT_DIRECTORY.resolve(PROJECT_NAME + ".xcworkspace");
    Path outputWorkspaceFilePath = outputWorkspaceBundlePath.resolve("contents.xcworkspacedata");

    projectGenerator.createXcodeProjects();

    Optional<String> pbxproj = projectFilesystem.readFileIfItExists(OUTPUT_PROJECT_FILE_PATH);
    assertTrue(pbxproj.isPresent());

    Optional<String> xcworkspacedata =
        projectFilesystem.readFileIfItExists(outputWorkspaceFilePath);
    assertTrue(xcworkspacedata.isPresent());
  }

  @Test
  public void testWorkspaceGeneration() throws IOException {
    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<BuildRule>of(),
        ImmutableSet.<BuildTarget>of());
    projectGenerator.createXcodeProjects();

    Document workspace = projectGenerator.getGeneratedWorkspace();
    assertThat(workspace, hasXPath("/Workspace[@version = \"1.0\"]"));
    assertThat(
        workspace,
        hasXPath("/Workspace/FileRef/@location", equalTo("container:" + PROJECT_CONTAINER)));
  }

  @Test
  public void shouldNotCreateHeaderMapsWhenHeaderMapsAreDisabled() throws IOException {
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "lib").build())
            .setType(AppleLibraryDescription.TYPE)
            .build();
    AppleNativeTargetDescriptionArg arg = createDescriptionArgWithDefaults(appleLibraryDescription);
    arg.configs = Optional.of(
        ImmutableSortedMap.of(
            "Debug", new XcodeRuleConfiguration(ImmutableList.<XcodeRuleConfigurationLayer>of())));
    arg.srcs = Optional.of(
        ImmutableList.of(
            AppleSource.ofSourceGroup(
                new Pair<>(
                    "HeaderGroup1",
                    ImmutableList.of(
                        AppleSource.ofSourcePath(new TestSourcePath("foo.h")),
                        AppleSource.ofSourcePathWithFlags(
                            new Pair<SourcePath, String>(
                                new TestSourcePath("bar.h"), "public")))))));
    arg.useBuckHeaderMaps = Optional.of(false);
    BuildRule rule = appleLibraryDescription.createBuildRule(params, new BuildRuleResolver(), arg);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule),
        ImmutableSet.of(rule.getBuildTarget()));

    projectGenerator.createXcodeProjects();

    // No header map should be generated
    List<Path> headerMaps = projectGenerator.getGeneratedHeaderMaps();
    assertThat(headerMaps, hasSize(0));
  }

  @Test(expected = HumanReadableException.class)
  public void testLibraryPrivateHeaderWithHeaderMaps() throws IOException {
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "lib").build())
            .setType(AppleLibraryDescription.TYPE)
            .build();
    AppleNativeTargetDescriptionArg arg = createDescriptionArgWithDefaults(appleLibraryDescription);
    arg.configs = Optional.of(
        ImmutableSortedMap.of(
            "Debug", new XcodeRuleConfiguration(ImmutableList.<XcodeRuleConfigurationLayer>of())));
    arg.srcs = Optional.of(
        ImmutableList.of(
            AppleSource.ofSourceGroup(
                new Pair<>(
                    "HeaderGroup2",
                    ImmutableList.of(
                        AppleSource.ofSourcePathWithFlags(
                            new Pair<SourcePath, String>(
                                new TestSourcePath("blech.h"), "private")))))));
    arg.useBuckHeaderMaps = Optional.of(true);
    BuildRule rule = appleLibraryDescription.createBuildRule(params, new BuildRuleResolver(), arg);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule),
        ImmutableSet.of(rule.getBuildTarget()));

    projectGenerator.createXcodeProjects();
  }

  @Test
  public void testLibraryHeaderGroupsWithHeaderMaps() throws IOException {
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "lib").build())
            .setType(AppleLibraryDescription.TYPE)
            .build();
    AppleNativeTargetDescriptionArg arg = createDescriptionArgWithDefaults(appleLibraryDescription);
    arg.configs = Optional.of(
        ImmutableSortedMap.of(
            "Debug", new XcodeRuleConfiguration(ImmutableList.<XcodeRuleConfigurationLayer>of())));
    arg.srcs = Optional.of(
        ImmutableList.of(
            AppleSource.ofSourceGroup(
                new Pair<>(
                    "HeaderGroup1",
                    ImmutableList.of(
                        AppleSource.ofSourcePath(new TestSourcePath("foo.h")),
                        AppleSource.ofSourcePathWithFlags(
                            new Pair<SourcePath, String>(new TestSourcePath("bar.h"), "public"))))),
            AppleSource.ofSourceGroup(
                new Pair<>(
                    "HeaderGroup2",
                    ImmutableList.of(
                        AppleSource.ofSourcePath(new TestSourcePath("baz.h")))))));
    arg.useBuckHeaderMaps = Optional.of(true);
    BuildRule rule = appleLibraryDescription.createBuildRule(params, new BuildRuleResolver(), arg);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule),
        ImmutableSet.of(rule.getBuildTarget()));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXGroup targetGroup =
        project.getMainGroup().getOrCreateChildGroupByName(rule.getFullyQualifiedName());
    PBXGroup sourcesGroup = targetGroup.getOrCreateChildGroupByName("Sources");

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
        "buck-out/gen/foo/lib-public-headers.hmap",
        ImmutableMap.<String, String>of("lib/bar.h", "bar.h")
    );

    assertEquals("buck-out/gen/foo/lib-target-headers.hmap", headerMaps.get(1).toString());
    assertThatHeaderMapFileContains(
        "buck-out/gen/foo/lib-target-headers.hmap",
        ImmutableMap.<String, String>of(
            "lib/foo.h", "foo.h",
            "lib/bar.h", "bar.h",
            "lib/baz.h", "baz.h"
            )
    );

    assertEquals("buck-out/gen/foo/lib-target-user-headers.hmap", headerMaps.get(2).toString());
    assertThatHeaderMapFileContains(
        "buck-out/gen/foo/lib-target-user-headers.hmap",
        ImmutableMap.<String, String>of(
            "foo.h", "foo.h",
            "bar.h", "bar.h",
            "baz.h", "baz.h"
        )
    );
  }

  private void assertThatHeaderMapFileContains(String file, ImmutableMap<String, String> content) {
    byte[] bytes = projectFilesystem.readFileIfItExists(Paths.get(file)).get().getBytes();
    HeaderMap map = HeaderMap.deserialize(bytes);
    assertEquals(content.size(), map.getNumEntries());
    for (String key : content.keySet()) {
      assertEquals(
          Paths.get(content.get(key)).toAbsolutePath().toString(),
          map.lookup(key));
    }

  }

  @Test
  public void testAppleLibraryRule() throws IOException {
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "lib").build())
            .setType(AppleLibraryDescription.TYPE)
            .build();
    AppleNativeTargetDescriptionArg arg = createDescriptionArgWithDefaults(appleLibraryDescription);
    arg.configs = Optional.of(
        ImmutableSortedMap.of(
            "Debug", new XcodeRuleConfiguration(ImmutableList.<XcodeRuleConfigurationLayer>of())));
    arg.srcs = Optional.of(
        ImmutableList.of(
            AppleSource.ofSourcePathWithFlags(
                new Pair<SourcePath, String>(new TestSourcePath("foo.m"), "-foo")),
            AppleSource.ofSourcePath(new TestSourcePath("foo.h")),
            AppleSource.ofSourcePath(new TestSourcePath("bar.m"))));
    arg.useBuckHeaderMaps = Optional.of(false);
    BuildRule rule = appleLibraryDescription.createBuildRule(params, new BuildRuleResolver(), arg);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule),
        ImmutableSet.of(rule.getBuildTarget()));

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
    Path rawXcconfigFile = Paths.get("Test.xcconfig");
    SourcePath xcconfigFile = new PathSourcePath(rawXcconfigFile);
    projectFilesystem.writeContentsToPath("", rawXcconfigFile);

    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "lib").build())
            .setType(AppleLibraryDescription.TYPE)
            .build();
    AppleNativeTargetDescriptionArg arg = createDescriptionArgWithDefaults(appleLibraryDescription);
    arg.configs = Optional.of(
        ImmutableSortedMap.of(
            "Debug",
            new XcodeRuleConfiguration(
                ImmutableList.of(
                    new XcodeRuleConfigurationLayer(xcconfigFile),
                    new XcodeRuleConfigurationLayer(xcconfigFile)))));
    arg.headerPathPrefix = Optional.of("MyHeaderPathPrefix");
    arg.prefixHeader = Optional.<SourcePath>of(new TestSourcePath("Foo/Foo-Prefix.pch"));
    arg.useBuckHeaderMaps = Optional.of(false);
    BuildRule rule = appleLibraryDescription.createBuildRule(params, new BuildRuleResolver(), arg);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule),
        ImmutableSet.of(rule.getBuildTarget()),
        ImmutableSet.of(ProjectGenerator.Option.REFERENCE_EXISTING_XCCONFIGS));

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:lib");
    assertThat(target.isa(), equalTo("PBXNativeTarget"));
    assertThat(target.getProductType(), equalTo(PBXTarget.ProductType.STATIC_LIBRARY));

    assertHasConfigurations(target, "Debug");
    XCBuildConfiguration configuration = target
        .getBuildConfigurationList().getBuildConfigurationsByName().asMap().get("Debug");
    NSDictionary settings = configuration.getBuildSettings();
    assertEquals(
        new NSString("../Foo/Foo-Prefix.pch"),
        settings.get("GCC_PREFIX_HEADER"));
    assertEquals(
        new NSString("$SYMROOT/F4XWM33PHJWGSYQ/$CONFIGURATION$EFFECTIVE_PLATFORM_NAME"),
        settings.get("CONFIGURATION_BUILD_DIR"));
    assertEquals(
        new NSString("../Headers/MyHeaderPathPrefix"),
        settings.get("PUBLIC_HEADERS_FOLDER_PATH"));
  }

  @Test
  public void testAppleLibraryConfiguresDynamicLibraryOutputPaths() throws IOException {
    Path rawXcconfigFile = Paths.get("Test.xcconfig");
    SourcePath xcconfigFile = new PathSourcePath(rawXcconfigFile);
    projectFilesystem.writeContentsToPath("", rawXcconfigFile);

    BuildTarget buildTarget = BuildTarget.builder("//hi", "lib")
        .setFlavor(AppleLibraryDescription.DYNAMIC_LIBRARY)
        .build();
    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(buildTarget)
            .setType(AppleLibraryDescription.TYPE)
            .build();
    AppleNativeTargetDescriptionArg arg = createDescriptionArgWithDefaults(appleLibraryDescription);
    arg.configs = Optional.of(
        ImmutableSortedMap.of(
            "Debug",
            new XcodeRuleConfiguration(
                ImmutableList.of(
                    new XcodeRuleConfigurationLayer(xcconfigFile),
                    new XcodeRuleConfigurationLayer(xcconfigFile)))));
    arg.headerPathPrefix = Optional.of("MyHeaderPathPrefix");
    BuildRule rule = appleLibraryDescription.createBuildRule(params, new BuildRuleResolver(), arg);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule),
        ImmutableSet.of(rule.getBuildTarget()),
        ImmutableSet.of(ProjectGenerator.Option.REFERENCE_EXISTING_XCCONFIGS));

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//hi:lib#dynamic");
    assertThat(target.isa(), equalTo("PBXNativeTarget"));
    assertThat(target.getProductType(), equalTo(PBXTarget.ProductType.DYNAMIC_LIBRARY));

    assertHasConfigurations(target, "Debug");
    XCBuildConfiguration configuration = target
        .getBuildConfigurationList().getBuildConfigurationsByName().asMap().get("Debug");
    NSDictionary settings = configuration.getBuildSettings();
    assertEquals(
        new NSString("$SYMROOT/F4XWQ2J2NRUWEI3EPFXGC3LJMM/$CONFIGURATION$EFFECTIVE_PLATFORM_NAME"),
        settings.get("CONFIGURATION_BUILD_DIR"));
    assertEquals(
        new NSString("../Headers/MyHeaderPathPrefix"),
        settings.get("PUBLIC_HEADERS_FOLDER_PATH"));
  }

  @Test
  public void testAppleLibraryDoesntOverrideHeaderOutputPath() throws IOException {
    Path rawXcconfigFile = Paths.get("Test.xcconfig");
    SourcePath xcconfigFile = new PathSourcePath(rawXcconfigFile);
    projectFilesystem.writeContentsToPath("", rawXcconfigFile);

    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "lib").build())
            .setType(AppleLibraryDescription.TYPE)
            .build();
    AppleNativeTargetDescriptionArg arg = createDescriptionArgWithDefaults(appleLibraryDescription);
    arg.configs = Optional.of(
        ImmutableSortedMap.of(
            "Debug",
            new XcodeRuleConfiguration(
                ImmutableList.of(
                    new XcodeRuleConfigurationLayer(xcconfigFile),
                    new XcodeRuleConfigurationLayer(
                        ImmutableMap.of("PUBLIC_HEADERS_FOLDER_PATH", "FooHeaders")),
                    new XcodeRuleConfigurationLayer(xcconfigFile),
                    new XcodeRuleConfigurationLayer(
                        ImmutableMap.of("PUBLIC_HEADERS_FOLDER_PATH", "FooHeaders"))))));
    BuildRule rule = appleLibraryDescription.createBuildRule(params, new BuildRuleResolver(), arg);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule),
        ImmutableSet.of(rule.getBuildTarget()),
        ImmutableSet.of(ProjectGenerator.Option.REFERENCE_EXISTING_XCCONFIGS));

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:lib");
    assertThat(target.isa(), equalTo("PBXNativeTarget"));
    assertThat(target.getProductType(), equalTo(PBXTarget.ProductType.STATIC_LIBRARY));

    assertHasConfigurations(target, "Debug");
    XCBuildConfiguration configuration = target
        .getBuildConfigurationList().getBuildConfigurationsByName().asMap().get("Debug");
    NSDictionary settings = configuration.getBuildSettings();
    assertEquals(
        new NSString("$SYMROOT/F4XWM33PHJWGSYQ/$CONFIGURATION$EFFECTIVE_PLATFORM_NAME"),
        settings.get("CONFIGURATION_BUILD_DIR"));
    assertEquals(
        new NSString("FooHeaders"),
        settings.get("PUBLIC_HEADERS_FOLDER_PATH"));
  }

  @Test
  public void testAppleLibraryDependentsSearchHeadersAndLibraries() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();

    Path rawXcconfigFile = Paths.get("Test.xcconfig");
    SourcePath xcconfigFile = new PathSourcePath(rawXcconfigFile);
    projectFilesystem.writeContentsToPath("", rawXcconfigFile);

    BuildRule libraryRule;
    BuildRule testRule;

    ImmutableSortedMap<String, XcodeRuleConfiguration> configs =
        ImmutableSortedMap.of(
            "Debug",
            new XcodeRuleConfiguration(
                ImmutableList.of(
                    new XcodeRuleConfigurationLayer(xcconfigFile),
                    new XcodeRuleConfigurationLayer(xcconfigFile))));

    {
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "lib").build())
              .setType(AppleLibraryDescription.TYPE)
              .build();
      AppleNativeTargetDescriptionArg arg =
          createDescriptionArgWithDefaults(appleLibraryDescription);
      arg.configs = Optional.of(configs);
      arg.srcs =
          Optional.of(ImmutableList.of(AppleSource.ofSourcePath(new TestSourcePath("foo.m"))));
      arg.frameworks = Optional.of(ImmutableSortedSet.of("$SDKROOT/Library.framework"));
      libraryRule = appleLibraryDescription.createBuildRule(params, resolver, arg);
      resolver.addToIndex(libraryRule);
    }

    {
      BuildRuleParams dynamicLibraryParams =
          new FakeBuildRuleParamsBuilder(
              BuildTarget.builder("//foo", "testlib")
                  .setFlavor(AppleLibraryDescription.DYNAMIC_LIBRARY)
                  .build())
              .setDeps(ImmutableSortedSet.of(libraryRule))
              .setType(AppleLibraryDescription.TYPE)
              .build();
      AppleNativeTargetDescriptionArg dynamicLibraryArg =
          createDescriptionArgWithDefaults(appleLibraryDescription);
      dynamicLibraryArg.configs = Optional.of(configs);
      dynamicLibraryArg.srcs =
          Optional.of(ImmutableList.of(AppleSource.ofSourcePath(new TestSourcePath("fooTest.m"))));
      dynamicLibraryArg.frameworks = Optional.of(ImmutableSortedSet.of("$SDKROOT/Test.framework"));
      BuildRule dynamicLibraryDep = appleLibraryDescription.createBuildRule(
          dynamicLibraryParams,
          resolver,
          dynamicLibraryArg);
      resolver.addToIndex(dynamicLibraryDep);

      BuildRule xctestRule = createAppleBundleBuildRule(
          BuildTarget.builder("//foo", "xctest").build(),
          resolver,
          appleBundleDescription,
          dynamicLibraryDep,
          AppleBundleExtension.XCTEST);
      resolver.addToIndex(xctestRule);

      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "test").build())
              .setDeps(ImmutableSortedSet.of(xctestRule))
              .setType(AppleTestDescription.TYPE)
              .build();

      AppleTestDescription.Arg arg =
          appleTestDescription.createUnpopulatedConstructorArg();
      arg.testBundle = xctestRule.getBuildTarget();
      arg.contacts = Optional.of(ImmutableSortedSet.<String>of());
      arg.labels = Optional.of(ImmutableSortedSet.<Label>of());
      arg.deps = Optional.of(ImmutableSortedSet.of(xctestRule.getBuildTarget()));
      arg.sourceUnderTest = Optional.of(ImmutableSortedSet.<BuildTarget>of());

      testRule = appleTestDescription.createBuildRule(
          params,
          resolver,
          arg);
      resolver.addToIndex(testRule);
    }

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(libraryRule, testRule),
        ImmutableSet.of(testRule.getBuildTarget()),
        ImmutableSet.of(ProjectGenerator.Option.REFERENCE_EXISTING_XCCONFIGS));

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:xctest");

    assertHasConfigurations(target, "Debug");
    XCBuildConfiguration configuration = target
        .getBuildConfigurationList().getBuildConfigurationsByName().asMap().get("Debug");
    NSDictionary settings = configuration.getBuildSettings();
    assertEquals(
        new NSString("$(inherited) " +
            "$SYMROOT/F4XWM33PHJWGSYQ/Headers"),
        settings.get("HEADER_SEARCH_PATHS"));
    assertEquals(
        new NSString("$(inherited) "),
        settings.get("USER_HEADER_SEARCH_PATHS"));
    assertEquals(
        new NSString("$(inherited) " +
            "$SYMROOT/F4XWM33PHJWGSYQ/$CONFIGURATION$EFFECTIVE_PLATFORM_NAME"),
        settings.get("LIBRARY_SEARCH_PATHS"));
    assertEquals(
        new NSString("$(inherited) " +
            "$SYMROOT/F4XWM33PHJWGSYQ/$CONFIGURATION$EFFECTIVE_PLATFORM_NAME"),
        settings.get("FRAMEWORK_SEARCH_PATHS"));
  }

  @Test
  public void testAppleLibraryDependentsInheritSearchPaths() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();

    Path rawXcconfigFile = Paths.get("Test.xcconfig");
    SourcePath xcconfigFile = new PathSourcePath(rawXcconfigFile);
    projectFilesystem.writeContentsToPath("", rawXcconfigFile);

    BuildRule libraryRule;
    BuildRule testRule;

    ImmutableSortedMap<String, XcodeRuleConfiguration> configs = ImmutableSortedMap.of(
        "Debug",
        new XcodeRuleConfiguration(
            ImmutableList.of(
                new XcodeRuleConfigurationLayer(xcconfigFile),
                new XcodeRuleConfigurationLayer(
                    ImmutableMap.of(
                        "HEADER_SEARCH_PATHS", "headers",
                        "USER_HEADER_SEARCH_PATHS", "user_headers",
                        "LIBRARY_SEARCH_PATHS", "libraries",
                        "FRAMEWORK_SEARCH_PATHS", "frameworks")),
                new XcodeRuleConfigurationLayer(xcconfigFile),
                new XcodeRuleConfigurationLayer(
                    ImmutableMap.of(
                        "HEADER_SEARCH_PATHS", "headers",
                        "USER_HEADER_SEARCH_PATHS", "user_headers",
                        "LIBRARY_SEARCH_PATHS", "libraries",
                        "FRAMEWORK_SEARCH_PATHS", "frameworks")))));

    {
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "lib").build())
              .setType(AppleLibraryDescription.TYPE)
              .build();
      AppleNativeTargetDescriptionArg arg =
          createDescriptionArgWithDefaults(appleLibraryDescription);
      arg.configs = Optional.of(configs);
      arg.srcs =
          Optional.of(ImmutableList.of(AppleSource.ofSourcePath(new TestSourcePath("foo.m"))));
      arg.frameworks = Optional.of(ImmutableSortedSet.of("$SDKROOT/Library.framework"));
      libraryRule = appleLibraryDescription.createBuildRule(params, resolver, arg);
      resolver.addToIndex(libraryRule);
    }

    {
      BuildRuleParams dynamicLibraryParams =
          new FakeBuildRuleParamsBuilder(
              BuildTarget.builder("//foo", "testlib")
                  .setFlavor(AppleLibraryDescription.DYNAMIC_LIBRARY)
                  .build())
              .setDeps(ImmutableSortedSet.of(libraryRule))
              .setType(AppleLibraryDescription.TYPE)
              .build();
      AppleNativeTargetDescriptionArg dynamicLibraryArg =
          createDescriptionArgWithDefaults(appleLibraryDescription);
      dynamicLibraryArg.configs = Optional.of(configs);
      dynamicLibraryArg.srcs =
          Optional.of(ImmutableList.of(AppleSource.ofSourcePath(new TestSourcePath("fooTest.m"))));
      dynamicLibraryArg.frameworks = Optional.of(ImmutableSortedSet.of("$SDKROOT/Test.framework"));
      BuildRule dynamicLibraryDep = appleLibraryDescription.createBuildRule(
          dynamicLibraryParams,
          resolver,
          dynamicLibraryArg);
      resolver.addToIndex(dynamicLibraryDep);

      BuildRule xctestRule = createAppleBundleBuildRule(
          BuildTarget.builder("//foo", "xctest").build(),
          resolver,
          appleBundleDescription,
          dynamicLibraryDep,
          AppleBundleExtension.XCTEST);
      resolver.addToIndex(xctestRule);

      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "test").build())
              .setDeps(ImmutableSortedSet.of(xctestRule))
              .setType(AppleTestDescription.TYPE)
              .build();

      AppleTestDescription.Arg arg =
          appleTestDescription.createUnpopulatedConstructorArg();
      arg.testBundle = xctestRule.getBuildTarget();
      arg.contacts = Optional.of(ImmutableSortedSet.<String>of());
      arg.labels = Optional.of(ImmutableSortedSet.<Label>of());
      arg.deps = Optional.of(ImmutableSortedSet.of(xctestRule.getBuildTarget()));
      arg.sourceUnderTest = Optional.of(ImmutableSortedSet.<BuildTarget>of());

      testRule = appleTestDescription.createBuildRule(
          params,
          resolver,
          arg);
      resolver.addToIndex(testRule);
    }

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(libraryRule, testRule),
        ImmutableSet.of(testRule.getBuildTarget()),
        ImmutableSet.of(ProjectGenerator.Option.REFERENCE_EXISTING_XCCONFIGS));

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:xctest");

    assertHasConfigurations(target, "Debug");
    XCBuildConfiguration configuration = target
        .getBuildConfigurationList().getBuildConfigurationsByName().asMap().get("Debug");
    NSDictionary settings = configuration.getBuildSettings();
    assertEquals(
        new NSString("headers " +
            "$SYMROOT/F4XWM33PHJWGSYQ/Headers"),
        settings.get("HEADER_SEARCH_PATHS"));
    assertEquals(
        new NSString("user_headers "),
        settings.get("USER_HEADER_SEARCH_PATHS"));
    assertEquals(
        new NSString("libraries " +
            "$SYMROOT/F4XWM33PHJWGSYQ/$CONFIGURATION$EFFECTIVE_PLATFORM_NAME"),
        settings.get("LIBRARY_SEARCH_PATHS"));
    assertEquals(
        new NSString("frameworks " +
            "$SYMROOT/F4XWM33PHJWGSYQ/$CONFIGURATION$EFFECTIVE_PLATFORM_NAME"),
        settings.get("FRAMEWORK_SEARCH_PATHS"));
  }

  @Test
  public void testAppleLibraryTransitiveDependentsSearchHeadersAndLibraries() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();

    Path rawXcconfigFile = Paths.get("Test.xcconfig");
    SourcePath xcconfigFile = new PathSourcePath(rawXcconfigFile);
    projectFilesystem.writeContentsToPath("", rawXcconfigFile);

    BuildRule libraryDepRule;
    BuildRule libraryRule;
    BuildRule testRule;

    ImmutableSortedMap<String, XcodeRuleConfiguration> configs =
        ImmutableSortedMap.of(
            "Debug",
            new XcodeRuleConfiguration(
                ImmutableList.of(
                    new XcodeRuleConfigurationLayer(xcconfigFile),
                    new XcodeRuleConfigurationLayer(xcconfigFile))));

    {
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(BuildTarget.builder("//bar", "lib").build())
              .setType(AppleLibraryDescription.TYPE)
              .build();
      AppleNativeTargetDescriptionArg arg =
          createDescriptionArgWithDefaults(appleLibraryDescription);
      arg.configs = Optional.of(configs);
      arg.srcs =
          Optional.of(ImmutableList.of(AppleSource.ofSourcePath(new TestSourcePath("foo.m"))));
      arg.frameworks = Optional.of(ImmutableSortedSet.of("$SDKROOT/Library.framework"));
      libraryDepRule =
          appleLibraryDescription.createBuildRule(params, resolver, arg);
      resolver.addToIndex(libraryDepRule);
    }

    {
      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "lib").build())
              .setDeps(ImmutableSortedSet.of(libraryDepRule))
              .setType(AppleLibraryDescription.TYPE)
              .build();
      AppleNativeTargetDescriptionArg arg =
          createDescriptionArgWithDefaults(appleLibraryDescription);
      arg.configs = Optional.of(configs);
      arg.srcs =
          Optional.of(ImmutableList.of(AppleSource.ofSourcePath(new TestSourcePath("foo.m"))));
      arg.frameworks = Optional.of(ImmutableSortedSet.of("$SDKROOT/Library.framework"));
      libraryRule = appleLibraryDescription.createBuildRule(params, resolver, arg);
      resolver.addToIndex(libraryRule);
    }

    {
      BuildRuleParams dynamicLibraryParams =
          new FakeBuildRuleParamsBuilder(
              BuildTarget.builder("//foo", "testlib")
                  .setFlavor(AppleLibraryDescription.DYNAMIC_LIBRARY)
                  .build())
              .setDeps(ImmutableSortedSet.of(libraryRule))
              .setType(AppleLibraryDescription.TYPE)
              .build();
      AppleNativeTargetDescriptionArg dynamicLibraryArg =
          createDescriptionArgWithDefaults(appleLibraryDescription);
      dynamicLibraryArg.configs = Optional.of(configs);
      dynamicLibraryArg.srcs =
          Optional.of(ImmutableList.of(AppleSource.ofSourcePath(new TestSourcePath("fooTest.m"))));
      dynamicLibraryArg.frameworks = Optional.of(ImmutableSortedSet.of("$SDKROOT/Test.framework"));
      BuildRule dynamicLibraryDep = appleLibraryDescription.createBuildRule(
          dynamicLibraryParams,
          resolver,
          dynamicLibraryArg);
      resolver.addToIndex(dynamicLibraryDep);

      BuildRule xctestRule = createAppleBundleBuildRule(
          BuildTarget.builder("//foo", "xctest").build(),
          resolver,
          appleBundleDescription,
          dynamicLibraryDep,
          AppleBundleExtension.XCTEST);
      resolver.addToIndex(xctestRule);

      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "test").build())
              .setDeps(ImmutableSortedSet.of(xctestRule))
              .setType(AppleTestDescription.TYPE)
              .build();

      AppleTestDescription.Arg arg =
          appleTestDescription.createUnpopulatedConstructorArg();
      arg.testBundle = xctestRule.getBuildTarget();
      arg.contacts = Optional.of(ImmutableSortedSet.<String>of());
      arg.labels = Optional.of(ImmutableSortedSet.<Label>of());
      arg.deps = Optional.of(ImmutableSortedSet.of(xctestRule.getBuildTarget()));
      arg.sourceUnderTest = Optional.of(ImmutableSortedSet.<BuildTarget>of());

      testRule = appleTestDescription.createBuildRule(
          params,
          resolver,
          arg);
      resolver.addToIndex(testRule);
    }

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(libraryRule, testRule),
        ImmutableSet.of(testRule.getBuildTarget()),
        ImmutableSet.of(ProjectGenerator.Option.REFERENCE_EXISTING_XCCONFIGS));

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:xctest");

    assertHasConfigurations(target, "Debug");
    XCBuildConfiguration configuration = target
        .getBuildConfigurationList().getBuildConfigurationsByName().asMap().get("Debug");
    NSDictionary settings = configuration.getBuildSettings();
    assertEquals(
        new NSString("$(inherited) " +
            "$SYMROOT/F4XWEYLSHJWGSYQ/Headers " +
            "$SYMROOT/F4XWM33PHJWGSYQ/Headers"),
        settings.get("HEADER_SEARCH_PATHS"));
    assertEquals(
        new NSString("$(inherited) "),
        settings.get("USER_HEADER_SEARCH_PATHS"));
    assertEquals(
        new NSString("$(inherited) " +
            "$SYMROOT/F4XWEYLSHJWGSYQ/$CONFIGURATION$EFFECTIVE_PLATFORM_NAME " +
            "$SYMROOT/F4XWM33PHJWGSYQ/$CONFIGURATION$EFFECTIVE_PLATFORM_NAME"),
        settings.get("LIBRARY_SEARCH_PATHS"));
    assertEquals(
        new NSString("$(inherited) " +
            "$SYMROOT/F4XWEYLSHJWGSYQ/$CONFIGURATION$EFFECTIVE_PLATFORM_NAME " +
            "$SYMROOT/F4XWM33PHJWGSYQ/$CONFIGURATION$EFFECTIVE_PLATFORM_NAME"),
        settings.get("FRAMEWORK_SEARCH_PATHS"));
  }

  @Test
  public void testAppleTestRule() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildRule dynamicLibraryDep = createBuildRuleWithDefaults(
        BuildTarget.builder("//dep", "dynamic").setFlavor(
            AppleLibraryDescription.DYNAMIC_LIBRARY).build(),
        ImmutableSortedSet.<BuildRule>of(),
        appleLibraryDescription, resolver);
    resolver.addToIndex(dynamicLibraryDep);

    BuildRule xctestRule = createAppleBundleBuildRule(
        BuildTarget.builder("//foo", "xctest").build(),
        resolver,
        appleBundleDescription,
        dynamicLibraryDep,
        AppleBundleExtension.XCTEST);
    resolver.addToIndex(xctestRule);

    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "test").build())
            .setDeps(ImmutableSortedSet.of(xctestRule))
            .setType(AppleTestDescription.TYPE)
            .build();

    AppleTestDescription.Arg arg =
        appleTestDescription.createUnpopulatedConstructorArg();
    arg.testBundle = xctestRule.getBuildTarget();
    arg.contacts = Optional.of(ImmutableSortedSet.<String>of());
    arg.labels = Optional.of(ImmutableSortedSet.<Label>of());
    arg.deps = Optional.of(ImmutableSortedSet.of(xctestRule.getBuildTarget()));
    arg.sourceUnderTest = Optional.of(ImmutableSortedSet.<BuildTarget>of());

    BuildRule rule = appleTestDescription.createBuildRule(
        params,
        resolver,
        arg);
    resolver.addToIndex(rule);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule),
        ImmutableSet.of(rule.getBuildTarget()));
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
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildRule depRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//dep", "dep").build(),
        ImmutableSortedSet.<BuildRule>of(),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(depRule);

    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "binary").build())
            .setDeps(ImmutableSortedSet.of(depRule))
            .setType(AppleBinaryDescription.TYPE)
            .build();
    AppleNativeTargetDescriptionArg arg = appleBinaryDescription.createUnpopulatedConstructorArg();
    arg.configs = Optional.of(
        ImmutableSortedMap.of(
            "Debug", new XcodeRuleConfiguration(ImmutableList.<XcodeRuleConfigurationLayer>of())));
    arg.srcs = Optional.of(
        ImmutableList.of(
            AppleSource.ofSourcePathWithFlags(
                new Pair<SourcePath, String>(new TestSourcePath("foo.m"), "-foo")),
            AppleSource.ofSourcePath(new TestSourcePath("foo.h"))));
    arg.frameworks = Optional.of(ImmutableSortedSet.of("$SDKROOT/Foo.framework"));
    arg.deps = Optional.absent();
    arg.gid = Optional.absent();
    arg.headerPathPrefix = Optional.absent();
    arg.useBuckHeaderMaps = Optional.absent();
    arg.prefixHeader = Optional.absent();

    BuildRule rule = appleBinaryDescription.createBuildRule(params, resolver, arg);
    resolver.addToIndex(rule);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule),
        ImmutableSet.of(rule.getBuildTarget()));
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
            "$BUILT_PRODUCTS_DIR/libdep.a"));

    // this test does not have a dependency on any asset catalogs, so verify no build phase for them
    // exists.
    assertFalse(hasShellScriptPhaseToCompileAssetCatalogs(target));
  }

  @Test
  public void testAppleBundleRuleGathersXcodeNativeDependencies() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildRule fooRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//external", "extFoo").build(),
        ImmutableSortedSet.<BuildRule>of(),
        xcodeNativeDescription,
        resolver);
    resolver.addToIndex(fooRule);

    BuildRule barRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//external", "extBar").build(),
        ImmutableSortedSet.of(fooRule),
        xcodeNativeDescription,
        resolver);
    resolver.addToIndex(barRule);

    BuildRule dynamicLibraryDep = createBuildRuleWithDefaults(
        BuildTarget.builder("//dep", "dynamic").setFlavor(
            AppleLibraryDescription.DYNAMIC_LIBRARY).build(),
        ImmutableSortedSet.of(barRule),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(dynamicLibraryDep);

    BuildRule binaryRule = createAppleBundleBuildRule(
        BuildTarget.builder("//foo", "foo").build(),
        resolver,
        appleBundleDescription,
        dynamicLibraryDep,
        AppleBundleExtension.BUNDLE);
    resolver.addToIndex(binaryRule);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(fooRule, barRule, binaryRule),
        ImmutableSet.of(binaryRule.getBuildTarget()));

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:foo");
    ProjectGeneratorTestUtils.assertHasSingletonFrameworksPhaseWithFrameworkEntries(
        target,
        ImmutableList.of(
            "$BUILT_PRODUCTS_DIR/libextFoo.a",
            "$BUILT_PRODUCTS_DIR/libextBar.a"));
  }

  @Test
  public void testAppleBundleRuleUsesCustomXcodeNativeBuildableNames() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildRule fooRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//external", "extFoo").build(),
        resolver,
        ImmutableSortedSet.<BuildRule>of(),
        xcodeNativeDescription,
        new Function<XcodeNativeDescription.Arg,
            XcodeNativeDescription.Arg>() {
          @Override
          public XcodeNativeDescription.Arg apply(
              XcodeNativeDescription.Arg input) {
            input.buildableName = Optional.of("librickandmorty.a");
            return input;
          }
        });
    resolver.addToIndex(fooRule);

    BuildRule dynamicLibraryDep = createBuildRuleWithDefaults(
        BuildTarget.builder("//dep", "dynamic").setFlavor(
            AppleLibraryDescription.DYNAMIC_LIBRARY).build(),
        ImmutableSortedSet.of(fooRule),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(dynamicLibraryDep);

    BuildRule binaryRule = createAppleBundleBuildRule(
        BuildTarget.builder("//foo", "foo").build(),
        resolver,
        appleBundleDescription,
        dynamicLibraryDep,
        AppleBundleExtension.FRAMEWORK);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(fooRule, binaryRule),
        ImmutableSet.of(binaryRule.getBuildTarget()));

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:foo");
    ProjectGeneratorTestUtils.assertHasSingletonFrameworksPhaseWithFrameworkEntries(
        target,
        ImmutableList.of(
            "$BUILT_PRODUCTS_DIR/librickandmorty.a"));
  }

  @Test
  public void testAppleBundleRuleWithPostBuildScriptDependency() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildRule scriptRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "post_build_script").build(),
        resolver,
        ImmutableSortedSet.<BuildRule>of(),
        iosPostprocessResourcesDescription,
        new Function<IosPostprocessResourcesDescription.Arg,
                     IosPostprocessResourcesDescription.Arg>() {
          @Override
          public IosPostprocessResourcesDescription.Arg apply(
            IosPostprocessResourcesDescription.Arg input) {

            input.cmd = Optional.of("script.sh");
            return input;
          }
        });
    resolver.addToIndex(scriptRule);

    BuildRule resourceRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "resource").build(),
        resolver,
        ImmutableSortedSet.<BuildRule>of(),
        appleResourceDescription,
        new Function<AppleResourceDescription.Arg, AppleResourceDescription.Arg>() {
          @Override
          public AppleResourceDescription.Arg apply(AppleResourceDescription.Arg input) {
            input.files = ImmutableSet.<SourcePath>of(new TestSourcePath("foo.png"));
            return input;
          }
        });
    resolver.addToIndex(resourceRule);

    BuildRule dynamicLibraryDep = createBuildRuleWithDefaults(
        BuildTarget.builder("//dep", "dynamic").setFlavor(
            AppleLibraryDescription.DYNAMIC_LIBRARY).build(),
        ImmutableSortedSet.of(resourceRule),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(dynamicLibraryDep);

    BuildRule bundleRule = createAppleBundleBuildRule(
        BuildTarget.builder("//foo", "bundle").build(),
        resolver,
        appleBundleDescription,
        dynamicLibraryDep,
        AppleBundleExtension.BUNDLE,
        ImmutableList.of(scriptRule));
    resolver.addToIndex(bundleRule);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
      ImmutableSet.of(bundleRule),
      ImmutableSet.of(bundleRule.getBuildTarget()));

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
        equalTo("/bin/bash -e -c script.sh"));

    // Assert that the post-build script phase comes after resources are copied.
    assertThat(
        target.getBuildPhases().get(0),
        instanceOf(PBXResourcesBuildPhase.class));

    assertThat(
        target.getBuildPhases().get(1),
        instanceOf(PBXShellScriptBuildPhase.class));
  }

  @Test
  public void testAppleBundleRuleForDynamicFramework() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();

    SourcePath xcconfigFile = new PathSourcePath(Paths.get("Test.xcconfig"));
    projectFilesystem.writeContentsToPath(
        "",
        new SourcePathResolver(resolver).getPath(xcconfigFile));

    BuildRuleParams dynamicLibraryParams =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//dep", "dynamic").setFlavor(
            AppleLibraryDescription.DYNAMIC_LIBRARY).build())
            .setType(AppleLibraryDescription.TYPE)
            .build();
    AppleNativeTargetDescriptionArg dynamicLibraryArg =
      createDescriptionArgWithDefaults(appleLibraryDescription);
    dynamicLibraryArg.configs = Optional.of(
        ImmutableSortedMap.of(
            "Debug",
            new XcodeRuleConfiguration(
                ImmutableList.of(
                    new XcodeRuleConfigurationLayer(xcconfigFile),
                    new XcodeRuleConfigurationLayer(xcconfigFile)))));
    BuildRule dynamicLibraryDep = appleLibraryDescription.createBuildRule(
        dynamicLibraryParams, resolver, dynamicLibraryArg);
    resolver.addToIndex(dynamicLibraryDep);

    BuildRule rule = createAppleBundleBuildRule(
        BuildTarget.builder("//foo", "bundle").build(),
        resolver,
        appleBundleDescription,
        dynamicLibraryDep,
        AppleBundleExtension.FRAMEWORK);
    resolver.addToIndex(rule);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSortedSet.of(rule),
        ImmutableSet.of(rule.getBuildTarget()),
        ImmutableSet.of(ProjectGenerator.Option.REFERENCE_EXISTING_XCCONFIGS));
    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXTarget target = assertTargetExistsAndReturnTarget(project, "//foo:bundle");
    assertEquals(target.getProductType(), PBXTarget.ProductType.FRAMEWORK);
    assertThat(target.isa(), equalTo("PBXNativeTarget"));
    PBXFileReference productReference = target.getProductReference();
    assertEquals("bundle.framework", productReference.getName());
    assertEquals(Optional.of("wrapper.framework"), productReference.getExplicitFileType());

    assertHasConfigurations(target, "Debug");
    XCBuildConfiguration configuration =
        target.getBuildConfigurationList().getBuildConfigurationsByName().asMap().get("Debug");
    NSDictionary settings = configuration.getBuildSettings();
    assertEquals(
        new NSString("framework"),
        settings.get("WRAPPER_EXTENSION"));
  }

  @Test
  public void testCoreDataModelRuleAddsReference() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildRule modelRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "model").build(),
        resolver,
        ImmutableSortedSet.<BuildRule>of(),
        coreDataModelDescription,
        new Function<CoreDataModelDescription.Arg, CoreDataModelDescription.Arg>() {
          @Override
          public CoreDataModelDescription.Arg apply(CoreDataModelDescription.Arg args) {
            args.path = new TestSourcePath("foo.xcdatamodel").getRelativePath();
            return args;
          }
        });
    resolver.addToIndex(modelRule);

    BuildRule libraryRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "lib").build(),
        ImmutableSortedSet.of(modelRule),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(libraryRule);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(libraryRule),
        ImmutableSet.of(libraryRule.getBuildTarget()));

    projectGenerator.createXcodeProjects();

    PBXProject project = projectGenerator.getGeneratedProject();
    PBXGroup targetGroup =
        project.getMainGroup().getOrCreateChildGroupByName(libraryRule.getFullyQualifiedName());
    PBXGroup resourcesGroup = targetGroup.getOrCreateChildGroupByName("Resources");

    assertThat(resourcesGroup.getChildren(), hasSize(1));

    PBXFileReference modelReference = (PBXFileReference) Iterables.get(
        resourcesGroup.getChildren(),
        0);
    assertEquals("foo.xcdatamodel", modelReference.getName());
  }

  @Test
  public void ruleToTargetMapContainsPBXTarget() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "lib").build())
            .setType(AppleLibraryDescription.TYPE)
            .build();
    AppleNativeTargetDescriptionArg arg = createDescriptionArgWithDefaults(appleLibraryDescription);
    arg.configs = Optional.of(
        ImmutableSortedMap.of(
            "Debug", new XcodeRuleConfiguration(ImmutableList.<XcodeRuleConfigurationLayer>of())));
    arg.srcs = Optional.of(
        ImmutableList.of(
            AppleSource.ofSourcePathWithFlags(
                new Pair<SourcePath, String>(new TestSourcePath("foo.m"), "-foo")),
            AppleSource.ofSourcePath(new TestSourcePath("foo.h")),
            AppleSource.ofSourcePath(new TestSourcePath("bar.m"))));
    BuildRule rule = appleLibraryDescription.createBuildRule(params, resolver, arg);
    resolver.addToIndex(rule);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule),
        ImmutableSet.of(rule.getBuildTarget()));

    projectGenerator.createXcodeProjects();

    assertEquals(rule.getBuildTarget(), Iterables.getOnlyElement(
            projectGenerator.getBuildTargetToGeneratedTargetMap().keySet()));

    PBXTarget target = Iterables.getOnlyElement(
        projectGenerator.getBuildTargetToGeneratedTargetMap().values());
    assertHasSingletonSourcesPhaseWithSourcesAndFlags(
        target, ImmutableMap.of(
            "foo.m", Optional.of("-foo"),
            "bar.m", Optional.<String>absent()));
  }

  @Test
  public void shouldDiscoverDependenciesAndTests() throws IOException {
    // Create the following dep tree:
    // FooBin -has-test-> FooBinTest
    // |
    // V
    // FooLib -has-test-> FooLibTest
    // |                  |
    // V                  V
    // BarLib             BazLib -has-test-> BazLibTest
    //
    // Calling generate on FooBin should pull in everything except BazLibTest

    BuildRuleResolver resolver = new BuildRuleResolver();

    final BuildRule barLib = createBuildRuleWithDefaults(
        BuildTarget.builder("//bar", "lib").build(),
        ImmutableSortedSet.<BuildRule>of(),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(barLib);

    final BuildRule fooLib = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "lib").build(),
        ImmutableSortedSet.of(barLib),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(fooLib);

    BuildRule fooBinBinary = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "binbinary").build(),
        ImmutableSortedSet.of(fooLib),
        appleBinaryDescription,
        resolver);
    resolver.addToIndex(fooBinBinary);

    BuildRule fooBin = createAppleBundleBuildRule(
        BuildTarget.builder("//foo", "bin").build(),
        resolver,
        appleBundleDescription,
        fooBinBinary,
        AppleBundleExtension.APP);
    resolver.addToIndex(fooBin);

    final BuildRule bazLib = createBuildRuleWithDefaults(
        BuildTarget.builder("//baz", "lib").build(),
        ImmutableSortedSet.of(fooLib),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(bazLib);

    BuildRule bazTest;
    {
      BuildRuleParams dynamicLibraryParams =
          new FakeBuildRuleParamsBuilder(
              BuildTarget.builder("//baz", "testlib")
                  .setFlavor(AppleLibraryDescription.DYNAMIC_LIBRARY)
                  .build())
              .setDeps(ImmutableSortedSet.of(bazLib))
              .setType(AppleLibraryDescription.TYPE)
              .build();
      AppleNativeTargetDescriptionArg dynamicLibraryArg =
          createDescriptionArgWithDefaults(appleLibraryDescription);
      BuildRule dynamicLibraryDep = appleLibraryDescription.createBuildRule(
          dynamicLibraryParams,
          resolver,
          dynamicLibraryArg);
      resolver.addToIndex(dynamicLibraryDep);

      BuildRule xctestRule = createAppleBundleBuildRule(
          BuildTarget.builder("//foo", "xctest").build(),
          resolver,
          appleBundleDescription,
          dynamicLibraryDep,
          AppleBundleExtension.XCTEST);
      resolver.addToIndex(xctestRule);

      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(BuildTarget.builder("//baz", "test").build())
              .setDeps(ImmutableSortedSet.of(xctestRule))
              .setType(AppleTestDescription.TYPE)
              .build();

      AppleTestDescription.Arg arg =
          appleTestDescription.createUnpopulatedConstructorArg();
      arg.testBundle = xctestRule.getBuildTarget();
      arg.contacts = Optional.of(ImmutableSortedSet.<String>of());
      arg.labels = Optional.of(ImmutableSortedSet.<Label>of());
      arg.deps = Optional.of(ImmutableSortedSet.of(xctestRule.getBuildTarget()));
      arg.sourceUnderTest = Optional.of(ImmutableSortedSet.of(bazLib.getBuildTarget()));

      bazTest = appleTestDescription.createBuildRule(
          params,
          resolver,
          arg);
      resolver.addToIndex(bazTest);
    }
    final BuildRule bazLibTest = bazTest;

    BuildRule fooLibTest;
    {
      BuildRuleParams dynamicLibraryParams =
          new FakeBuildRuleParamsBuilder(
              BuildTarget.builder("//foo", "lib-testlib")
                  .setFlavor(AppleLibraryDescription.DYNAMIC_LIBRARY)
                  .build())
              .setDeps(ImmutableSortedSet.of(bazLib))
              .setType(AppleLibraryDescription.TYPE)
              .build();
      AppleNativeTargetDescriptionArg dynamicLibraryArg =
          createDescriptionArgWithDefaults(appleLibraryDescription);
      BuildRule dynamicLibraryDep = appleLibraryDescription.createBuildRule(
          dynamicLibraryParams,
          resolver,
          dynamicLibraryArg);
      resolver.addToIndex(dynamicLibraryDep);

      BuildRule xctestRule = createAppleBundleBuildRule(
          BuildTarget.builder("//foo", "lib-xctest").build(),
          resolver,
          appleBundleDescription,
          dynamicLibraryDep,
          AppleBundleExtension.XCTEST);
      resolver.addToIndex(xctestRule);

      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "lib-test").build())
              .setDeps(ImmutableSortedSet.of(xctestRule))
              .setType(AppleTestDescription.TYPE)
              .build();

      AppleTestDescription.Arg arg =
          appleTestDescription.createUnpopulatedConstructorArg();
      arg.testBundle = xctestRule.getBuildTarget();
      arg.contacts = Optional.of(ImmutableSortedSet.<String>of());
      arg.labels = Optional.of(ImmutableSortedSet.<Label>of());
      arg.deps = Optional.of(ImmutableSortedSet.of(xctestRule.getBuildTarget()));
      arg.sourceUnderTest = Optional.of(ImmutableSortedSet.of(bazLib.getBuildTarget()));

      fooLibTest = appleTestDescription.createBuildRule(
          params,
          resolver,
          arg);
      resolver.addToIndex(fooLibTest);
    }

    BuildRule fooBinTest;
    {
      BuildRuleParams dynamicLibraryParams =
          new FakeBuildRuleParamsBuilder(
              BuildTarget.builder("//foo", "bin-testlib")
                  .setFlavor(AppleLibraryDescription.DYNAMIC_LIBRARY)
                  .build())
              .setDeps(ImmutableSortedSet.of(bazLib))
              .setType(AppleLibraryDescription.TYPE)
              .build();
      AppleNativeTargetDescriptionArg dynamicLibraryArg =
          createDescriptionArgWithDefaults(appleLibraryDescription);
      BuildRule dynamicLibraryDep = appleLibraryDescription.createBuildRule(
          dynamicLibraryParams,
          resolver,
          dynamicLibraryArg);
      resolver.addToIndex(dynamicLibraryDep);

      BuildRule xctestRule = createAppleBundleBuildRule(
          BuildTarget.builder("//foo", "bin-xctest").build(),
          resolver,
          appleBundleDescription,
          dynamicLibraryDep,
          AppleBundleExtension.XCTEST);
      resolver.addToIndex(xctestRule);

      BuildRuleParams params =
          new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "bin-test").build())
              .setDeps(ImmutableSortedSet.of(xctestRule))
              .setType(AppleTestDescription.TYPE)
              .build();

      AppleTestDescription.Arg arg =
          appleTestDescription.createUnpopulatedConstructorArg();
      arg.testBundle = xctestRule.getBuildTarget();
      arg.contacts = Optional.of(ImmutableSortedSet.<String>of());
      arg.labels = Optional.of(ImmutableSortedSet.<Label>of());
      arg.deps = Optional.of(ImmutableSortedSet.of(xctestRule.getBuildTarget()));
      arg.sourceUnderTest = Optional.of(ImmutableSortedSet.of(bazLib.getBuildTarget()));

      fooBinTest = appleTestDescription.createBuildRule(
          params,
          resolver,
          arg);
      resolver.addToIndex(fooBinTest);
    }

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(barLib, fooLib, fooBin, bazLib, bazLibTest, fooLibTest, fooBinTest),
        ImmutableSet.of(fooBin.getBuildTarget()));
    projectGenerator.createXcodeProjects();

    assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:bin");
    assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:lib");
    assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//bar:lib");
    assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:bin-xctest");
    assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:lib-xctest");
    assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//baz:lib");
  }

  @Test
  public void generatedGidsForTargetsAreStable() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildRule fooLib = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "foo").build(),
        ImmutableSortedSet.<BuildRule>of(),
        appleLibraryDescription,
        resolver);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(fooLib),
        ImmutableSet.of(fooLib.getBuildTarget()));
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
  public void stopsLinkingRecursiveDependenciesAtDynamicLibraries() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildRule dependentStaticLibrary = createBuildRuleWithDefaults(
        BuildTarget.builder("//dep", "static").build(),
        ImmutableSortedSet.<BuildRule>of(),
        appleLibraryDescription,
        resolver);

    BuildTarget dependentDynamicLibraryTarget = BuildTarget
        .builder("//dep", "dynamic")
        .setFlavor(AppleLibraryDescription.DYNAMIC_LIBRARY)
        .build();
    BuildRuleParams dependentDynamicLibraryParams =
        new FakeBuildRuleParamsBuilder(dependentDynamicLibraryTarget)
            .setDeps(ImmutableSortedSet.of(dependentStaticLibrary))
            .setType(AppleLibraryDescription.TYPE)
            .build();

    AppleNativeTargetDescriptionArg dependentDynamicLibraryArg =
      createDescriptionArgWithDefaults(appleLibraryDescription);
    BuildRule dependentDynamicLibrary = appleLibraryDescription.createBuildRule(
        dependentDynamicLibraryParams,
        resolver,
        dependentDynamicLibraryArg);
    resolver.addToIndex(dependentDynamicLibrary);

    BuildTarget libraryTarget = BuildTarget
        .builder("//foo", "library")
        .setFlavor(AppleLibraryDescription.DYNAMIC_LIBRARY)
        .build();
    BuildRuleParams libraryParams =
        new FakeBuildRuleParamsBuilder(libraryTarget)
            .setDeps(ImmutableSortedSet.of(dependentDynamicLibrary))
            .setType(AppleLibraryDescription.TYPE)
            .build();

    AppleNativeTargetDescriptionArg libraryArg =
        createDescriptionArgWithDefaults(appleLibraryDescription);
    BuildRule library = appleLibraryDescription.createBuildRule(
        libraryParams,
        resolver,
        libraryArg);
    resolver.addToIndex(library);

    BuildRule bundle = createAppleBundleBuildRule(
        BuildTarget.builder("//foo", "final").build(),
        resolver,
        appleBundleDescription,
        library,
        AppleBundleExtension.BUNDLE);
    resolver.addToIndex(bundle);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(bundle),
        ImmutableSet.of(bundle.getBuildTarget()));
    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:final");
    assertEquals(target.getProductType(), PBXTarget.ProductType.BUNDLE);
    assertEquals("Should have exact number of build phases ", 2, target.getBuildPhases().size());
    ProjectGeneratorTestUtils.assertHasSingletonFrameworksPhaseWithFrameworkEntries(
        target, ImmutableList.of("$BUILT_PRODUCTS_DIR/dynamic.dylib"));
  }

  @Test
  public void stopsLinkingRecursiveDependenciesAtBundles() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildRule dependentStaticLibrary = createBuildRuleWithDefaults(
        BuildTarget.builder("//dep", "static").build(),
        ImmutableSortedSet.<BuildRule>of(),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(dependentStaticLibrary);

    BuildTarget dependentDynamicLibraryTarget = BuildTarget
        .builder("//dep", "dynamic")
        .setFlavor(AppleLibraryDescription.DYNAMIC_LIBRARY)
        .build();
    BuildRuleParams dependentDynamicLibraryParams =
        new FakeBuildRuleParamsBuilder(dependentDynamicLibraryTarget)
            .setDeps(ImmutableSortedSet.of(dependentStaticLibrary))
            .setType(AppleLibraryDescription.TYPE)
            .build();

    AppleNativeTargetDescriptionArg dependentDynamicLibraryArg =
        createDescriptionArgWithDefaults(appleLibraryDescription);
    BuildRule dependentDynamicLibrary = appleLibraryDescription.createBuildRule(
        dependentDynamicLibraryParams,
        resolver,
        dependentDynamicLibraryArg);
    resolver.addToIndex(dependentDynamicLibrary);

    BuildRule dependentFramework = createAppleBundleBuildRule(
        BuildTarget.builder("//dep", "framework").build(),
        resolver,
        appleBundleDescription,
        dependentDynamicLibrary,
        AppleBundleExtension.FRAMEWORK);
    resolver.addToIndex(dependentFramework);

    BuildTarget libraryTarget = BuildTarget
        .builder("//foo", "library")
        .setFlavor(AppleLibraryDescription.DYNAMIC_LIBRARY)
        .build();
    BuildRuleParams libraryParams =
        new FakeBuildRuleParamsBuilder(libraryTarget)
            .setDeps(ImmutableSortedSet.of(dependentFramework))
            .setType(AppleLibraryDescription.TYPE)
            .build();

    AppleNativeTargetDescriptionArg libraryArg =
        createDescriptionArgWithDefaults(appleLibraryDescription);
    BuildRule library = appleLibraryDescription.createBuildRule(
        libraryParams,
        resolver,
        libraryArg);
    resolver.addToIndex(library);

    BuildRule bundle = createAppleBundleBuildRule(
        BuildTarget.builder("//foo", "final").build(),
        resolver,
        appleBundleDescription,
        library,
        AppleBundleExtension.BUNDLE);
    resolver.addToIndex(bundle);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(bundle),
        ImmutableSet.of(bundle.getBuildTarget()));
    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:final");
    assertEquals(target.getProductType(), PBXTarget.ProductType.BUNDLE);
    assertEquals("Should have exact number of build phases ", 2, target.getBuildPhases().size());
    ProjectGeneratorTestUtils.assertHasSingletonFrameworksPhaseWithFrameworkEntries(
        target, ImmutableList.of("$BUILT_PRODUCTS_DIR/framework.framework"));
  }

  @Test
  public void stopsCopyingRecursiveDependenciesAtBundles() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildRule dependentStaticLibrary = createBuildRuleWithDefaults(
        BuildTarget.builder("//dep", "static").build(),
        ImmutableSortedSet.<BuildRule>of(),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(dependentStaticLibrary);

    BuildRule dependentStaticFramework = createAppleBundleBuildRule(
        BuildTarget.builder("//dep", "static-framework").build(),
        resolver,
        appleBundleDescription,
        dependentStaticLibrary,
        AppleBundleExtension.FRAMEWORK);
    resolver.addToIndex(dependentStaticFramework);

    BuildTarget dependentDynamicLibraryTarget = BuildTarget
        .builder("//dep", "dynamic")
        .setFlavor(AppleLibraryDescription.DYNAMIC_LIBRARY)
        .build();
    BuildRuleParams dependentDynamicLibraryParams =
        new FakeBuildRuleParamsBuilder(dependentDynamicLibraryTarget)
            .setDeps(ImmutableSortedSet.of(dependentStaticFramework))
            .setType(AppleLibraryDescription.TYPE)
            .build();
    AppleNativeTargetDescriptionArg dependentDynamicLibraryArg =
        createDescriptionArgWithDefaults(appleLibraryDescription);
    BuildRule dependentDynamicLibrary = appleLibraryDescription.createBuildRule(
        dependentDynamicLibraryParams,
        resolver,
        dependentDynamicLibraryArg);
    resolver.addToIndex(dependentDynamicLibrary);

    BuildRule dependentFramework = createAppleBundleBuildRule(
        BuildTarget.builder("//dep", "framework").build(),
        resolver,
        appleBundleDescription,
        dependentDynamicLibrary,
        AppleBundleExtension.FRAMEWORK);
    resolver.addToIndex(dependentFramework);

    BuildTarget libraryTarget = BuildTarget
        .builder("//foo", "library")
        .setFlavor(AppleLibraryDescription.DYNAMIC_LIBRARY)
        .build();
    BuildRuleParams libraryParams =
        new FakeBuildRuleParamsBuilder(libraryTarget)
            .setDeps(ImmutableSortedSet.of(dependentFramework))
            .setType(AppleLibraryDescription.TYPE)
            .build();

    AppleNativeTargetDescriptionArg libraryArg =
        createDescriptionArgWithDefaults(appleLibraryDescription);
    BuildRule library = appleLibraryDescription.createBuildRule(
        libraryParams,
        resolver,
        libraryArg);
    resolver.addToIndex(library);

    BuildRule bundle = createAppleBundleBuildRule(
        BuildTarget.builder("//foo", "final").build(),
        resolver,
        appleBundleDescription,
        library,
        AppleBundleExtension.BUNDLE);
    resolver.addToIndex(bundle);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(bundle),
        ImmutableSet.of(bundle.getBuildTarget()));
    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:final");
    assertEquals(target.getProductType(), PBXTarget.ProductType.BUNDLE);
    assertEquals("Should have exact number of build phases ", 2, target.getBuildPhases().size());
    ProjectGeneratorTestUtils.assertHasSingletonCopyFilesPhaseWithFileEntries(
        target, ImmutableList.of("$BUILT_PRODUCTS_DIR/framework.framework"));
  }

  @Test
  public void bundlesDontLinkTheirOwnBinary() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildTarget libraryTarget = BuildTarget
        .builder("//foo", "library")
        .setFlavor(AppleLibraryDescription.DYNAMIC_LIBRARY)
        .build();
    BuildRuleParams libraryParams =
        new FakeBuildRuleParamsBuilder(libraryTarget)
            .setDeps(ImmutableSortedSet.<BuildRule>of())
            .setType(AppleLibraryDescription.TYPE)
            .build();

    AppleNativeTargetDescriptionArg libraryArg =
        createDescriptionArgWithDefaults(appleLibraryDescription);
    BuildRule library = appleLibraryDescription.createBuildRule(
        libraryParams,
        resolver,
        libraryArg);
    resolver.addToIndex(library);

    BuildRule bundle = createAppleBundleBuildRule(
        BuildTarget.builder("//foo", "final").build(),
        resolver,
        appleBundleDescription,
        library,
        AppleBundleExtension.BUNDLE);
    resolver.addToIndex(bundle);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(bundle),
        ImmutableSet.of(bundle.getBuildTarget()));
    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:final");
    assertEquals(target.getProductType(), PBXTarget.ProductType.BUNDLE);
    assertEquals("Should have exact number of build phases ", 0, target.getBuildPhases().size());
  }

  @Test
  public void resourcesInDependenciesPropagatesToBundles() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildRule resourceRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "res").build(),
        resolver,
        ImmutableSortedSet.<BuildRule>of(),
        appleResourceDescription,
        new Function<AppleResourceDescription.Arg, AppleResourceDescription.Arg>() {
          @Override
          public AppleResourceDescription.Arg apply(AppleResourceDescription.Arg input) {
            input.files = ImmutableSet.<SourcePath>of(new TestSourcePath("foo.png"));
            input.dirs = ImmutableSet.of(Paths.get("foodir"));
            return input;
          }
        });
    resolver.addToIndex(resourceRule);

    BuildRule libraryRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "lib").build(),
        ImmutableSortedSet.of(resourceRule),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(libraryRule);

    BuildRule bundleLibraryRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "bundlelib").build(),
        ImmutableSortedSet.of(libraryRule),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(bundleLibraryRule);

    BuildRule bundleRule = createAppleBundleBuildRule(
        BuildTarget.builder("//foo", "bundle").build(),
        resolver,
        appleBundleDescription,
        bundleLibraryRule,
        AppleBundleExtension.BUNDLE);
    resolver.addToIndex(bundleRule);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(resourceRule, libraryRule, bundleRule),
        ImmutableSet.of(bundleRule.getBuildTarget()));
    projectGenerator.createXcodeProjects();

    PBXProject generatedProject = projectGenerator.getGeneratedProject();
    PBXTarget bundleTarget = assertTargetExistsAndReturnTarget(
        generatedProject,
        "//foo:bundle");
    assertHasSingletonResourcesPhaseWithEntries(bundleTarget, "foo.png", "foodir");
  }

  @Test
  public void assetCatalogsInDependenciesPropogatesToBundles() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildRule assetCatalogRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "asset_catalog").build(),
        resolver,
        ImmutableSortedSet.<BuildRule>of(),
        new AppleAssetCatalogDescription(),
        new Function<AppleAssetCatalogDescription.Arg, AppleAssetCatalogDescription.Arg>() {
          @Override
          public AppleAssetCatalogDescription.Arg apply(AppleAssetCatalogDescription.Arg input) {
            input.dirs = ImmutableSet.of(Paths.get("AssetCatalog.xcassets"));
            return input;
          }
        });
    resolver.addToIndex(assetCatalogRule);

    BuildRule libraryRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "lib").build(),
        ImmutableSortedSet.of(assetCatalogRule),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(libraryRule);

    BuildRule bundleLibraryRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "bundlelib").build(),
        ImmutableSortedSet.of(libraryRule),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(bundleLibraryRule);

    BuildRule bundleRule = createAppleBundleBuildRule(
        BuildTarget.builder("//foo", "bundle").build(),
        resolver,
        appleBundleDescription,
        bundleLibraryRule,
        AppleBundleExtension.BUNDLE);
    resolver.addToIndex(bundleRule);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(assetCatalogRule, libraryRule, bundleRule),
        ImmutableSet.of(bundleRule.getBuildTarget()));
    projectGenerator.createXcodeProjects();

    PBXProject generatedProject = projectGenerator.getGeneratedProject();
    PBXTarget bundleTarget = assertTargetExistsAndReturnTarget(
        generatedProject,
        "//foo:bundle");
    assertTrue(hasShellScriptPhaseToCompileAssetCatalogs(bundleTarget));
  }

  /**
   * The project configurations should have named entries corresponding to every existing target
   * configuration for targets in the project.
   */
  @Test
  public void generatedProjectConfigurationListIsUnionOfAllTargetConfigurations()
      throws IOException {
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
                    "Conf1",
                    new XcodeRuleConfiguration(ImmutableList.<XcodeRuleConfigurationLayer>of()),
                    "Conf2",
                    new XcodeRuleConfiguration(ImmutableList.<XcodeRuleConfigurationLayer>of())));
            return input;
          }
        });

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
                    "Conf2",
                    new XcodeRuleConfiguration(ImmutableList.<XcodeRuleConfigurationLayer>of()),
                    "Conf3",
                    new XcodeRuleConfiguration(ImmutableList.<XcodeRuleConfigurationLayer>of())));
            return input;
          }
        });

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule1, rule2),
        ImmutableSet.of(rule1.getBuildTarget(), rule2.getBuildTarget()));
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
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildRule rule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "rule")
            .setFlavor(AppleLibraryDescription.DYNAMIC_LIBRARY)
            .build(),
        resolver,
        ImmutableSortedSet.<BuildRule>of(),
        appleLibraryDescription,
        new Function<AppleNativeTargetDescriptionArg, AppleNativeTargetDescriptionArg>() {
          @Override
          public AppleNativeTargetDescriptionArg apply(AppleNativeTargetDescriptionArg input) {
            input.frameworks = Optional.of(
                ImmutableSortedSet.of(
                    "$BUILT_PRODUCTS_DIR/libfoo.a",
                    "$SDKROOT/libfoo.a",
                    "$SOURCE_ROOT/libfoo.a"));
            return input;
          }
        });
    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule),
        ImmutableSet.of(rule.getBuildTarget()));
    projectGenerator.createXcodeProjects();

    PBXProject generatedProject = projectGenerator.getGeneratedProject();
    PBXTarget target = assertTargetExistsAndReturnTarget(generatedProject, "//foo:rule#dynamic");
    assertHasSingletonFrameworksPhaseWithFrameworkEntries(
        target,
        ImmutableList.of(
            "$BUILT_PRODUCTS_DIR/libfoo.a",
            "$SDKROOT/libfoo.a",
            "$SOURCE_ROOT/libfoo.a"));
  }

  @Test(expected = HumanReadableException.class)
  public void shouldRejectUnknownBuildSettingsInFrameworkEntries() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildRule rule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "rule")
            .setFlavor(AppleLibraryDescription.DYNAMIC_LIBRARY)
            .build(),
        resolver,
        ImmutableSortedSet.<BuildRule>of(),
        appleLibraryDescription,
        new Function<AppleNativeTargetDescriptionArg, AppleNativeTargetDescriptionArg>() {
          @Override
          public AppleNativeTargetDescriptionArg apply(AppleNativeTargetDescriptionArg input) {
            input.frameworks = Optional.of(ImmutableSortedSet.of("$FOOBAR/libfoo.a"));
            return input;
          }
        }
    );

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule),
        ImmutableSet.of(rule.getBuildTarget()));
    projectGenerator.createXcodeProjects();
  }

  @Test
  public void testGeneratedProjectIsNotReadOnlyIfOptionNotSpecified() throws IOException {
    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<BuildRule>of(),
        ImmutableSet.<BuildTarget>of());

    projectGenerator.createXcodeProjects();

    assertTrue(fakeProjectFilesystem.getFileAttributesAtPath(OUTPUT_PROJECT_FILE_PATH).isEmpty());
  }

  @Test
  public void testGeneratedProjectIsReadOnlyIfOptionSpecified() throws IOException {
    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<BuildRule>of(),
        ImmutableSet.<BuildTarget>of(),
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
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "lib").build())
            .setType(AppleLibraryDescription.TYPE)
            .build();
    AppleNativeTargetDescriptionArg arg = createDescriptionArgWithDefaults(appleLibraryDescription);
    arg.gid = Optional.of("D00D64738");
    BuildRule rule = appleLibraryDescription.createBuildRule(params, resolver, arg);
    resolver.addToIndex(rule);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(rule),
        ImmutableSet.of(rule.getBuildTarget()));

    projectGenerator.createXcodeProjects();

    PBXTarget target = assertTargetExistsAndReturnTarget(
        projectGenerator.getGeneratedProject(),
        "//foo:lib");
    // Ensure the GID for the target uses the gid value in the description.
    assertThat(target.getGlobalID(), equalTo("D00D64738"));
  }

  @Test
  public void targetGidInDescriptionReservesGidFromUseByAnotherTarget() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildRuleParams fooParams =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "lib").build())
            .setType(AppleLibraryDescription.TYPE)
            .build();
    AppleNativeTargetDescriptionArg fooArg =
      createDescriptionArgWithDefaults(appleLibraryDescription);
    fooArg.gid = Optional.of("E66DC04E36F2D8BE00000000");
    BuildRule fooRule =
      appleLibraryDescription.createBuildRule(fooParams, resolver, fooArg);
    resolver.addToIndex(fooRule);

    BuildRuleParams barParams =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//bar", "lib").build())
            .setType(AppleLibraryDescription.TYPE)
            .build();
    AppleNativeTargetDescriptionArg barArg =
        createDescriptionArgWithDefaults(appleLibraryDescription);
    BuildRule barRule =
      appleLibraryDescription.createBuildRule(barParams, resolver, barArg);
    resolver.addToIndex(barRule);

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(fooRule, barRule),
        ImmutableSet.of(fooRule.getBuildTarget(), barRule.getBuildTarget()));
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
  public void projectIsRewrittenIfContentsHaveChanged() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();

    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<BuildRule>of(),
        ImmutableSet.<BuildTarget>of());

    clock.setCurrentTimeMillis(49152);
    projectGenerator.createXcodeProjects();
    assertThat(
        projectFilesystem.getLastModifiedTime(OUTPUT_PROJECT_FILE_PATH),
        equalTo(49152L));

    BuildRule fooLib = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "foo").build(),
        ImmutableSortedSet.<BuildRule>of(),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(fooLib);

    ProjectGenerator projectGenerator2 = createProjectGeneratorForCombinedProject(
        ImmutableSet.of(fooLib),
        ImmutableSet.of(fooLib.getBuildTarget()));

    clock.setCurrentTimeMillis(64738);
    projectGenerator2.createXcodeProjects();
    assertThat(
        projectFilesystem.getLastModifiedTime(OUTPUT_PROJECT_FILE_PATH),
        equalTo(64738L));
  }

  @Test
  public void projectIsNotRewrittenIfContentsHaveNotChanged() throws IOException {
    ProjectGenerator projectGenerator = createProjectGeneratorForCombinedProject(
        ImmutableSet.<BuildRule>of(),
        ImmutableSet.<BuildTarget>of());

    clock.setCurrentTimeMillis(49152);
    projectGenerator.createXcodeProjects();
    assertThat(
        projectFilesystem.getLastModifiedTime(OUTPUT_PROJECT_FILE_PATH),
        equalTo(49152L));

    ProjectGenerator projectGenerator2 = createProjectGeneratorForCombinedProject(
        ImmutableSet.<BuildRule>of(),
        ImmutableSet.<BuildTarget>of());

    clock.setCurrentTimeMillis(64738);
    projectGenerator2.createXcodeProjects();
    assertThat(
        projectFilesystem.getLastModifiedTime(OUTPUT_PROJECT_FILE_PATH),
        equalTo(49152L));
  }

  private ProjectGenerator createProjectGeneratorForCombinedProject(
      Iterable<BuildRule> rulesToBuild,
      ImmutableSet<BuildTarget> initialBuildTargets) {
    return createProjectGeneratorForCombinedProject(
        rulesToBuild,
        initialBuildTargets,
        ImmutableSet.<ProjectGenerator.Option>of());
  }

  private ProjectGenerator createProjectGeneratorForCombinedProject(
      Iterable<BuildRule> rulesToBuild,
      ImmutableSet<BuildTarget> initialBuildTargets,
      ImmutableSet<ProjectGenerator.Option> projectGeneratorOptions) {
    ImmutableSet<ProjectGenerator.Option> options = ImmutableSet.<ProjectGenerator.Option>builder()
        .addAll(projectGeneratorOptions)
        .addAll(ProjectGenerator.COMBINED_PROJECT_OPTIONS)
        .build();

    return new ProjectGenerator(
        new SourcePathResolver(new BuildRuleResolver()),
        rulesToBuild,
        initialBuildTargets,
        projectFilesystem,
        executionContext,
        OUTPUT_DIRECTORY,
        PROJECT_NAME,
        options);
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
}
