/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.features.apple.projectV2;

import static com.facebook.buck.features.apple.projectV2.ProjectGeneratorTestUtils.assertTargetExistsAndReturnTarget;
import static com.facebook.buck.features.apple.projectV2.ProjectGeneratorTestUtils.getSingleBuildPhaseOfType;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.xcode.AbstractPBXObjectFactory;
import com.facebook.buck.apple.xcode.xcodeproj.PBXFileReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXGroup;
import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.apple.xcode.xcodeproj.PBXShellScriptBuildPhase;
import com.facebook.buck.apple.xcode.xcodeproj.ProductTypes;
import com.facebook.buck.core.cell.NewCellPathResolver;
import com.facebook.buck.core.cell.impl.CellMappingsFactory;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.util.config.Configs;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class XcodeNativeTargetProjectWriterTest {
  private ProjectFilesystem projectFilesystem;
  private BuildTarget buildTarget;
  private AppleConfig appleConfig;
  private PBXProject generatedProject;
  private PathRelativizer pathRelativizer;
  private SourcePathResolverAdapter sourcePathResolverAdapter;
  private BuildRuleResolver buildRuleResolver;
  private NewCellPathResolver newCellPathResolver;
  private AbstractPBXObjectFactory objectFactory;

  @Before
  public void setUp() throws IOException {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);
    projectFilesystem = new FakeProjectFilesystem();
    generatedProject = new PBXProject("TestProject", AbstractPBXObjectFactory.DefaultFactory());
    buildTarget = BuildTargetFactory.newInstance("//foo:bar");
    appleConfig = AppleProjectHelper.createDefaultAppleConfig(new FakeProjectFilesystem());
    buildRuleResolver = new TestActionGraphBuilder();
    sourcePathResolverAdapter = buildRuleResolver.getSourcePathResolver();
    pathRelativizer =
        new PathRelativizer(Paths.get("_output"), sourcePathResolverAdapter::getRelativePath);
    newCellPathResolver =
        CellMappingsFactory.create(
            projectFilesystem.getRootPath(),
            Configs.createDefaultConfig(projectFilesystem.getRootPath().getPath()));
    objectFactory = AbstractPBXObjectFactory.DefaultFactory();
  }

  @Test
  public void shouldCreateTarget() throws NoSuchBuildTargetException {
    XCodeNativeTargetAttributes nativeTargetAttributes =
        ImmutableXCodeNativeTargetAttributes.builder()
            .setTarget(Optional.of(buildTarget))
            .setAppleConfig(appleConfig)
            .setProduct(
                Optional.of(
                    new XcodeProductMetadata(
                        ProductTypes.BUNDLE,
                        "TestTargetProduct",
                        Paths.get("TestTargetProduct.bundle"))))
            .build();

    XcodeNativeTargetProjectWriter projectWriter =
        new XcodeNativeTargetProjectWriter(
            pathRelativizer,
            sourcePathResolverAdapter::getRelativePath,
            true,
            newCellPathResolver,
            objectFactory);
    projectWriter.writeTargetToProject(nativeTargetAttributes, generatedProject);

    assertTargetExistsAndReturnTarget(generatedProject, "bar");
  }

  @Test
  public void testSourceGroups() throws NoSuchBuildTargetException {
    SourcePath foo = FakeSourcePath.of("Group1/foo.m");
    SourcePath bar = FakeSourcePath.of("Group1/bar.m");
    SourcePath baz = FakeSourcePath.of("Group2/baz.m");
    XCodeNativeTargetAttributes nativeTargetAttributes =
        builderWithCommonDefaults()
            .setSourcesWithFlags(
                ImmutableSet.of(
                    SourceWithFlags.of(foo),
                    SourceWithFlags.of(bar, ImmutableList.of("-Wall")),
                    SourceWithFlags.of(baz)))
            .build();
    XcodeNativeTargetProjectWriter projectWriter =
        new XcodeNativeTargetProjectWriter(
            pathRelativizer,
            sourcePathResolverAdapter::getRelativePath,
            false,
            newCellPathResolver,
            objectFactory);
    XcodeNativeTargetProjectWriter.Result result =
        projectWriter.writeTargetToProject(nativeTargetAttributes, generatedProject);

    PBXGroup sourcesGroup = result.getTargetGroup();

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
    SourcePath foo = FakeSourcePath.of("HeaderGroup1/foo.h");
    SourcePath bar = FakeSourcePath.of("HeaderGroup1/bar.h");
    SourcePath baz = FakeSourcePath.of("HeaderGroup2/baz.h");

    XCodeNativeTargetAttributes targetAttributes =
        builderWithCommonDefaults()
            .setPublicHeaders(ImmutableSet.of(bar, baz))
            .setPrivateHeaders(ImmutableSet.of(foo))
            .build();

    XcodeNativeTargetProjectWriter projectWriter =
        new XcodeNativeTargetProjectWriter(
            pathRelativizer,
            sourcePathResolverAdapter::getRelativePath,
            false,
            newCellPathResolver,
            objectFactory);
    XcodeNativeTargetProjectWriter.Result result =
        projectWriter.writeTargetToProject(targetAttributes, generatedProject);

    PBXGroup sourcesGroup = result.getTargetGroup();

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
    SourcePath prefixHeader = FakeSourcePath.of("Group1/prefix.pch");

    XCodeNativeTargetAttributes targetAttributes =
        builderWithCommonDefaults().setPrefixHeader(Optional.of(prefixHeader)).build();

    XcodeNativeTargetProjectWriter projectWriter =
        new XcodeNativeTargetProjectWriter(
            pathRelativizer,
            sourcePathResolverAdapter::getRelativePath,
            false,
            newCellPathResolver,
            objectFactory);
    XcodeNativeTargetProjectWriter.Result result =
        projectWriter.writeTargetToProject(targetAttributes, generatedProject);

    PBXGroup group1 = PBXTestUtils.assertHasSubgroupAndReturnIt(result.getTargetGroup(), "Group1");
    PBXFileReference fileRef = (PBXFileReference) Iterables.get(group1.getChildren(), 0);
    assertEquals("prefix.pch", fileRef.getName());
  }

  @Test
  public void testBuckFileAddedInCorrectGroup() throws NoSuchBuildTargetException {
    XCodeNativeTargetAttributes targetAttributes =
        builderWithCommonDefaults()
            .setBuckFilePath(Optional.of(Paths.get("MyApp/MyLib/BUCK")))
            .build();

    XcodeNativeTargetProjectWriter projectWriter =
        new XcodeNativeTargetProjectWriter(
            pathRelativizer,
            sourcePathResolverAdapter::getRelativePath,
            false,
            newCellPathResolver,
            objectFactory);
    XcodeNativeTargetProjectWriter.Result result =
        projectWriter.writeTargetToProject(targetAttributes, generatedProject);

    PBXGroup myAppGroup =
        PBXTestUtils.assertHasSubgroupAndReturnIt(result.getTargetGroup(), "MyApp");
    PBXGroup filesGroup = PBXTestUtils.assertHasSubgroupAndReturnIt(myAppGroup, "MyLib");
    PBXFileReference buckFileReference =
        PBXTestUtils.assertHasFileReferenceWithNameAndReturnIt(filesGroup, "BUCK");
    assertEquals(buckFileReference.getExplicitFileType(), Optional.of("text.script.python"));
  }

  @Test
  public void testTargetHasBuildScriptPhase() {
    XCodeNativeTargetAttributes targetAttributes =
        ImmutableXCodeNativeTargetAttributes.builder()
            .setTarget(Optional.of(buildTarget))
            .setAppleConfig(appleConfig)
            .setProduct(
                Optional.of(
                    new XcodeProductMetadata(
                        ProductTypes.APPLICATION,
                        buildTarget.getShortName(),
                        Paths.get(buildTarget.getShortName()))))
            .build();

    XcodeNativeTargetProjectWriter projectWriter =
        new XcodeNativeTargetProjectWriter(
            pathRelativizer,
            sourcePathResolverAdapter::getRelativePath,
            false,
            newCellPathResolver,
            objectFactory);
    XcodeNativeTargetProjectWriter.Result result =
        projectWriter.writeTargetToProject(targetAttributes, generatedProject);

    PBXShellScriptBuildPhase phase =
        getSingleBuildPhaseOfType(result.getTarget().get(), PBXShellScriptBuildPhase.class);

    assertThat(
        "Buck build script command is as expected",
        phase.getShellScript(),
        is(equalTo("cd $SOURCE_ROOT/.. && ./build_script.sh")));
  }

  private ImmutableXCodeNativeTargetAttributes.Builder builderWithCommonDefaults() {
    return ImmutableXCodeNativeTargetAttributes.builder()
        .setTarget(Optional.of(buildTarget))
        .setAppleConfig(appleConfig)
        .setProduct(
            Optional.of(
                new XcodeProductMetadata(
                    ProductTypes.BUNDLE,
                    "TestTargetProduct",
                    Paths.get("TestTargetProduct.bundle"))));
  }
}
