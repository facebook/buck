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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.apple.AppleBinaryBuilder;
import com.facebook.buck.apple.AppleBundleBuilder;
import com.facebook.buck.apple.AppleBundleExtension;
import com.facebook.buck.apple.AppleLibraryBuilder;
import com.facebook.buck.apple.AppleTestBuilder;
import com.facebook.buck.apple.xcode.xcodeproj.ProductType;
import com.facebook.buck.apple.xcode.xcodeproj.ProductTypes;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.UserFlavor;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.cxx.toolchain.impl.DefaultCxxPlatforms;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.Before;
import org.junit.Test;

public class XcodeNativeTargetGeneratorTest {

  private Cells cells;
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

    cells = (new TestCellBuilder()).build();

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

  @Test
  public void testSwapWithSharedBundes() {
    BuildTarget sharedLibrary = BuildTargetFactory.newInstance("//foo:shared#shared");
    BuildTarget bundleTarget = BuildTargetFactory.newInstance("//foo", "sharedFramework");

    TargetNode<?> bundleNode =
        AppleBundleBuilder.createBuilder(bundleTarget)
            .setBinary(sharedLibrary)
            .setExtension(Either.ofLeft(AppleBundleExtension.FRAMEWORK))
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .setProductName(Optional.of("shared"))
            .build();

    TargetNode<?> sharedNode = AppleLibraryBuilder.createBuilder(sharedLibrary).build();

    ImmutableMap<BuildTarget, TargetNode<?>> sharedLibraryToBundle =
        ImmutableMap.of(sharedLibrary, bundleNode);

    FluentIterable<TargetNode<?>> input = FluentIterable.of(sharedNode);
    FluentIterable<TargetNode<?>> output =
        XcodeNativeTargetGenerator.swapSharedLibrariesForBundles(input, sharedLibraryToBundle);
    assertTrue(output.contains(bundleNode));
    assertFalse(output.contains(sharedNode));
    assertEquals(output.size(), 1);
  }

  @Test
  public void testPlatformSourcesAndHeaders() {
    SourceWithFlags androidSource = SourceWithFlags.of(FakeSourcePath.of("androidFile.cpp"));
    SourceWithFlags iOSAndSimulatorSource =
        SourceWithFlags.of(FakeSourcePath.of("iOSAndSimulatorFile.cpp"));
    SourceWithFlags macOSSource = SourceWithFlags.of(FakeSourcePath.of("macOSFile.cpp"));

    Pattern androidPattern = Pattern.compile("android");
    Pattern simulatorPattern = Pattern.compile("^iphonesim.*");
    Pattern iOSPattern = Pattern.compile("iphoneos");
    Pattern macOSPattern = Pattern.compile("macos");

    Pair<Pattern, ImmutableSortedSet<SourceWithFlags>> androidSources =
        new Pair<>(androidPattern, ImmutableSortedSet.of(androidSource));
    Pair<Pattern, ImmutableSortedSet<SourceWithFlags>> simulatorSources =
        new Pair<>(simulatorPattern, ImmutableSortedSet.of(iOSAndSimulatorSource));
    Pair<Pattern, ImmutableSortedSet<SourceWithFlags>> iOSSources =
        new Pair<>(iOSPattern, ImmutableSortedSet.of(iOSAndSimulatorSource));
    Pair<Pattern, ImmutableSortedSet<SourceWithFlags>> macOSSources =
        new Pair<>(macOSPattern, ImmutableSortedSet.of(macOSSource));

    ImmutableList.Builder<Pair<Pattern, ImmutableSortedSet<SourceWithFlags>>>
        platformSourcesbuilder = ImmutableList.builder();
    ImmutableList<Pair<Pattern, ImmutableSortedSet<SourceWithFlags>>> platformSources =
        platformSourcesbuilder
            .add(androidSources)
            .add(simulatorSources)
            .add(iOSSources)
            .add(macOSSources)
            .build();

    SourcePath androidHeader = FakeSourcePath.of("androidFile.h");
    SourcePath simulatorHeader = FakeSourcePath.of("simulatorFile.h");
    SourcePath macOSAndIOSHeader = FakeSourcePath.of("macOSAndIOSFile.h");

    Pattern androidPattern2 = Pattern.compile("android");
    Pattern simulatorPattern2 = Pattern.compile("iphonesimulator");
    Pattern iOSPattern2 = Pattern.compile("iphoneos");
    Pattern macOSPattern2 = Pattern.compile("mac");

    Pair<Pattern, SourceSortedSet> androidHeaders =
        new Pair<>(
            androidPattern2,
            SourceSortedSet.ofUnnamedSources(ImmutableSortedSet.of(androidHeader)));
    Pair<Pattern, SourceSortedSet> simulatorHeaders =
        new Pair<>(
            simulatorPattern2,
            SourceSortedSet.ofUnnamedSources(ImmutableSortedSet.of(simulatorHeader)));
    Pair<Pattern, SourceSortedSet> iOSHeaders =
        new Pair<>(
            iOSPattern2,
            SourceSortedSet.ofUnnamedSources(ImmutableSortedSet.of(macOSAndIOSHeader)));
    Pair<Pattern, SourceSortedSet> macOSHeaders =
        new Pair<>(
            macOSPattern2,
            SourceSortedSet.ofUnnamedSources(ImmutableSortedSet.of(macOSAndIOSHeader)));

    SourcePath androidAndMacHeader = FakeSourcePath.of("androidAndMacFile.h");
    SourcePath iPhoneHeader = FakeSourcePath.of("iOSAndSimulatorFile.h");

    Pattern androidPattern3 = Pattern.compile("an.*");
    Pattern iPhonePattern = Pattern.compile("iphone");

    Pair<Pattern, SourceSortedSet> androidHeaders2 =
        new Pair<>(
            androidPattern3,
            SourceSortedSet.ofNamedSources(ImmutableSortedMap.of("ignored", androidAndMacHeader)));
    Pair<Pattern, SourceSortedSet> iPhoneHeaders =
        new Pair<>(
            iPhonePattern,
            SourceSortedSet.ofNamedSources(ImmutableSortedMap.of("ignored", iPhoneHeader)));
    Pair<Pattern, SourceSortedSet> macOSHeaders2 =
        new Pair<>(
            macOSPattern2,
            SourceSortedSet.ofNamedSources(ImmutableSortedMap.of("ignored", androidAndMacHeader)));

    ImmutableList.Builder<Pair<Pattern, SourceSortedSet>> platformHeadersBuilder =
        ImmutableList.builder();
    platformHeadersBuilder.add(androidHeaders);
    platformHeadersBuilder.add(simulatorHeaders);
    platformHeadersBuilder.add(iOSHeaders);
    platformHeadersBuilder.add(macOSHeaders);
    platformHeadersBuilder.add(androidHeaders2);
    platformHeadersBuilder.add(iPhoneHeaders);
    platformHeadersBuilder.add(macOSHeaders2);
    ImmutableList<Pair<Pattern, SourceSortedSet>> platformHeaders = platformHeadersBuilder.build();

    ImmutableList.Builder<Pair<Pattern, Iterable<SourcePath>>> platformHeadersIterableBuilder =
        ImmutableList.builder();
    for (Pair<Pattern, SourceSortedSet> platformHeader : platformHeaders) {
      platformHeadersIterableBuilder.add(
          new Pair<>(
              platformHeader.getFirst(),
              XcodeNativeTargetGenerator.getHeaderSourcePaths(platformHeader.getSecond())));
    }
    ImmutableList<Pair<Pattern, Iterable<SourcePath>>> platformHeadersIterable =
        platformHeadersIterableBuilder.build();

    UserFlavor simulator = UserFlavor.of("iphonesimulator11.4-i386", "buck boilerplate");
    UserFlavor macOS = UserFlavor.of("macosx10-x86_64", "buck boilerplate");
    UserFlavor iOS = UserFlavor.of("iphoneos-x86_64", "buck boilerplate");

    ImmutableSet<Flavor> appleFlavors = ImmutableSet.of(simulator, iOS, macOS);

    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();

    ImmutableMap<String, ImmutableSortedSet<String>> result =
        XcodeNativeTargetGenerator.gatherExcludedSources(
            appleFlavors,
            platformSources,
            platformHeadersIterable,
            Paths.get(".."),
            graphBuilder.getSourcePathResolver());

    ImmutableSet.Builder<String> excludedResultsBuilder = ImmutableSet.builder();
    ImmutableSet<String> excludedResults =
        excludedResultsBuilder
            .add("'../androidFile.cpp'")
            .add("'../iOSAndSimulatorFile.cpp'")
            .add("'../macOSFile.cpp'")
            .add("'../androidFile.h'")
            .add("'../simulatorFile.h'")
            .add("'../macOSAndIOSFile.h'")
            .add("'../androidAndMacFile.h'")
            .add("'../iOSAndSimulatorFile.h'")
            .build();
    ImmutableSet.Builder<String> simulatorResultsBuilder = ImmutableSet.builder();
    ImmutableSet<String> simulatorResults =
        simulatorResultsBuilder
            .add("'../iOSAndSimulatorFile.cpp'")
            .add("'../simulatorFile.h'")
            .add("'../iOSAndSimulatorFile.h'")
            .build();
    ImmutableSet.Builder<String> iOSResultsBuilder = ImmutableSet.builder();
    ImmutableSet<String> iOSResults =
        iOSResultsBuilder
            .add("'../iOSAndSimulatorFile.cpp'")
            .add("'../macOSAndIOSFile.h'")
            .add("'../iOSAndSimulatorFile.h'")
            .build();
    ImmutableSet.Builder<String> macOSResultsBuilder = ImmutableSet.builder();
    ImmutableSet<String> macOSResults =
        macOSResultsBuilder
            .add("'../macOSFile.cpp'")
            .add("'../macOSAndIOSFile.h'")
            .add("'../androidAndMacFile.h'")
            .build();

    ImmutableMap.Builder<String, ImmutableSet<String>> expectedBuilder = ImmutableMap.builder();
    expectedBuilder.put("EXCLUDED_SOURCE_FILE_NAMES", excludedResults);
    expectedBuilder.put("INCLUDED_SOURCE_FILE_NAMES[sdk=iphoneos*][arch=x86_64]", iOSResults);
    expectedBuilder.put(
        "INCLUDED_SOURCE_FILE_NAMES[sdk=iphonesimulator*][arch=i386]", simulatorResults);
    expectedBuilder.put("INCLUDED_SOURCE_FILE_NAMES[sdk=macosx*][arch=x86_64]", macOSResults);
    ImmutableMap<String, ImmutableSet<String>> expectedResult = expectedBuilder.build();

    assertEquals(result, expectedResult);
  }

  public ProductType getProductType(TargetNode targetNode) {
    return XcodeNativeTargetGenerator.getProductType(targetNode, targetGraph).get();
  }
}
