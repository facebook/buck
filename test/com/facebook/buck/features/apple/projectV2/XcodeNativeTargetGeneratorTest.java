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

import com.facebook.buck.apple.AppleBinaryBuilder;
import com.facebook.buck.apple.AppleBundleBuilder;
import com.facebook.buck.apple.AppleBundleExtension;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleDependenciesCache;
import com.facebook.buck.apple.AppleLibraryBuilder;
import com.facebook.buck.apple.AppleTestBuilder;
import com.facebook.buck.apple.XCodeDescriptions;
import com.facebook.buck.apple.XCodeDescriptionsFactory;
import com.facebook.buck.apple.xcode.xcodeproj.ProductType;
import com.facebook.buck.apple.xcode.xcodeproj.ProductTypes;
import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.UserFlavor;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.cxx.toolchain.impl.DefaultCxxPlatforms;
import com.facebook.buck.features.filegroup.FilegroupBuilder;
import com.facebook.buck.features.halide.HalideLibraryBuilder;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.coercer.SourceSortedSet;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.swift.SwiftBuckConfig;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

public class XcodeNativeTargetGeneratorTest {

  private XcodeNativeTargetGenerator xcodeNativeTargetGenerator;

  // Test graph
  private Cells cells;
  private BuildTarget tuxFileGroupTarget;
  private BuildTarget bazTestTarget;
  private BuildTarget bazLibTarget;
  private BuildTarget quxTestTarget;
  private BuildTarget quxLibTarget;
  private BuildTarget fooAppBinaryTarget;
  private BuildTarget barBinaryTarget;
  private BuildTarget barExtTarget;
  private BuildTarget fooAppBundleTarget;
  private TargetNode tuxFileGroupNode;
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
  public void setUp() throws IOException {
    Assume.assumeThat(Platform.detect(), Matchers.not(Platform.WINDOWS));

    setupTargetGraph();

    XCodeDescriptions xcodeDescriptions =
        XCodeDescriptionsFactory.create(BuckPluginManagerFactory.createPluginManager());
    AppleDependenciesCache dependenciesCache = new AppleDependenciesCache(targetGraph);
    ProjectGenerationStateCache projGenerationStateCache = new ProjectGenerationStateCache();
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    Cell projectCell = cells.getRootCell();

    ActionGraphBuilder actionGraphBuilder =
        AppleProjectHelper.getActionGraphBuilderNodeFunction(targetGraph);
    SourcePathResolverAdapter defaultPathResolver =
        AppleProjectHelper.defaultSourcePathResolverAdapter(actionGraphBuilder);

    CxxPlatform cxxPlatform = CxxPlatformUtils.DEFAULT_PLATFORM;
    ImmutableSet<Flavor> appleCxxFlavors = ImmutableSet.of();

    ProjectSourcePathResolver projectSourcePathResolver =
        new ProjectSourcePathResolver(
            cells.getRootCell(), defaultPathResolver, targetGraph, actionGraphBuilder);

    BuckConfig config = AppleProjectHelper.createDefaultBuckConfig(projectFilesystem);

    CxxBuckConfig cxxBuckConfig = new CxxBuckConfig(config);
    AppleConfig appleConfig = config.getView(AppleConfig.class);
    SwiftBuckConfig swiftBuckConfig = new SwiftBuckConfig(config);

    PathRelativizer pathRelativizer = AppleProjectHelper.defaultPathRelativizer("test-out");

    SwiftAttributeParser swiftAttributeParser =
        new SwiftAttributeParser(swiftBuckConfig, projGenerationStateCache, projectFilesystem);

    XcodeProjectWriteOptions xcodeProjectWriteOptions =
        AppleProjectHelper.defaultXcodeProjectWriteOptions();

    HeaderSearchPaths headerSearchPaths =
        new HeaderSearchPaths(
            cells,
            projectCell,
            cxxBuckConfig,
            cxxPlatform,
            TestRuleKeyConfigurationFactory.create(),
            xcodeDescriptions,
            targetGraph,
            actionGraphBuilder,
            dependenciesCache,
            projectSourcePathResolver,
            pathRelativizer,
            swiftAttributeParser,
            appleConfig);

    FlagParser flagParser =
        new FlagParser(
            cells,
            projectCell,
            appleConfig,
            swiftBuckConfig,
            cxxBuckConfig,
            appleCxxFlavors,
            xcodeDescriptions,
            targetGraph,
            actionGraphBuilder,
            dependenciesCache,
            defaultPathResolver,
            headerSearchPaths);

    xcodeNativeTargetGenerator =
        new XcodeNativeTargetGenerator(
            xcodeDescriptions,
            targetGraph,
            dependenciesCache,
            projGenerationStateCache,
            projectCell.getFilesystem(),
            xcodeProjectWriteOptions.sourceRoot(),
            "BUCK",
            pathRelativizer,
            defaultPathResolver,
            projectSourcePathResolver,
            ProjectGeneratorOptions.builder().build(),
            CxxPlatformUtils.DEFAULT_PLATFORM,
            appleCxxFlavors,
            actionGraphBuilder,
            HalideLibraryBuilder.createDefaultHalideConfig(projectFilesystem),
            headerSearchPaths,
            cxxBuckConfig,
            appleConfig,
            swiftBuckConfig,
            swiftAttributeParser,
            flagParser,
            Optional.empty(),
            xcodeProjectWriteOptions.objectFactory());
  }

  @Test
  public void getProductType() {
    assertEquals(ProductTypes.UNIT_TEST, getProductTypeWithNode(bazTestNode));
    assertEquals(ProductTypes.STATIC_LIBRARY, getProductTypeWithNode(bazLibNode));
    assertEquals(ProductTypes.UI_TEST, getProductTypeWithNode(quxTestNode));
    assertEquals(ProductTypes.STATIC_LIBRARY, getProductTypeWithNode(quxLibNode));
    assertEquals(ProductTypes.TOOL, getProductTypeWithNode(fooAppBinaryNode));
    assertEquals(ProductTypes.TOOL, getProductTypeWithNode(barBinaryNode));
    assertEquals(ProductTypes.APP_EXTENSION, getProductTypeWithNode(barExtNode));
    assertEquals(ProductTypes.APPLICATION, getProductTypeWithNode(fooAppBundleNode));
  }

  @Test
  public void swapWithSharedBundes() {
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
  public void platformSourcesAndHeaders() {
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

  @Test
  public void appleLibraryHasHeaders() throws IOException {
    XcodeNativeTargetGenerator.Result result =
        xcodeNativeTargetGenerator.generateTarget(bazLibNode);
    assertEquals(bazLibNode, result.targetNode);
    assertEquals(bazLibTarget, result.targetAttributes.target().get());
    assertEquals(1, result.targetAttributes.privateHeaders().size());
    SourcePath header = result.targetAttributes.privateHeaders().asList().get(0);
    assertTrue(header instanceof PathSourcePath);
    assertEquals("Baz.h", ((PathSourcePath) header).getRelativePath().toString());
  }

  @Test
  public void fileGroupNodes() throws IOException {
    XcodeNativeTargetGenerator.Result result =
        xcodeNativeTargetGenerator.generateTarget(tuxFileGroupNode);
    assertEquals(1, result.targetAttributes.filegroupFiles().size());
    SourcePath fileGroupSrc = result.targetAttributes.filegroupFiles().get(0);
    assertTrue(fileGroupSrc instanceof PathSourcePath);
    assertEquals(
        "SomeFileGroupFile.txt", ((PathSourcePath) fileGroupSrc).getRelativePath().toString());
  }

  private void setupTargetGraph() {
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
    //   |
    //   V
    //   TuxFileGroup

    tuxFileGroupTarget = BuildTargetFactory.newInstance("//baz:test");
    tuxFileGroupNode =
        FilegroupBuilder.createBuilder(tuxFileGroupTarget)
            .setSrcs(ImmutableSortedSet.of(FakeSourcePath.of("SomeFileGroupFile.txt")))
            .build();

    bazTestTarget = BuildTargetFactory.newInstance("//baz:test");
    bazTestNode =
        AppleTestBuilder.createBuilder(bazTestTarget)
            .setInfoPlist(FakeSourcePath.of("Info.plist"))
            .build();

    bazLibTarget = BuildTargetFactory.newInstance("//baz:lib");
    bazLibNode =
        AppleLibraryBuilder.createBuilder(bazLibTarget)
            .setHeaders(ImmutableSortedSet.of(FakeSourcePath.of("Baz.h")))
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
            tuxFileGroupNode,
            bazTestNode,
            bazLibNode,
            quxTestNode,
            quxLibNode,
            fooAppBinaryNode,
            barBinaryNode,
            barExtNode,
            fooAppBundleNode);
  }

  public ProductType getProductTypeWithNode(TargetNode targetNode) {
    return XcodeNativeTargetGenerator.getProductType(targetNode, targetGraph).get();
  }
}
