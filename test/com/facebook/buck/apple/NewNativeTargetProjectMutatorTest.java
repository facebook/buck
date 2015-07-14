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

package com.facebook.buck.apple;

import static com.facebook.buck.apple.ProjectGeneratorTestUtils.assertHasSingletonFrameworksPhaseWithFrameworkEntries;
import static com.facebook.buck.apple.ProjectGeneratorTestUtils.assertHasSingletonPhaseWithEntries;
import static com.facebook.buck.apple.ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget;
import static com.facebook.buck.apple.ProjectGeneratorTestUtils.createDescriptionArgWithDefaults;
import static com.facebook.buck.apple.ProjectGeneratorTestUtils.getSingletonPhaseByType;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.hamcrest.collection.IsEmptyIterable.emptyIterable;
import static org.hamcrest.core.IsNot.not;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.dd.plist.NSArray;
import com.dd.plist.NSDictionary;
import com.dd.plist.NSString;
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
import com.facebook.buck.apple.xcode.xcodeproj.ProductType;
import com.facebook.buck.apple.xcode.xcodeproj.SourceTreePath;
import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.js.IosReactNativeLibraryBuilder;
import com.facebook.buck.js.ReactNativeBuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.AllExistingProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import org.junit.Before;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.Set;

public class NewNativeTargetProjectMutatorTest {
  private BuildRuleResolver buildRuleResolver;
  private PBXProject generatedProject;
  private PathRelativizer pathRelativizer;
  private SourcePathResolver sourcePathResolver;

  @Before
  public void setUp() {
    buildRuleResolver = new BuildRuleResolver();
    generatedProject = new PBXProject("TestProject");
    sourcePathResolver = new SourcePathResolver(buildRuleResolver);
    pathRelativizer = new PathRelativizer(
        Paths.get("_output"),
        sourcePathResolver.getPathFunction());
  }

  @Test
  public void shouldCreateTargetAndTargetGroup() throws NoSuchBuildTargetException {
    NewNativeTargetProjectMutator mutator = new NewNativeTargetProjectMutator(
        pathRelativizer,
        sourcePathResolver.getPathFunction());
    mutator
        .setTargetName("TestTarget")
        .setProduct(
            ProductType.BUNDLE,
            "TestTargetProduct",
            Paths.get("TestTargetProduct.bundle"))
        .buildTargetAndAddToProject(generatedProject);

    assertTargetExistsAndReturnTarget(generatedProject, "TestTarget");
    assertHasTargetGroupWithName(generatedProject, "TestTarget");
  }

  @Test
  public void shouldCreateTargetAndCustomTargetGroup() throws NoSuchBuildTargetException {
    NewNativeTargetProjectMutator mutator = new NewNativeTargetProjectMutator(
        pathRelativizer,
        sourcePathResolver.getPathFunction());
    mutator
        .setTargetName("TestTarget")
        .setTargetGroupPath(ImmutableList.of("Grandparent", "Parent"))
        .setProduct(
            ProductType.BUNDLE,
            "TestTargetProduct",
            Paths.get("TestTargetProduct.bundle"))
        .buildTargetAndAddToProject(generatedProject);

    assertTargetExistsAndReturnTarget(generatedProject, "TestTarget");
    PBXGroup grandparentGroup =
        assertHasSubgroupAndReturnIt(generatedProject.getMainGroup(), "Grandparent");
    assertHasSubgroupAndReturnIt(grandparentGroup, "Parent");
  }

  @Test
  public void testSourceGroups() throws NoSuchBuildTargetException {
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();

    SourcePath foo = new TestSourcePath("Group1/foo.m");
    SourcePath bar = new TestSourcePath("Group1/bar.m");
    SourcePath baz = new TestSourcePath("Group2/baz.m");
    mutator.setSourcesWithFlags(
        ImmutableSet.of(
            SourceWithFlags.of(foo),
            SourceWithFlags.of(bar, ImmutableList.of("-Wall")),
            SourceWithFlags.of(baz)));
    NewNativeTargetProjectMutator.Result result = mutator.buildTargetAndAddToProject(
        generatedProject);

    PBXGroup sourcesGroup = result.targetGroup.getOrCreateChildGroupByName("Sources");

    PBXGroup group1 = (PBXGroup) Iterables.get(sourcesGroup.getChildren(), 0);
    assertEquals("Group1", group1.getName());
    assertThat(group1.getChildren(), hasSize(2));
    PBXFileReference fileRefBar = (PBXFileReference) Iterables.get(group1.getChildren(), 0);
    assertEquals("bar.m", fileRefBar.getName());
    PBXFileReference fileRefFoo = (PBXFileReference) Iterables.get(group1.getChildren(), 1);
    assertEquals("foo.m", fileRefFoo.getName());

    PBXGroup group2 = (PBXGroup) Iterables.get(sourcesGroup.getChildren(), 1);
    assertEquals("Group2", group2.getName());
    assertThat(group2.getChildren(), hasSize(1));
    PBXFileReference fileRefBaz = (PBXFileReference) Iterables.get(group2.getChildren(), 0);
    assertEquals("baz.m", fileRefBaz.getName());
  }

  @Test
  public void testLibraryHeaderGroups() throws NoSuchBuildTargetException {
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();

    SourcePath foo = new TestSourcePath("HeaderGroup1/foo.h");
    SourcePath bar = new TestSourcePath("HeaderGroup1/bar.h");
    SourcePath baz = new TestSourcePath("HeaderGroup2/baz.h");
    mutator.setPublicHeaders(ImmutableSet.of(bar, baz));
    mutator.setPrivateHeaders(ImmutableSet.of(foo));
    NewNativeTargetProjectMutator.Result result = mutator.buildTargetAndAddToProject(
        generatedProject);

    PBXGroup sourcesGroup = result.targetGroup.getOrCreateChildGroupByName("Sources");

    assertThat(sourcesGroup.getChildren(), hasSize(2));

    PBXGroup group1 = (PBXGroup) Iterables.get(sourcesGroup.getChildren(), 0);
    assertEquals("HeaderGroup1", group1.getName());
    assertThat(group1.getChildren(), hasSize(2));
    PBXFileReference fileRefBar = (PBXFileReference) Iterables.get(group1.getChildren(), 0);
    assertEquals("bar.h", fileRefBar.getName());
    PBXFileReference fileRefFoo = (PBXFileReference) Iterables.get(group1.getChildren(), 1);
    assertEquals("foo.h", fileRefFoo.getName());

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
    PBXBuildFile barHeaderBuildFile = Iterables.get(headersBuildPhase.getFiles(), 0);
    assertTrue(
        "bar.h should have settings dictionary",
        barHeaderBuildFile.getSettings().isPresent());
    NSDictionary barBuildFileSettings = barHeaderBuildFile.getSettings().get();
    NSArray barAttributes = (NSArray) barBuildFileSettings.get("ATTRIBUTES");
    assertArrayEquals(new NSString[]{new NSString("Public")}, barAttributes.getArray());
    PBXBuildFile fooHeaderBuildFile = Iterables.get(headersBuildPhase.getFiles(), 1);
    assertFalse(
        "foo.h should not have settings dictionary",
        fooHeaderBuildFile.getSettings().isPresent());
    PBXBuildFile bazHeaderBuildFile = Iterables.get(headersBuildPhase.getFiles(), 2);
    assertTrue(
        "baz.h should have settings dictionary",
        bazHeaderBuildFile.getSettings().isPresent());
    NSDictionary blechBuildFileSettings = bazHeaderBuildFile.getSettings().get();
    NSArray blechAttributes = (NSArray) blechBuildFileSettings.get("ATTRIBUTES");
    assertArrayEquals(new NSString[]{new NSString("Public")}, blechAttributes.getArray());
  }

  @Test
  public void testPrefixHeaderInSourceGroup() throws NoSuchBuildTargetException {
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();
    SourcePath prefixHeader = new TestSourcePath("Group1/prefix.pch");
    mutator.setPrefixHeader(Optional.of(prefixHeader));

    NewNativeTargetProjectMutator.Result result = mutator.buildTargetAndAddToProject(
        generatedProject);

    // No matter where the prefixHeader file is it should always be directly inside Sources
    PBXGroup sourcesGroup = result.targetGroup.getOrCreateChildGroupByName("Sources");

    assertThat(sourcesGroup.getChildren(), hasSize(1));
    PBXFileReference fileRef = (PBXFileReference) Iterables.get(sourcesGroup.getChildren(), 0);
    assertEquals("prefix.pch", fileRef.getName());
  }

  @Test
  public void testSuppressCopyHeaderOption() throws NoSuchBuildTargetException {
    Set<SourcePath> privateHeaders = ImmutableSet.<SourcePath>of(new TestSourcePath("foo"));

    {
      NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();
      mutator
          .setShouldGenerateCopyHeadersPhase(false)
          .setPrivateHeaders(privateHeaders);
      NewNativeTargetProjectMutator.Result result = mutator.buildTargetAndAddToProject(
          generatedProject);

      assertThat(
          "copy headers phase should be omitted",
          Iterables.filter(result.target.getBuildPhases(), PBXHeadersBuildPhase.class),
          emptyIterable());
    }

    {
      NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();
      mutator
          .setShouldGenerateCopyHeadersPhase(true)
          .setPrivateHeaders(privateHeaders);
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
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();
    mutator.setFrameworks(
        ImmutableSet.of(
            FrameworkPath.ofSourceTreePath(
                new SourceTreePath(
                    PBXReference.SourceTree.SDKROOT, Paths.get("Foo.framework")))));
    mutator.setArchives(
        ImmutableSet.of(
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
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();

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
    arg1.dirs = ImmutableSortedSet.of(Paths.get("AssetCatalog1.xcassets"));
    arg1.copyToBundles = Optional.of(false);

    AppleAssetCatalogDescription.Arg arg2 = new AppleAssetCatalogDescription.Arg();
    arg2.dirs = ImmutableSortedSet.of(Paths.get("AssetCatalog2.xcassets"));
    arg2.copyToBundles = Optional.of(true);

    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();
    mutator.setAssetCatalogs(
        Paths.get("compile_asset_catalogs"),
        ImmutableSet.of(arg1, arg2));
    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject);
    assertTrue(hasShellScriptPhaseToCompileCommonAndSplitAssetCatalogs(result.target));
  }

  @Test
  public void assetCatalogsBuildPhaseDoesNotExceedCommandLineLengthWithLotsOfXcassets()
      throws NoSuchBuildTargetException {
    ImmutableSet.Builder<AppleAssetCatalogDescription.Arg> assetsBuilder = ImmutableSet.builder();
    for (int i = 0; i < 10000; i += 2) {
      AppleAssetCatalogDescription.Arg arg1 = new AppleAssetCatalogDescription.Arg();
      arg1.dirs = ImmutableSortedSet.of(Paths.get(String.format("AssetCatalog%d.xcassets", i)));
      arg1.copyToBundles = Optional.of(false);
      assetsBuilder.add(arg1);
      AppleAssetCatalogDescription.Arg arg2 = new AppleAssetCatalogDescription.Arg();
      arg2.dirs = ImmutableSortedSet.of(Paths.get(String.format("AssetCatalog%d.xcassets", i + 1)));
      arg2.copyToBundles = Optional.of(true);
      assetsBuilder.add(arg2);
    }

    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();
    mutator.setAssetCatalogs(
        Paths.get("compile_asset_catalogs"),
        assetsBuilder.build());
    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject);

    PBXShellScriptBuildPhase assetCatalogBuildPhase = getAssetCatalogBuildPhase(
        result.target.getBuildPhases());

    for (String line : Splitter.on('\n').split(assetCatalogBuildPhase.getShellScript())) {
      int lineLength = line.length();
      assertThat(
          "Line length of generated asset catalog build phase should not be super long",
          lineLength,
          lessThan(1024));
    }
  }

  @Test
  public void testScriptBuildPhase() throws NoSuchBuildTargetException{
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();

    TargetNode<?> genruleNode = GenruleBuilder
        .newGenruleBuilder(BuildTarget.builder("//foo", "script").build())
        .setSrcs(ImmutableList.<SourcePath>of(new TestSourcePath("script/input.png")))
        .setCmd("echo \"hello world!\"")
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
        "echo \"hello world!\"",
        phase.getShellScript());
  }

  @Test
  public void testScriptBuildPhaseWithGenruleDep() throws NoSuchBuildTargetException{
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();

    BuildTarget depBuildTarget = BuildTarget.builder("//foo", "dep").build();
    // This has the side effect of adding the dependency to the BuildRuleResolver map.
    GenruleBuilder
        .newGenruleBuilder(depBuildTarget)
        .setCmd("echo \"hello dep!\"")
        .setOut("dep.txt")
        .build(buildRuleResolver);

    TargetNode<?> genruleNode = GenruleBuilder
        .newGenruleBuilder(BuildTarget.builder("//foo", "script").build())
        .setSrcs(ImmutableList.<SourcePath>of(new TestSourcePath("script/input.png")))
        .setCmd("echo \"hello world!\"")
        .setOut("helloworld.txt")
        .setDeps(ImmutableSortedSet.of(depBuildTarget))
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
        "echo \"hello world!\"",
        phase.getShellScript());
  }

  @Test
  public void testScriptBuildPhaseWithReactNative() throws NoSuchBuildTargetException {
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();

    BuildTarget depBuildTarget = BuildTarget.builder("//foo", "dep").build();
    ProjectFilesystem filesystem = new AllExistingProjectFilesystem();
    ReactNativeBuckConfig buckConfig = new ReactNativeBuckConfig(new FakeBuckConfig(
        ImmutableMap.of("react-native", ImmutableMap.of("packager", "react-native/packager.sh")),
        filesystem));
    TargetNode<?> reactNativeNode =
        IosReactNativeLibraryBuilder.builder(depBuildTarget, buckConfig)
        .setBundleName("Apps/Foo/FooBundle.js")
        .setEntryPath(new PathSourcePath(filesystem, Paths.get("js/FooApp.js")))
        .build();

    mutator.setPostBuildRunScriptPhases(ImmutableList.<TargetNode<?>>of(reactNativeNode));
    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject);

    PBXShellScriptBuildPhase phase =
        getSingletonPhaseByType(result.target, PBXShellScriptBuildPhase.class);
    String shellScript = phase.getShellScript();
    assertThat(
        shellScript,
        startsWith("BASE_DIR=${CONFIGURATION_BUILD_DIR}/${UNLOCALIZED_RESOURCES_FOLDER_PATH}" +
        " && JS_OUT=${BASE_DIR}/Apps/Foo/FooBundle.js && SOURCE_MAP=${TEMP_DIR}/rn_source_map/" +
            "Apps/Foo/FooBundle.js.map && mkdir -p `dirname ${JS_OUT}` " +
            "&& mkdir -p `dirname ${SOURCE_MAP}`"));
  }

  private NewNativeTargetProjectMutator mutatorWithCommonDefaults() {
    NewNativeTargetProjectMutator mutator = new NewNativeTargetProjectMutator(
        pathRelativizer,
        sourcePathResolver.getPathFunction());
    mutator
        .setTargetName("TestTarget")
        .setProduct(
            ProductType.BUNDLE,
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

  private static PBXGroup assertHasSubgroupAndReturnIt(PBXGroup group, final String subgroupName) {
    ImmutableList<PBXGroup> candidates = FluentIterable
        .from(group.getChildren())
        .filter(
            new Predicate<PBXReference>() {
              @Override
              public boolean apply(PBXReference input) {
                return input.getName().equals(subgroupName);
              }
            })
        .filter(PBXGroup.class)
        .toList();
    if (candidates.size() != 1) {
      fail("Could not find a unique subgroup by its name");
    }
    return candidates.get(0);
  }

  private PBXShellScriptBuildPhase getAssetCatalogBuildPhase(Iterable<PBXBuildPhase> buildPhases) {
    PBXShellScriptBuildPhase assetCatalogBuildPhase = null;
    for (PBXBuildPhase phase : buildPhases) {
      if (phase.getClass().equals(PBXShellScriptBuildPhase.class)) {
        PBXShellScriptBuildPhase shellScriptBuildPhase = (PBXShellScriptBuildPhase) phase;
        if (shellScriptBuildPhase.getShellScript().contains("compile_asset_catalogs")) {
          assetCatalogBuildPhase = shellScriptBuildPhase;
        }
      }
    }
    assertNotNull(assetCatalogBuildPhase);
    return assetCatalogBuildPhase;
  }

  private boolean hasShellScriptPhaseToCompileCommonAndSplitAssetCatalogs(PBXTarget target) {
    PBXShellScriptBuildPhase assetCatalogBuildPhase = getAssetCatalogBuildPhase(
        target.getBuildPhases());

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
