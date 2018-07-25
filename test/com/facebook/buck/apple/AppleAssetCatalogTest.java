/*
 * Copyright 2015-present Facebook, Inc.
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

import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class AppleAssetCatalogTest {

  private ProjectFilesystem filesystem;
  private SourcePathResolver resolver =
      DefaultSourcePathResolver.from(new SourcePathRuleFinder(new TestActionGraphBuilder()));

  @Rule public final TemporaryPaths tmp = new TemporaryPaths();

  @Rule public final ExpectedException exception = ExpectedException.none();

  @Before
  public void setUp() throws InterruptedException {
    filesystem = TestProjectFilesystems.createProjectFilesystem(tmp.getRoot());
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);
  }

  @Test
  public void missingXcassetsExtensionFailsXcodeValidation() throws IOException {
    String invalidName = "catalog.wrongextension";
    tmp.newFolder(invalidName);

    exception.expect(HumanReadableException.class);
    exception.expectMessage(
        "Target //:foobar had asset catalog dir catalog.wrongextension - asset catalog dirs must end with .xcassets");
    AppleAssetCatalog.validateAssetCatalogs(
        ImmutableSortedSet.of(FakeSourcePath.of(filesystem, invalidName)),
        BuildTargetFactory.newInstance("//:foobar"),
        filesystem,
        resolver,
        AppleAssetCatalog.ValidationType.XCODE);
  }

  @Test
  public void missingCatalogContentsJsonPassesXcodeValidation() throws IOException {
    String catalogName = "catalog.xcassets";
    tmp.newFolder(catalogName);

    AppleAssetCatalog.validateAssetCatalogs(
        ImmutableSortedSet.of(FakeSourcePath.of(filesystem, catalogName)),
        BuildTargetFactory.newInstance("//:foobar"),
        filesystem,
        resolver,
        AppleAssetCatalog.ValidationType.XCODE);
  }

  @Test
  public void missingContentsJsonPassXcodeValidation() throws IOException {
    String catalogName = "catalog.xcassets";
    tmp.newFolder(catalogName);
    tmp.newFile(String.format("%s/Contents.json", catalogName));
    String appiconsetName = String.format("%s/some.appiconset", catalogName);
    tmp.newFolder(appiconsetName);
    String brandassetsName = String.format("%s/some.brandassets", catalogName);
    tmp.newFolder(brandassetsName);
    String cubetexturesetName = String.format("%s/some.cubetextureset", catalogName);
    tmp.newFolder(cubetexturesetName);
    String datasetName = String.format("%s/some.dataset", catalogName);
    tmp.newFolder(datasetName);
    String imagesetName = String.format("%s/some.imageset", catalogName);
    tmp.newFolder(imagesetName);
    String imagestackName = String.format("%s/some.imagestack", catalogName);
    tmp.newFolder(imagestackName);
    String launchimageName = String.format("%s/some.launchimage", catalogName);
    tmp.newFolder(launchimageName);
    String mipmapsetName = String.format("%s/some.mipmapset", catalogName);
    tmp.newFolder(mipmapsetName);
    String stickerName = String.format("%s/some.sticker", catalogName);
    tmp.newFolder(stickerName);
    String stickerpackName = String.format("%s/some.stickerpack", catalogName);
    tmp.newFolder(stickerpackName);
    String stickersequenceName = String.format("%s/some.stickersequence", catalogName);
    tmp.newFolder(stickersequenceName);
    String texturesetName = String.format("%s/some.textureset", catalogName);
    tmp.newFolder(texturesetName);
    String complicationsetName = String.format("%s/some.complicationset", catalogName);
    tmp.newFolder(complicationsetName);

    AppleAssetCatalog.validateAssetCatalogs(
        ImmutableSortedSet.of(FakeSourcePath.of(filesystem, catalogName)),
        BuildTargetFactory.newInstance("//:foobar"),
        filesystem,
        resolver,
        AppleAssetCatalog.ValidationType.XCODE);
  }

  @Test
  public void duplicateImageNamesPassXcodeValidation() throws IOException {
    String catalogName1 = "catalog.xcassets";
    tmp.newFolder(catalogName1);
    tmp.newFile(String.format("%s/Contents.json", catalogName1));
    String imagesetName1 = String.format("%s/some.imageset", catalogName1);
    tmp.newFolder(imagesetName1);
    tmp.newFile(String.format("%s/Contents.json", imagesetName1));
    String imageName1 = String.format("%s/image.png", imagesetName1);
    tmp.newFile(imageName1);

    String catalogName2 = "other_catalog.xcassets";
    tmp.newFolder(catalogName2);
    tmp.newFile(String.format("%s/Contents.json", catalogName2));
    String imagesetName2 = String.format("%s/some_other.imageset", catalogName2);
    tmp.newFolder(imagesetName2);
    tmp.newFile(String.format("%s/Contents.json", imagesetName2));
    String imageName2 = String.format("%s/image.png", imagesetName2);
    tmp.newFile(imageName2);

    AppleAssetCatalog.validateAssetCatalogs(
        ImmutableSortedSet.of(
            FakeSourcePath.of(filesystem, catalogName1),
            FakeSourcePath.of(filesystem, catalogName2)),
        BuildTargetFactory.newInstance("//:foobar"),
        filesystem,
        resolver,
        AppleAssetCatalog.ValidationType.XCODE);
  }

  @Test
  public void validCatalogPassesXcodeValidation() throws IOException {
    String catalogName1 = "catalog.xcassets";
    tmp.newFolder(catalogName1);
    tmp.newFile(String.format("%s/Contents.json", catalogName1));
    String imagesetName1 = String.format("%s/some.imageset", catalogName1);
    tmp.newFolder(imagesetName1);
    tmp.newFile(String.format("%s/Contents.json", imagesetName1));
    String imageName1 = String.format("%s/image1.png", imagesetName1);
    tmp.newFile(imageName1);

    String catalogName2 = "other_catalog.xcassets";
    tmp.newFolder(catalogName2);
    tmp.newFile(String.format("%s/Contents.json", catalogName2));
    String imagesetName2 = String.format("%s/some_other.imageset", catalogName2);
    tmp.newFolder(imagesetName2);
    tmp.newFile(String.format("%s/Contents.json", imagesetName2));
    String imageName2 = String.format("%s/image2.png", imagesetName2);
    tmp.newFile(imageName2);

    AppleAssetCatalog.validateAssetCatalogs(
        ImmutableSortedSet.of(
            FakeSourcePath.of(filesystem, catalogName1),
            FakeSourcePath.of(filesystem, catalogName2)),
        BuildTargetFactory.newInstance("//:foobar"),
        filesystem,
        resolver,
        AppleAssetCatalog.ValidationType.XCODE);
  }

  @Test
  public void missingXcassetsExtensionFailsStrictValidation() throws IOException {
    String invalidName = "catalog.wrongextension";
    tmp.newFolder(invalidName);

    exception.expect(HumanReadableException.class);
    exception.expectMessage(
        "Target //:foobar had asset catalog dir catalog.wrongextension - asset catalog dirs must end with .xcassets");
    AppleAssetCatalog.validateAssetCatalogs(
        ImmutableSortedSet.of(FakeSourcePath.of(filesystem, invalidName)),
        BuildTargetFactory.newInstance("//:foobar"),
        filesystem,
        resolver,
        AppleAssetCatalog.ValidationType.STRICT);
  }

  @Test
  public void missingCatalogContentsJsonPassesStrictValidation() throws IOException {
    String catalogName = "catalog.xcassets";
    tmp.newFolder(catalogName);

    AppleAssetCatalog.validateAssetCatalogs(
        ImmutableSortedSet.of(FakeSourcePath.of(filesystem, catalogName)),
        BuildTargetFactory.newInstance("//:foobar"),
        filesystem,
        resolver,
        AppleAssetCatalog.ValidationType.STRICT);
  }

  @Test
  public void missingContentsJsonFailStrictValidation() throws IOException {
    String catalogName = "catalog.xcassets";
    tmp.newFolder(catalogName);
    tmp.newFile(String.format("%s/Contents.json", catalogName));
    String appiconsetName = String.format("%s/some.appiconset", catalogName);
    tmp.newFolder(appiconsetName);
    String brandassetsName = String.format("%s/some.brandassets", catalogName);
    tmp.newFolder(brandassetsName);
    String cubetexturesetName = String.format("%s/some.cubetextureset", catalogName);
    tmp.newFolder(cubetexturesetName);
    String datasetName = String.format("%s/some.dataset", catalogName);
    tmp.newFolder(datasetName);
    String imagesetName = String.format("%s/some.imageset", catalogName);
    tmp.newFolder(imagesetName);
    String imagestackName = String.format("%s/some.imagestack", catalogName);
    tmp.newFolder(imagestackName);
    String launchimageName = String.format("%s/some.launchimage", catalogName);
    tmp.newFolder(launchimageName);
    String mipmapsetName = String.format("%s/some.mipmapset", catalogName);
    tmp.newFolder(mipmapsetName);
    String stickerName = String.format("%s/some.sticker", catalogName);
    tmp.newFolder(stickerName);
    String stickerpackName = String.format("%s/some.stickerpack", catalogName);
    tmp.newFolder(stickerpackName);
    String stickersequenceName = String.format("%s/some.stickersequence", catalogName);
    tmp.newFolder(stickersequenceName);
    String texturesetName = String.format("%s/some.textureset", catalogName);
    tmp.newFolder(texturesetName);
    String complicationsetName = String.format("%s/some.complicationset", catalogName);
    tmp.newFolder(complicationsetName);

    exception.expect(HumanReadableException.class);
    exception.expectMessage("some.appiconset doesn't have Contents.json");
    exception.expectMessage("some.brandassets doesn't have Contents.json");
    exception.expectMessage("some.cubetextureset doesn't have Contents.json");
    exception.expectMessage("some.dataset doesn't have Contents.json");
    exception.expectMessage("some.imageset doesn't have Contents.json");
    exception.expectMessage("some.imagestack doesn't have Contents.json");
    exception.expectMessage("some.launchimage doesn't have Contents.json");
    exception.expectMessage("some.mipmapset doesn't have Contents.json");
    exception.expectMessage("some.sticker doesn't have Contents.json");
    exception.expectMessage("some.stickerpack doesn't have Contents.json");
    exception.expectMessage("some.stickersequence doesn't have Contents.json");
    exception.expectMessage("some.textureset doesn't have Contents.json");
    exception.expectMessage("some.complicationset doesn't have Contents.json");
    AppleAssetCatalog.validateAssetCatalogs(
        ImmutableSortedSet.of(FakeSourcePath.of(filesystem, catalogName)),
        BuildTargetFactory.newInstance("//:foobar"),
        filesystem,
        resolver,
        AppleAssetCatalog.ValidationType.STRICT);
  }

  @Test
  public void duplicateImageNamesFailStrictValidation() throws IOException {
    String catalogName1 = "catalog.xcassets";
    tmp.newFolder(catalogName1);
    tmp.newFile(String.format("%s/Contents.json", catalogName1));
    String imagesetName1 = String.format("%s/some.imageset", catalogName1);
    tmp.newFolder(imagesetName1);
    tmp.newFile(String.format("%s/Contents.json", imagesetName1));
    String imageName1 = String.format("%s/image.png", imagesetName1);
    tmp.newFile(imageName1);

    String catalogName2 = "other_catalog.xcassets";
    tmp.newFolder(catalogName2);
    tmp.newFile(String.format("%s/Contents.json", catalogName2));
    String imagesetName2 = String.format("%s/some_other.imageset", catalogName2);
    tmp.newFolder(imagesetName2);
    tmp.newFile(String.format("%s/Contents.json", imagesetName2));
    String imageName2 = String.format("%s/image.png", imagesetName2);
    tmp.newFile(imageName2);

    exception.expect(HumanReadableException.class);
    exception.expectMessage(
        "image.png is included by two asset catalogs: 'other_catalog.xcassets' and 'catalog.xcassets'");
    AppleAssetCatalog.validateAssetCatalogs(
        ImmutableSortedSet.of(
            FakeSourcePath.of(filesystem, catalogName1),
            FakeSourcePath.of(filesystem, catalogName2)),
        BuildTargetFactory.newInstance("//:foobar"),
        filesystem,
        resolver,
        AppleAssetCatalog.ValidationType.STRICT);
  }

  @Test
  public void validCatalogPassesStrictValidation() throws IOException {
    String catalogName1 = "catalog.xcassets";
    tmp.newFolder(catalogName1);
    tmp.newFile(String.format("%s/Contents.json", catalogName1));
    String imagesetName1 = String.format("%s/some.imageset", catalogName1);
    tmp.newFolder(imagesetName1);
    tmp.newFile(String.format("%s/Contents.json", imagesetName1));
    String imageName1 = String.format("%s/image1.png", imagesetName1);
    tmp.newFile(imageName1);

    String catalogName2 = "other_catalog.xcassets";
    tmp.newFolder(catalogName2);
    tmp.newFile(String.format("%s/Contents.json", catalogName2));
    String imagesetName2 = String.format("%s/some_other.imageset", catalogName2);
    tmp.newFolder(imagesetName2);
    tmp.newFile(String.format("%s/Contents.json", imagesetName2));
    String imageName2 = String.format("%s/image2.png", imagesetName2);
    tmp.newFile(imageName2);

    AppleAssetCatalog.validateAssetCatalogs(
        ImmutableSortedSet.of(
            FakeSourcePath.of(filesystem, catalogName1),
            FakeSourcePath.of(filesystem, catalogName2)),
        BuildTargetFactory.newInstance("//:foobar"),
        filesystem,
        resolver,
        AppleAssetCatalog.ValidationType.STRICT);
  }
}
