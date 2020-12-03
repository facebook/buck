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

package com.facebook.buck.jvm.core;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.junit.MatcherAssert.assertThat;

import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.FlavorSet;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.BaseBuckPaths;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Test;

public class BuildTargetValueTest {

  private RelPath relBuckOut;
  private BaseBuckPaths defaultBaseBuckPaths;

  @Before
  public void setUp() throws Exception {
    relBuckOut = RelPath.of(Paths.get("buck-out"));
    defaultBaseBuckPaths = BaseBuckPaths.of(relBuckOut, relBuckOut, false);
  }

  @Test
  public void buildTargetValueWithTargetConfigHash() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildTargetValue buildTargetValue =
        BuildTargetValue.of(target, BaseBuckPaths.of(relBuckOut, relBuckOut, true));
    ForwardRelativePath expectedCellRelativeBasePath = target.getCellRelativeBasePath().getPath();

    assertThat(buildTargetValue.getCellRelativeBasePath(), equalTo(expectedCellRelativeBasePath));
    assertThat(
        "in case of target config hash: these paths should NOT be equal as config hash should be added as part of the path",
        buildTargetValue.getBasePathForBaseName(),
        not(equalTo(expectedCellRelativeBasePath)));
  }

  @Test
  public void buildTargetValueWithoutTargetConfigHash() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildTargetValue buildTargetValue = BuildTargetValue.of(target, defaultBaseBuckPaths);
    ForwardRelativePath expectedCellRelativeBasePath = target.getCellRelativeBasePath().getPath();

    assertThat(buildTargetValue.getCellRelativeBasePath(), equalTo(expectedCellRelativeBasePath));
    assertThat(
        "in case of target config hash is disabled: these paths should be equal",
        buildTargetValue.getBasePathForBaseName(),
        equalTo(expectedCellRelativeBasePath));
  }

  @Test
  public void buildTargetValueWithNoFlavors() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildTargetValue buildTargetValue = BuildTargetValue.of(target, defaultBaseBuckPaths);
    ForwardRelativePath expectedCellRelativeBasePath = target.getCellRelativeBasePath().getPath();

    assertThat(buildTargetValue.getCellRelativeBasePath(), equalTo(expectedCellRelativeBasePath));
    assertThat(
        "in case of target config hash is disabled: these paths should be equal",
        buildTargetValue.getBasePathForBaseName(),
        equalTo(expectedCellRelativeBasePath));
    assertThat(buildTargetValue.getShortName(), equalTo(target.getShortName()));
    assertThat(
        buildTargetValue.getShortNameAndFlavorPostfix(),
        equalTo(target.getShortNameAndFlavorPostfix()));
    assertThat(buildTargetValue.getFullyQualifiedName(), equalTo(target.getFullyQualifiedName()));

    assertThat(buildTargetValue.isLibraryJar(), equalTo(true));
    assertThat(buildTargetValue.isSourceAbi(), equalTo(false));
    assertThat(buildTargetValue.isSourceOnlyAbi(), equalTo(false));

    assertThat(buildTargetValue.isFlavored(), equalTo(false));
    assertThat(buildTargetValue.getFlavors(), equalTo(FlavorSet.NO_FLAVORS));
    assertThat(buildTargetValue.getFlavors(), equalTo(target.getFlavors()));
  }

  @Test
  public void buildTargetValueWithFlavors() {
    BuildTarget target =
        BuildTargetFactory.newInstance("//foo/bar", "baz", InternalFlavor.of("d8"));

    BuildTargetValue buildTargetValue = BuildTargetValue.of(target, defaultBaseBuckPaths);
    ForwardRelativePath actualCellRelativeBasePath = target.getCellRelativeBasePath().getPath();

    assertThat(buildTargetValue.getCellRelativeBasePath(), equalTo(actualCellRelativeBasePath));
    assertThat(
        "in case of target config hash is disabled: these paths should be equal",
        buildTargetValue.getBasePathForBaseName(),
        equalTo(actualCellRelativeBasePath));
    assertThat(buildTargetValue.getShortName(), equalTo(target.getShortName()));
    assertThat(
        buildTargetValue.getShortNameAndFlavorPostfix(),
        equalTo(target.getShortNameAndFlavorPostfix()));
    assertThat(buildTargetValue.getFullyQualifiedName(), equalTo(target.getFullyQualifiedName()));

    assertThat(buildTargetValue.isLibraryJar(), equalTo(true));
    assertThat(buildTargetValue.isSourceAbi(), equalTo(false));
    assertThat(buildTargetValue.isSourceOnlyAbi(), equalTo(false));

    assertThat(buildTargetValue.isFlavored(), equalTo(true));
    assertThat(buildTargetValue.getFlavors(), equalTo(FlavorSet.of(InternalFlavor.of("d8"))));
    assertThat(buildTargetValue.getFlavors(), equalTo(target.getFlavors()));
  }

  @Test
  public void sourceAbi() {
    BuildTarget target =
        BuildTargetFactory.newInstance("//foo/bar", "baz", InternalFlavor.of("d8"));
    BuildTargetValue buildTargetValue = BuildTargetValue.of(target, defaultBaseBuckPaths);

    BuildTargetValue sourceAbiTarget = BuildTargetValue.sourceAbiTarget(buildTargetValue);

    ForwardRelativePath actualCellRelativeBasePath = target.getCellRelativeBasePath().getPath();

    assertThat(sourceAbiTarget.getCellRelativeBasePath(), equalTo(actualCellRelativeBasePath));
    assertThat(
        "in case of target config hash is disabled: these paths should be equal",
        sourceAbiTarget.getBasePathForBaseName(),
        equalTo(actualCellRelativeBasePath));
    assertThat(sourceAbiTarget.getShortName(), equalTo(target.getShortName()));
    assertThat(
        sourceAbiTarget.getShortNameAndFlavorPostfix(),
        equalTo(target.getShortNameAndFlavorPostfix() + "," + JavaAbis.SOURCE_ABI_FLAVOR));
    assertThat(
        sourceAbiTarget.getFullyQualifiedName(),
        equalTo(target.getFullyQualifiedName() + "," + JavaAbis.SOURCE_ABI_FLAVOR));

    assertThat(sourceAbiTarget.isLibraryJar(), equalTo(false));
    assertThat(sourceAbiTarget.isSourceAbi(), equalTo(true));
    assertThat(sourceAbiTarget.isSourceOnlyAbi(), equalTo(false));

    assertThat(sourceAbiTarget.isFlavored(), equalTo(true));
    assertThat(
        sourceAbiTarget.getFlavors(),
        equalTo(FlavorSet.of(InternalFlavor.of("d8"), JavaAbis.SOURCE_ABI_FLAVOR)));
    assertThat(
        sourceAbiTarget.getFlavors(),
        equalTo(target.getFlavors().withAdded(ImmutableSet.of(JavaAbis.SOURCE_ABI_FLAVOR))));
  }

  @Test
  public void sourceOnlyAbi() {
    BuildTarget target =
        BuildTargetFactory.newInstance("//foo/bar", "baz", InternalFlavor.of("d8"));
    BuildTargetValue buildTargetValue = BuildTargetValue.of(target, defaultBaseBuckPaths);

    BuildTargetValue sourceOnlyAbiTarget = BuildTargetValue.sourceOnlyAbiTarget(buildTargetValue);

    ForwardRelativePath actualCellRelativeBasePath = target.getCellRelativeBasePath().getPath();

    assertThat(sourceOnlyAbiTarget.getCellRelativeBasePath(), equalTo(actualCellRelativeBasePath));
    assertThat(
        "in case of target config hash is disabled: these paths should be equal",
        sourceOnlyAbiTarget.getBasePathForBaseName(),
        equalTo(actualCellRelativeBasePath));
    assertThat(sourceOnlyAbiTarget.getShortName(), equalTo(target.getShortName()));
    assertThat(
        sourceOnlyAbiTarget.getShortNameAndFlavorPostfix(),
        equalTo(target.getShortNameAndFlavorPostfix() + "," + JavaAbis.SOURCE_ONLY_ABI_FLAVOR));
    assertThat(
        sourceOnlyAbiTarget.getFullyQualifiedName(),
        equalTo(target.getFullyQualifiedName() + "," + JavaAbis.SOURCE_ONLY_ABI_FLAVOR));

    assertThat(sourceOnlyAbiTarget.isLibraryJar(), equalTo(false));
    assertThat(sourceOnlyAbiTarget.isSourceAbi(), equalTo(false));
    assertThat(sourceOnlyAbiTarget.isSourceOnlyAbi(), equalTo(true));

    assertThat(sourceOnlyAbiTarget.isFlavored(), equalTo(true));
    assertThat(
        sourceOnlyAbiTarget.getFlavors(),
        equalTo(FlavorSet.of(InternalFlavor.of("d8"), JavaAbis.SOURCE_ONLY_ABI_FLAVOR)));
    assertThat(
        sourceOnlyAbiTarget.getFlavors(),
        equalTo(target.getFlavors().withAdded(ImmutableSet.of(JavaAbis.SOURCE_ONLY_ABI_FLAVOR))));
  }

  @Test
  public void libraryTarget() {
    BuildTarget target =
        BuildTargetFactory.newInstance(
            "//foo/bar",
            "baz",
            InternalFlavor.of("d8"),
            JavaAbis.SOURCE_ONLY_ABI_FLAVOR,
            JavaAbis.SOURCE_ABI_FLAVOR);
    BuildTargetValue buildTargetValue = BuildTargetValue.of(target, defaultBaseBuckPaths);

    BuildTargetValue libraryTarget = BuildTargetValue.libraryTarget(buildTargetValue);

    ForwardRelativePath actualCellRelativeBasePath = target.getCellRelativeBasePath().getPath();

    assertThat(libraryTarget.getCellRelativeBasePath(), equalTo(actualCellRelativeBasePath));
    assertThat(
        "in case of target config hash is disabled: these paths should be equal",
        libraryTarget.getBasePathForBaseName(),
        equalTo(actualCellRelativeBasePath));
    assertThat(libraryTarget.getShortName(), equalTo(target.getShortName()));
    assertThat(libraryTarget.getShortNameAndFlavorPostfix(), equalTo("baz#d8"));
    assertThat(libraryTarget.getFullyQualifiedName(), equalTo("//foo/bar:baz#d8"));

    assertThat(libraryTarget.isLibraryJar(), equalTo(true));
    assertThat(libraryTarget.isSourceAbi(), equalTo(false));
    assertThat(libraryTarget.isSourceOnlyAbi(), equalTo(false));

    assertThat(libraryTarget.isFlavored(), equalTo(true));
    assertThat(libraryTarget.getFlavors(), equalTo(FlavorSet.of(InternalFlavor.of("d8"))));
    assertThat(
        libraryTarget.getFlavors(),
        equalTo(
            target
                .getFlavors()
                .without(
                    ImmutableSet.of(JavaAbis.SOURCE_ABI_FLAVOR, JavaAbis.SOURCE_ONLY_ABI_FLAVOR))));
  }
}
