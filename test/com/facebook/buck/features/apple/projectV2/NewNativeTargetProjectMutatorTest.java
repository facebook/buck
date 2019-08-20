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

package com.facebook.buck.features.apple.projectV2;

import static com.facebook.buck.features.apple.projectV2.ProjectGeneratorTestUtils.assertHasSingleFrameworksPhaseWithFrameworkEntries;
import static com.facebook.buck.features.apple.projectV2.ProjectGeneratorTestUtils.assertHasSinglePhaseWithEntries;
import static com.facebook.buck.features.apple.projectV2.ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget;
import static com.facebook.buck.features.apple.projectV2.ProjectGeneratorTestUtils.getExpectedBuildPhasesByType;
import static com.facebook.buck.features.apple.projectV2.ProjectGeneratorTestUtils.getSingleBuildPhaseOfType;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.AppleAssetCatalogDescriptionArg;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleResourceDescriptionArg;
import com.facebook.buck.apple.XcodePostbuildScriptBuilder;
import com.facebook.buck.apple.XcodePrebuildScriptBuilder;
import com.facebook.buck.apple.xcode.xcodeproj.CopyFilePhaseDestinationSpec;
import com.facebook.buck.apple.xcode.xcodeproj.PBXBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXCopyFilesBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXFileReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXGroup;
import com.facebook.buck.apple.xcode.xcodeproj.PBXNativeTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXResourcesBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.PBXShellScriptBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.ProductTypes;
import com.facebook.buck.apple.xcode.xcodeproj.SourceTreePath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.features.js.JsBundleGenruleBuilder;
import com.facebook.buck.features.js.JsTestScenario;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class NewNativeTargetProjectMutatorTest {
  private BuildTarget buildTarget;
  private AppleConfig appleConfig;
  private PBXProject generatedProject;
  private PathRelativizer pathRelativizer;
  private SourcePathResolver sourcePathResolver;
  private BuildRuleResolver buildRuleResolver;

  @Before
  public void setUp() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);
    generatedProject = new PBXProject("TestProject");
    buildTarget = BuildTargetFactory.newInstance("//foo:bar");
    appleConfig = AppleProjectHelper.createDefaultAppleConfig(new FakeProjectFilesystem());
    buildRuleResolver = new TestActionGraphBuilder();
    sourcePathResolver = buildRuleResolver.getSourcePathResolver();
    pathRelativizer =
        new PathRelativizer(Paths.get("_output"), sourcePathResolver::getRelativePath);
  }

  @Test
  public void shouldCreateTarget() throws NoSuchBuildTargetException {
    NewNativeTargetProjectMutator mutator =
        new NewNativeTargetProjectMutator(
            pathRelativizer, sourcePathResolver::getRelativePath, buildTarget, appleConfig);
    mutator
        .setTargetName("TestTarget")
        .setProduct(ProductTypes.BUNDLE, "TestTargetProduct", Paths.get("TestTargetProduct.bundle"))
        .buildTargetAndAddToProject(generatedProject, true);

    assertTargetExistsAndReturnTarget(generatedProject, "TestTarget");
  }

  @Test
  public void shouldCreateTargetAndNoGroup() throws NoSuchBuildTargetException {
    NewNativeTargetProjectMutator mutator =
        new NewNativeTargetProjectMutator(
            pathRelativizer, sourcePathResolver::getRelativePath, buildTarget, appleConfig);
    NewNativeTargetProjectMutator.Result result =
        mutator
            .setTargetName("TestTarget")
            .setProduct(
                ProductTypes.BUNDLE, "TestTargetProduct", Paths.get("TestTargetProduct.bundle"))
            .buildTargetAndAddToProject(generatedProject, false);

    assertFalse(result.targetGroup.isPresent());
  }

  @Test
  public void testSourceGroups() throws NoSuchBuildTargetException {
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();

    SourcePath foo = FakeSourcePath.of("Group1/foo.m");
    SourcePath bar = FakeSourcePath.of("Group1/bar.m");
    SourcePath baz = FakeSourcePath.of("Group2/baz.m");
    mutator.setSourcesWithFlags(
        ImmutableSet.of(
            SourceWithFlags.of(foo),
            SourceWithFlags.of(bar, ImmutableList.of("-Wall")),
            SourceWithFlags.of(baz)));
    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject, true);

    PBXGroup sourcesGroup = result.targetGroup.get();

    PBXGroup group1 = PBXTestUtils.assertHasSubgroupAndReturnIt(sourcesGroup, "Group1");
    assertThat(group1.getChildren(), hasSize(2));
    PBXFileReference fileRefBar = (PBXFileReference) Iterables.get(group1.getChildren(), 0);
    assertEquals("bar.m", fileRefBar.getName());
    PBXFileReference fileRefFoo = (PBXFileReference) Iterables.get(group1.getChildren(), 1);
    assertEquals("foo.m", fileRefFoo.getName());

    PBXGroup group2 = PBXTestUtils.assertHasSubgroupAndReturnIt(sourcesGroup, "Group2");
    assertThat(group2.getChildren(), hasSize(1));
    PBXFileReference fileRefBaz = (PBXFileReference) Iterables.get(group2.getChildren(), 0);
    assertEquals("baz.m", fileRefBaz.getName());
  }

  @Test
  public void testLibraryHeaderGroups() throws NoSuchBuildTargetException {
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();

    SourcePath foo = FakeSourcePath.of("HeaderGroup1/foo.h");
    SourcePath bar = FakeSourcePath.of("HeaderGroup1/bar.h");
    SourcePath baz = FakeSourcePath.of("HeaderGroup2/baz.h");
    mutator.setPublicHeaders(ImmutableSet.of(bar, baz));
    mutator.setPrivateHeaders(ImmutableSet.of(foo));
    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject, true);

    PBXGroup sourcesGroup = result.targetGroup.get();

    PBXGroup group1 = PBXTestUtils.assertHasSubgroupAndReturnIt(sourcesGroup, "HeaderGroup1");
    assertThat(group1.getChildren(), hasSize(2));
    PBXFileReference fileRefBar = (PBXFileReference) Iterables.get(group1.getChildren(), 0);
    assertEquals("bar.h", fileRefBar.getName());
    PBXFileReference fileRefFoo = (PBXFileReference) Iterables.get(group1.getChildren(), 1);
    assertEquals("foo.h", fileRefFoo.getName());

    PBXGroup group2 = PBXTestUtils.assertHasSubgroupAndReturnIt(sourcesGroup, "HeaderGroup2");
    assertEquals("HeaderGroup2", group2.getName());
    assertThat(group2.getChildren(), hasSize(1));
    PBXFileReference fileRefBaz = (PBXFileReference) Iterables.get(group2.getChildren(), 0);
    assertEquals("baz.h", fileRefBaz.getName());
  }

  @Test
  public void testPrefixHeaderInCorrectGroup() throws NoSuchBuildTargetException {
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();
    SourcePath prefixHeader = FakeSourcePath.of("Group1/prefix.pch");
    mutator.setPrefixHeader(Optional.of(prefixHeader));

    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject, true);

    PBXGroup group1 = PBXTestUtils.assertHasSubgroupAndReturnIt(result.targetGroup.get(), "Group1");
    PBXFileReference fileRef = (PBXFileReference) Iterables.get(group1.getChildren(), 0);
    assertEquals("prefix.pch", fileRef.getName());
  }

  @Test
  public void testBuckFileAddedInCorrectGroup() throws NoSuchBuildTargetException {
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();
    mutator.setBuckFilePath(Optional.of(Paths.get("MyApp/MyLib/BUCK")));

    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject, true);
    PBXGroup myAppGroup =
        PBXTestUtils.assertHasSubgroupAndReturnIt(result.targetGroup.get(), "MyApp");
    PBXGroup filesGroup = PBXTestUtils.assertHasSubgroupAndReturnIt(myAppGroup, "MyLib");
    PBXFileReference buckFileReference =
        PBXTestUtils.assertHasFileReferenceWithNameAndReturnIt(filesGroup, "BUCK");
    assertEquals(buckFileReference.getExplicitFileType(), Optional.of("text.script.python"));
  }

  @Test
  public void testFrameworkBuildPhase() throws NoSuchBuildTargetException {
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();
    mutator.setFrameworks(
        ImmutableSet.of(
            FrameworkPath.ofSourceTreePath(
                new SourceTreePath(
                    PBXReference.SourceTree.SDKROOT,
                    Paths.get("Foo.framework"),
                    Optional.empty()))));
    mutator.setArchives(
        ImmutableSet.of(
            new PBXFileReference(
                "libdep.a",
                "libdep.a",
                PBXReference.SourceTree.BUILT_PRODUCTS_DIR,
                Optional.empty())));
    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject, true);
    assertHasSingleFrameworksPhaseWithFrameworkEntries(
        result.target, ImmutableList.of("$SDKROOT/Foo.framework", "$BUILT_PRODUCTS_DIR/libdep.a"));
  }

  @Test
  public void testResourcesBuildPhase() throws NoSuchBuildTargetException {
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();

    AppleResourceDescriptionArg arg =
        AppleResourceDescriptionArg.builder()
            .setName("resources")
            .setFiles(ImmutableSet.of(FakeSourcePath.of("foo.png")))
            .build();

    mutator.setRecursiveResources(ImmutableSet.of(arg));
    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject, true);

    assertHasSinglePhaseWithEntries(
        result.target, PBXResourcesBuildPhase.class, ImmutableList.of("$SOURCE_ROOT/../foo.png"));
  }

  @Test
  public void testCopyFilesBuildPhase() throws NoSuchBuildTargetException {
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();

    CopyFilePhaseDestinationSpec.Builder specBuilder = CopyFilePhaseDestinationSpec.builder();
    specBuilder.setDestination(PBXCopyFilesBuildPhase.Destination.FRAMEWORKS);
    specBuilder.setPath("foo.png");

    PBXBuildPhase copyPhase = new PBXCopyFilesBuildPhase(specBuilder.build());
    mutator.setCopyFilesPhases(ImmutableList.of(copyPhase));

    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject, true);

    PBXBuildPhase buildPhaseToTest =
        getSingleBuildPhaseOfType(result.target, PBXCopyFilesBuildPhase.class);
    assertThat(copyPhase, equalTo(buildPhaseToTest));
  }

  @Test
  public void testCopyFilesBuildPhaseIsBeforePostBuildScriptBuildPhase()
      throws NoSuchBuildTargetException {
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();

    CopyFilePhaseDestinationSpec.Builder specBuilder = CopyFilePhaseDestinationSpec.builder();
    specBuilder.setDestination(PBXCopyFilesBuildPhase.Destination.FRAMEWORKS);
    specBuilder.setPath("script/input.png");

    PBXBuildPhase copyFilesPhase = new PBXCopyFilesBuildPhase(specBuilder.build());
    mutator.setCopyFilesPhases(ImmutableList.of(copyFilesPhase));

    TargetNode<?> postbuildNode =
        XcodePostbuildScriptBuilder.createBuilder(BuildTargetFactory.newInstance("//foo:script"))
            .setCmd("echo \"hello world!\"")
            .build();
    mutator.setPostBuildRunScriptPhasesFromTargetNodes(
        ImmutableList.of(postbuildNode), x -> buildRuleResolver);

    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject, true);

    PBXNativeTarget target = result.target;

    List<PBXBuildPhase> buildPhases = target.getBuildPhases();

    PBXBuildPhase copyBuildPhaseToTest =
        getSingleBuildPhaseOfType(target, PBXCopyFilesBuildPhase.class);
    Iterable<PBXShellScriptBuildPhase> scriptBuildPhases =
        getExpectedBuildPhasesByType(target, PBXShellScriptBuildPhase.class, 2);
    PBXBuildPhase postBuildScriptPhase = scriptBuildPhases.iterator().next();

    assertThat(
        buildPhases.indexOf(copyBuildPhaseToTest),
        lessThan(buildPhases.indexOf(postBuildScriptPhase)));
  }

  @Test
  public void assetCatalogsBuildPhaseBuildsAssetCatalogs() throws NoSuchBuildTargetException {
    AppleAssetCatalogDescriptionArg arg =
        AppleAssetCatalogDescriptionArg.builder()
            .setName("some_rule")
            .setDirs(ImmutableSortedSet.of(FakeSourcePath.of("AssetCatalog1.xcassets")))
            .build();

    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();
    mutator.setRecursiveAssetCatalogs(ImmutableSet.of(arg));
    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject, true);
    assertHasSinglePhaseWithEntries(
        result.target,
        PBXResourcesBuildPhase.class,
        ImmutableList.of("$SOURCE_ROOT/../AssetCatalog1.xcassets"));
  }

  @Test
  public void testScriptBuildPhase() throws NoSuchBuildTargetException {
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();

    TargetNode<?> prebuildNode =
        XcodePrebuildScriptBuilder.createBuilder(BuildTargetFactory.newInstance("//foo:script"))
            .setSrcs(ImmutableSortedSet.of(FakeSourcePath.of("script/input.png")))
            .setInputs(ImmutableSortedSet.of("$(SRCROOT)/helloworld.md"))
            .setInputFileLists(ImmutableSortedSet.of("$(SRCROOT)/foo-inputs.xcfilelist"))
            .setOutputs(ImmutableSortedSet.of("helloworld.txt"))
            .setOutputFileLists(ImmutableSortedSet.of("$(SRCROOT)/foo-outputs.xcfilelist"))
            .setCmd("echo \"hello world!\"")
            .build();

    mutator.setPostBuildRunScriptPhasesFromTargetNodes(
        ImmutableList.of(prebuildNode), x -> buildRuleResolver);
    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject, true);

    Iterable<PBXShellScriptBuildPhase> scriptBuildPhases =
        getExpectedBuildPhasesByType(result.target, PBXShellScriptBuildPhase.class, 2);

    PBXShellScriptBuildPhase phase = scriptBuildPhases.iterator().next();
    assertThat("Should set input paths correctly", phase.getInputPaths().size(), is(equalTo(2)));
    assertThat(
        "Should set input paths correctly",
        phase.getInputPaths(),
        is(hasItems("../script/input.png", "$(SRCROOT)/helloworld.md")));
    assertThat(
        "Should set input file list paths correctly",
        Iterables.getOnlyElement(phase.getInputFileListPaths()),
        is(equalTo("$(SRCROOT)/foo-inputs.xcfilelist")));
    assertThat(
        "Should set output paths correctly",
        "helloworld.txt",
        is(equalTo(Iterables.getOnlyElement(phase.getOutputPaths()))));
    assertThat(
        "Should set output file list paths correctly",
        Iterables.getOnlyElement(phase.getOutputFileListPaths()),
        is(equalTo("$(SRCROOT)/foo-outputs.xcfilelist")));
    assertEquals("should set script correctly", "echo \"hello world!\"", phase.getShellScript());
  }

  @Test
  public void testScriptBuildPhaseWithJsBundle() throws NoSuchBuildTargetException {
    BuildTarget depBuildTarget = BuildTargetFactory.newInstance("//foo:dep");
    JsTestScenario scenario =
        JsTestScenario.builder().bundle(depBuildTarget, ImmutableSortedSet.of()).build();

    NewNativeTargetProjectMutator mutator = mutator(scenario.graphBuilder.getSourcePathResolver());

    TargetNode<?> jsBundleNode = scenario.targetGraph.get(depBuildTarget);

    mutator.setPostBuildRunScriptPhasesFromTargetNodes(
        ImmutableList.of(jsBundleNode), x -> scenario.graphBuilder);
    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject, true);

    Iterable<PBXShellScriptBuildPhase> scriptBuildPhases =
        getExpectedBuildPhasesByType(result.target, PBXShellScriptBuildPhase.class, 2);

    PBXShellScriptBuildPhase phase = scriptBuildPhases.iterator().next();
    String shellScript = phase.getShellScript();
    Path genDir = scenario.filesystem.getBuckPaths().getGenDir().toAbsolutePath();
    assertEquals(
        String.format(
            "BASE_DIR=\"${TARGET_BUILD_DIR}/${UNLOCALIZED_RESOURCES_FOLDER_PATH}\"\n"
                + "mkdir -p \"${BASE_DIR}\"\n\n"
                + "cp -a \"%s/foo/dep/js/\" \"${BASE_DIR}/\"\n"
                + "cp -a \"%s/foo/dep/res/\" \"${BASE_DIR}/\"\n",
            genDir, genDir),
        shellScript);
  }

  @Test
  public void testScriptBuildPhaseWithJsBundleGenrule() throws NoSuchBuildTargetException {
    BuildTarget bundleBuildTarget = BuildTargetFactory.newInstance("//foo:bundle");
    BuildTarget depBuildTarget = BuildTargetFactory.newInstance("//foo:dep");
    JsTestScenario scenario =
        JsTestScenario.builder()
            .bundle(bundleBuildTarget, ImmutableSortedSet.of())
            .bundleGenrule(JsBundleGenruleBuilder.Options.of(depBuildTarget, bundleBuildTarget))
            .build();

    NewNativeTargetProjectMutator mutator = mutator(scenario.graphBuilder.getSourcePathResolver());

    TargetNode<?> jsBundleGenruleNode = scenario.targetGraph.get(depBuildTarget);

    mutator.setPostBuildRunScriptPhasesFromTargetNodes(
        ImmutableList.of(jsBundleGenruleNode), x -> scenario.graphBuilder);
    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject, true);

    Iterable<PBXShellScriptBuildPhase> scriptBuildPhases =
        getExpectedBuildPhasesByType(result.target, PBXShellScriptBuildPhase.class, 2);

    PBXShellScriptBuildPhase phase = scriptBuildPhases.iterator().next();
    String shellScript = phase.getShellScript();
    Path genDir = scenario.filesystem.getBuckPaths().getGenDir().toAbsolutePath();
    assertEquals(
        String.format(
            "BASE_DIR=\"${TARGET_BUILD_DIR}/${UNLOCALIZED_RESOURCES_FOLDER_PATH}\"\n"
                + "mkdir -p \"${BASE_DIR}\"\n\n"
                + "cp -a \"%s/foo/dep/js/\" \"${BASE_DIR}/\"\n"
                + "cp -a \"%s/foo/bundle/res/\" \"${BASE_DIR}/\"\n",
            genDir, genDir),
        shellScript);
  }

  @Test
  public void testApplicationTargetHasScriptPhase() {
    NewNativeTargetProjectMutator mutator = mutatorWithCommonDefaults();
    mutator.setProduct(
        ProductTypes.APPLICATION,
        buildTarget.getShortName(),
        Paths.get(buildTarget.getShortName()));

    NewNativeTargetProjectMutator.Result result =
        mutator.buildTargetAndAddToProject(generatedProject, true);

    PBXShellScriptBuildPhase phase =
        getSingleBuildPhaseOfType(result.target, PBXShellScriptBuildPhase.class);

    assertThat(
        "Buck build script command is as expected",
        phase.getShellScript(),
        is(equalTo("cd $SOURCE_ROOT/.. && ./build_script.sh")));
  }

  private NewNativeTargetProjectMutator mutatorWithCommonDefaults() {
    return mutator(sourcePathResolver, pathRelativizer);
  }

  private NewNativeTargetProjectMutator mutator(SourcePathResolver pathResolver) {
    return mutator(
        pathResolver, new PathRelativizer(Paths.get("_output"), pathResolver::getRelativePath));
  }

  private NewNativeTargetProjectMutator mutator(
      SourcePathResolver pathResolver, PathRelativizer relativizer) {
    NewNativeTargetProjectMutator mutator =
        new NewNativeTargetProjectMutator(
            relativizer, pathResolver::getRelativePath, buildTarget, appleConfig);
    mutator
        .setTargetName("TestTarget")
        .setProduct(
            ProductTypes.BUNDLE, "TestTargetProduct", Paths.get("TestTargetProduct.bundle"));
    return mutator;
  }
}
