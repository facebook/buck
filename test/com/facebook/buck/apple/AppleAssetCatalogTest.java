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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.SingleThreadedBuildRuleResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.util.HumanReadableException;
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
      DefaultSourcePathResolver.from(
          new SourcePathRuleFinder(
              new SingleThreadedBuildRuleResolver(
                  TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())));

  @Rule public final TemporaryPaths tmp = new TemporaryPaths();

  @Rule public final ExpectedException exception = ExpectedException.none();

  @Before
  public void setUp() throws InterruptedException {
    filesystem = new ProjectFilesystem(tmp.getRoot());
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
        ImmutableSortedSet.of(new FakeSourcePath(filesystem, invalidName)),
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
        ImmutableSortedSet.of(new FakeSourcePath(filesystem, catalogName)),
        BuildTargetFactory.newInstance("//:foobar"),
        filesystem,
        resolver,
        AppleAssetCatalog.ValidationType.XCODE);
  }

  @Test
  public void missingImagesetContentsJsonPassesXcodeValidation() throws IOException {
    String catalogName = "catalog.xcassets";
    tmp.newFolder(catalogName);
    tmp.newFile(String.format("%s/Contents.json", catalogName));
    String imagesetName = String.format("%s/some.imageset", catalogName);
    tmp.newFolder(imagesetName);

    AppleAssetCatalog.validateAssetCatalogs(
        ImmutableSortedSet.of(new FakeSourcePath(filesystem, catalogName)),
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
            new FakeSourcePath(filesystem, catalogName1),
            new FakeSourcePath(filesystem, catalogName2)),
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
            new FakeSourcePath(filesystem, catalogName1),
            new FakeSourcePath(filesystem, catalogName2)),
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
        ImmutableSortedSet.of(new FakeSourcePath(filesystem, invalidName)),
        BuildTargetFactory.newInstance("//:foobar"),
        filesystem,
        resolver,
        AppleAssetCatalog.ValidationType.STRICT);
  }

  @Test
  public void missingCatalogContentsJsonFailsStrictValidation() throws IOException {
    String catalogName = "catalog.xcassets";
    tmp.newFolder(catalogName);

    exception.expect(HumanReadableException.class);
    exception.expectMessage("catalog.xcassets doesn't have Contents.json");
    AppleAssetCatalog.validateAssetCatalogs(
        ImmutableSortedSet.of(new FakeSourcePath(filesystem, catalogName)),
        BuildTargetFactory.newInstance("//:foobar"),
        filesystem,
        resolver,
        AppleAssetCatalog.ValidationType.STRICT);
  }

  @Test
  public void missingImagesetContentsJsonFailsStrictValidation() throws IOException {
    String catalogName = "catalog.xcassets";
    tmp.newFolder(catalogName);
    tmp.newFile(String.format("%s/Contents.json", catalogName));
    String imagesetName = String.format("%s/some.imageset", catalogName);
    tmp.newFolder(imagesetName);

    exception.expect(HumanReadableException.class);
    exception.expectMessage("some.imageset doesn't have Contents.json");
    AppleAssetCatalog.validateAssetCatalogs(
        ImmutableSortedSet.of(new FakeSourcePath(filesystem, catalogName)),
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
            new FakeSourcePath(filesystem, catalogName1),
            new FakeSourcePath(filesystem, catalogName2)),
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
            new FakeSourcePath(filesystem, catalogName1),
            new FakeSourcePath(filesystem, catalogName2)),
        BuildTargetFactory.newInstance("//:foobar"),
        filesystem,
        resolver,
        AppleAssetCatalog.ValidationType.STRICT);
  }
}
