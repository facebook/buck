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

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.AppleBinaryBuilder;
import com.facebook.buck.apple.AppleBundleBuilder;
import com.facebook.buck.apple.AppleBundleExtension;
import com.facebook.buck.apple.AppleLibraryBuilder;
import com.facebook.buck.apple.AppleTestBuilder;
import com.facebook.buck.apple.xcode.xcodeproj.ProductType;
import com.facebook.buck.apple.xcode.xcodeproj.ProductTypes;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.cxx.toolchain.impl.DefaultCxxPlatforms;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class XcodeNativeTargetGeneratorTest {

  private Cell rootCell;
  private BuildTarget bazTestTarget;
  private BuildTarget bazLibTarget;
  private BuildTarget quxTestTarget;
  private BuildTarget quxLibTarget;
  private BuildTarget fooAppBinaryTarget;
  private BuildTarget barBinaryTarget;
  private BuildTarget barExtTarget;
  private BuildTarget fooAppBundleTarget;
  private TargetNode bazTestNode;
  private TargetNode bazLibNode;
  private TargetNode quxTestNode;
  private TargetNode quxLibNode;
  private TargetNode fooAppBinaryNode;
  private TargetNode barBinaryNode;
  private TargetNode barExtNode;
  private TargetNode fooAppBundleNode;
  private TargetGraph targetGraph;

  @Before
  public void setUp() {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);

    rootCell = (new TestCellBuilder()).build();

    // Create the following dep tree:
    //   FooAppBundle -has-extension-> BarExt -> BarBinary
    //   |
    //   V
    //   FooAppBinary -has-dep-> QuxLib -has-ui-test-> QuxTest
    //   |                       /
    //   |                    /
    //   |                 /
    //   |              /
    //   |           /
    //   |        /
    //   V     V
    //   BazLib -has-unit-test-> BazTest

    bazTestTarget = BuildTargetFactory.newInstance("//baz:test");
    bazTestNode =
        AppleTestBuilder.createBuilder(bazTestTarget)
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .build();

    bazLibTarget = BuildTargetFactory.newInstance("//baz:lib");
    bazLibNode =
        AppleLibraryBuilder.createBuilder(bazLibTarget)
            .setTests(ImmutableSortedSet.of(bazTestTarget))
            .build();

    quxTestTarget = BuildTargetFactory.newInstance("//qux:test");
    quxTestNode =
        AppleTestBuilder.createBuilder(quxTestTarget)
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .isUiTest(true)
            .build();

    quxLibTarget = BuildTargetFactory.newInstance("//qux:lib");
    quxLibNode =
        AppleLibraryBuilder.createBuilder(quxLibTarget)
            .setDeps(ImmutableSortedSet.of(bazLibTarget))
            .setTests(ImmutableSortedSet.of(quxTestTarget))
            .build();

    fooAppBinaryTarget = BuildTargetFactory.newInstance("//foo:appBinary");
    fooAppBinaryNode =
        AppleBinaryBuilder.createBuilder(fooAppBinaryTarget)
            .setDeps(ImmutableSortedSet.of(quxLibTarget, bazLibTarget))
            .build();

    barBinaryTarget = BuildTargetFactory.newInstance("//bar:binary");
    barBinaryNode = AppleBinaryBuilder.createBuilder(barBinaryTarget).build();

    barExtTarget = BuildTargetFactory.newInstance("//bar", "ext", DefaultCxxPlatforms.FLAVOR);
    barExtNode =
        AppleBundleBuilder.createBuilder(barExtTarget)
            .setBinary(barBinaryTarget)
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .setExtension(Either.ofLeft(AppleBundleExtension.APPEX))
            .setXcodeProductType(Optional.of(ProductTypes.APP_EXTENSION.getIdentifier()))
            .build();

    fooAppBundleTarget = BuildTargetFactory.newInstance("//foo:appBundle");
    fooAppBundleNode =
        AppleBundleBuilder.createBuilder(fooAppBundleTarget)
            .setBinary(fooAppBinaryTarget)
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .setExtension(Either.ofLeft(AppleBundleExtension.APP))
            .setDeps(ImmutableSortedSet.of(barExtTarget))
            .build();

    targetGraph =
        TargetGraphFactory.newInstance(
            bazTestNode,
            bazLibNode,
            quxTestNode,
            quxLibNode,
            fooAppBinaryNode,
            barBinaryNode,
            barExtNode,
            fooAppBundleNode);
  }

  @Test
  public void testGetProductType() {
    assertEquals(ProductTypes.UNIT_TEST, getProductType(bazTestNode));
    assertEquals(ProductTypes.STATIC_LIBRARY, getProductType(bazLibNode));
    assertEquals(ProductTypes.UI_TEST, getProductType(quxTestNode));
    assertEquals(ProductTypes.STATIC_LIBRARY, getProductType(quxLibNode));
    assertEquals(ProductTypes.TOOL, getProductType(fooAppBinaryNode));
    assertEquals(ProductTypes.TOOL, getProductType(barBinaryNode));
    assertEquals(ProductTypes.APP_EXTENSION, getProductType(barExtNode));
    assertEquals(ProductTypes.APPLICATION, getProductType(fooAppBundleNode));
  }

  public ProductType getProductType(TargetNode targetNode) {
    return XcodeNativeTargetGenerator.getProductType(targetNode, targetGraph).get();
  }
}
