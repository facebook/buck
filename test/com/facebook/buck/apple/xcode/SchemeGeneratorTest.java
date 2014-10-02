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

package com.facebook.buck.apple.xcode;

import static com.facebook.buck.apple.xcode.ProjectGeneratorTestUtils.createBuildRuleWithDefaults;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.core.IsNull.nullValue;
import static org.junit.Assert.assertThat;

import com.facebook.buck.apple.AppleBundleDescription;
import com.facebook.buck.apple.AppleBundleExtension;
import com.facebook.buck.apple.AppleLibraryDescription;
import com.facebook.buck.apple.AppleNativeTargetDescriptionArg;
import com.facebook.buck.apple.AppleTestDescription;
import com.facebook.buck.apple.SchemeActionType;
import com.facebook.buck.apple.XcodeNativeDescription;
import com.facebook.buck.apple.xcode.xcodeproj.PBXFileReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXNativeTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.cxx.Archives;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.rules.coercer.AppleSource;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.timing.SettableFakeClock;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;

import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;

public class SchemeGeneratorTest {

  private SettableFakeClock clock;
  private ProjectFilesystem projectFilesystem;
  private AppleLibraryDescription appleLibraryDescription;
  private AppleBundleDescription appleBundleDescription;
  private AppleTestDescription appleTestDescription;
  private XcodeNativeDescription xcodeNativeDescription;

  @Before
  public void setUp() throws IOException {
    clock = new SettableFakeClock(0, 0);
    projectFilesystem = new FakeProjectFilesystem(clock);
    appleLibraryDescription = new AppleLibraryDescription(Archives.DEFAULT_ARCHIVE_PATH);
    appleBundleDescription = new AppleBundleDescription();
    appleTestDescription = new AppleTestDescription();
    xcodeNativeDescription = new XcodeNativeDescription();
  }

  @Test
  public void schemeWithMultipleTargetsBuildsInCorrectOrder() throws Exception {
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildRule rootRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "root").build(),
        ImmutableSortedSet.<BuildRule>of(),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(rootRule);

    BuildRule leftRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "left").build(),
        ImmutableSortedSet.of(rootRule),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(leftRule);

    BuildRule rightRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "right").build(),
        ImmutableSortedSet.of(rootRule),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(rightRule);

    BuildRule childRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "child").build(),
        ImmutableSortedSet.of(leftRule, rightRule),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(childRule);

    ImmutableMap.Builder<BuildRule, PBXTarget> buildRuleToTargetMapBuilder =
      ImmutableMap.builder();
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder =
      ImmutableMap.builder();

    PBXTarget rootTarget = new PBXNativeTarget("rootRule", PBXTarget.ProductType.STATIC_LIBRARY);
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductReference(
        new PBXFileReference(
            "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    buildRuleToTargetMapBuilder.put(rootRule, rootTarget);
    PBXTarget leftTarget = new PBXNativeTarget("leftRule", PBXTarget.ProductType.STATIC_LIBRARY);
    leftTarget.setGlobalID("leftGID");
    leftTarget.setProductReference(
        new PBXFileReference(
            "left.a", "left.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    buildRuleToTargetMapBuilder.put(leftRule, leftTarget);
    PBXTarget rightTarget = new PBXNativeTarget("rightRule", PBXTarget.ProductType.STATIC_LIBRARY);
    rightTarget.setGlobalID("rightGID");
    rightTarget.setProductReference(
        new PBXFileReference(
            "right.a", "right.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    buildRuleToTargetMapBuilder.put(rightRule, rightTarget);
    PBXTarget childTarget = new PBXNativeTarget("childRule", PBXTarget.ProductType.STATIC_LIBRARY);
    childTarget.setGlobalID("childGID");
    childTarget.setProductReference(
        new PBXFileReference(
            "child.a", "child.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    buildRuleToTargetMapBuilder.put(childRule, childTarget);

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(rootTarget, pbxprojectPath);
    targetToProjectPathMapBuilder.put(leftTarget, pbxprojectPath);
    targetToProjectPathMapBuilder.put(rightTarget, pbxprojectPath);
    targetToProjectPathMapBuilder.put(childTarget, pbxprojectPath);

    SchemeGenerator schemeGenerator = new SchemeGenerator(
        projectFilesystem,
        Optional.of(childRule),
        ImmutableSet.of(rootRule, leftRule, rightRule, childRule),
        ImmutableSet.<BuildRule>of(),
        ImmutableSet.<BuildRule>of(),
        "TestScheme",
        Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
        SchemeActionType.DEFAULT_CONFIG_NAMES,
        buildRuleToTargetMapBuilder.build(),
        targetToProjectPathMapBuilder.build());

    Path schemePath = schemeGenerator.writeScheme();
    String schemeXml = projectFilesystem.readFileIfItExists(schemePath).get();
    System.out.println(schemeXml);

    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
    Document scheme = dBuilder.parse(projectFilesystem.newFileInputStream(schemePath));

    XPathFactory xpathFactory = XPathFactory.newInstance();
    XPath xpath = xpathFactory.newXPath();
    XPathExpression expr =
        xpath.compile("//BuildAction//BuildableReference/@BlueprintIdentifier");
    NodeList nodes = (NodeList) expr.evaluate(scheme, XPathConstants.NODESET);

    List<String> expectedOrdering = ImmutableList.of(
        "rootGID",
        "leftGID",
        "rightGID",
        "childGID");

    List<String> actualOrdering = Lists.newArrayList();
    for (int i = 0; i < nodes.getLength(); i++) {
      actualOrdering.add(nodes.item(i).getNodeValue());
    }
    assertThat(actualOrdering, equalTo(expectedOrdering));
  }

  @Test(expected = HumanReadableException.class)
  public void schemeWithTargetWithoutCorrespondingProjectsFails() throws Exception {
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildRule rootRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "root").build(),
        ImmutableSortedSet.<BuildRule>of(),
        appleLibraryDescription,
        resolver);

    SchemeGenerator schemeGenerator = new SchemeGenerator(
        projectFilesystem,
        Optional.of(rootRule),
        ImmutableSet.<BuildRule>of(rootRule),
        ImmutableSet.<BuildRule>of(),
        ImmutableSet.<BuildRule>of(),
        "TestScheme",
        Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
        SchemeActionType.DEFAULT_CONFIG_NAMES,
        ImmutableMap.<BuildRule, PBXTarget>of(),
        ImmutableMap.<PBXTarget, Path>of());

    schemeGenerator.writeScheme();
  }

  @Test
  public void schemeIncludesXcodeNativeTargets() throws Exception {
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildRule xcodeNativeRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "xcode-native").build(),
        ImmutableSortedSet.<BuildRule>of(),
        xcodeNativeDescription,
        resolver);
    resolver.addToIndex(xcodeNativeRule);

    BuildRule rootRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "root").build(),
        ImmutableSortedSet.of(xcodeNativeRule),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(rootRule);

    ImmutableMap.Builder<BuildRule, PBXTarget> buildRuleToTargetMapBuilder =
      ImmutableMap.builder();
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder =
      ImmutableMap.builder();

    PBXTarget rootTarget = new PBXNativeTarget("root", PBXTarget.ProductType.STATIC_LIBRARY);
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductReference(
        new PBXFileReference(
            "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    buildRuleToTargetMapBuilder.put(rootRule, rootTarget);
    PBXTarget xcodeNativeTarget =
        new PBXNativeTarget("xcode-native", PBXTarget.ProductType.STATIC_LIBRARY);
    xcodeNativeTarget.setGlobalID("xcode-nativeGID");
    xcodeNativeTarget.setProductReference(
        new PBXFileReference(
            "xcode-native.a", "xcode-native.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    buildRuleToTargetMapBuilder.put(xcodeNativeRule, xcodeNativeTarget);

    Path projectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(rootTarget, projectPath);

    Path nativeProjectPath = Paths.get("foo/XcodeNative.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(xcodeNativeTarget, nativeProjectPath);

    SchemeGenerator schemeGenerator = new SchemeGenerator(
        projectFilesystem,
        Optional.of(rootRule),
        ImmutableSet.<BuildRule>of(xcodeNativeRule, rootRule),
        ImmutableSet.<BuildRule>of(),
        ImmutableSet.<BuildRule>of(),
        "TestScheme",
        Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
        SchemeActionType.DEFAULT_CONFIG_NAMES,
        buildRuleToTargetMapBuilder.build(),
        targetToProjectPathMapBuilder.build());

    Path schemePath = schemeGenerator.writeScheme();
    String schemeXml = projectFilesystem.readFileIfItExists(schemePath).get();
    System.out.println(schemeXml);

    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
    Document scheme = dBuilder.parse(projectFilesystem.newFileInputStream(schemePath));

    XPathFactory xpathFactory = XPathFactory.newInstance();
    XPath xpath = xpathFactory.newXPath();
    XPathExpression expr =
        xpath.compile("//BuildAction//BuildableReference/@BlueprintIdentifier");
    NodeList nodes = (NodeList) expr.evaluate(scheme, XPathConstants.NODESET);

    List<String> expectedOrdering = ImmutableList.of(
        "xcode-nativeGID",
        "rootGID");

    List<String> actualOrdering = Lists.newArrayList();
    for (int i = 0; i < nodes.getLength(); i++) {
      actualOrdering.add(nodes.item(i).getNodeValue());
    }
    assertThat(actualOrdering, equalTo(expectedOrdering));
  }

  @Test
  public void schemeBuildsAndTestsAppleTestTargets() throws Exception {
    BuildRuleResolver resolver = new BuildRuleResolver();
    BuildRuleParams testDepParams =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "testDep").build())
            .setType(AppleLibraryDescription.TYPE)
            .build();
    AppleNativeTargetDescriptionArg testDepArg =
        appleLibraryDescription.createUnpopulatedConstructorArg();
    testDepArg.configs = Optional.of(
        ImmutableMap.<String, ImmutableList<Either<SourcePath, ImmutableMap<String, String>>>>of());
    testDepArg.srcs = Optional.of(ImmutableList.<AppleSource>of());
    testDepArg.frameworks = Optional.of(ImmutableSortedSet.<String>of());
    testDepArg.weakFrameworks = Optional.of(ImmutableSortedSet.<String>of());
    testDepArg.deps = Optional.absent();
    testDepArg.gid = Optional.absent();
    testDepArg.headerPathPrefix = Optional.absent();
    testDepArg.useBuckHeaderMaps = Optional.absent();
    BuildRule testDepRule =
        appleLibraryDescription.createBuildRule(testDepParams, resolver, testDepArg);
    resolver.addToIndex(testDepRule);

    BuildRuleParams libraryParams =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "lib").build())
            .setType(AppleLibraryDescription.TYPE)
            .build();
    AppleNativeTargetDescriptionArg libraryArg =
        appleLibraryDescription.createUnpopulatedConstructorArg();
    libraryArg.configs = Optional.of(
        ImmutableMap.<String, ImmutableList<Either<SourcePath, ImmutableMap<String, String>>>>of());
    libraryArg.srcs = Optional.of(ImmutableList.<AppleSource>of());
    libraryArg.frameworks = Optional.of(ImmutableSortedSet.<String>of());
    libraryArg.weakFrameworks = Optional.of(ImmutableSortedSet.<String>of());
    libraryArg.deps = Optional.of(ImmutableSortedSet.of(testDepRule.getBuildTarget()));
    libraryArg.gid = Optional.absent();
    libraryArg.headerPathPrefix = Optional.absent();
    libraryArg.useBuckHeaderMaps = Optional.absent();
    BuildRule libraryRule =
        appleLibraryDescription.createBuildRule(libraryParams, resolver, libraryArg);
    resolver.addToIndex(libraryRule);

    BuildRuleParams xctestParams =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "xctest").build())
            .setDeps(ImmutableSortedSet.of(libraryRule))
            .setType(AppleBundleDescription.TYPE)
            .build();

    AppleBundleDescription.Arg xctestArg =
        appleBundleDescription.createUnpopulatedConstructorArg();
    xctestArg.infoPlist = Optional.<SourcePath>of(new TestSourcePath("Info.plist"));
    xctestArg.binary = libraryRule.getBuildTarget();
    xctestArg.extension = Either.ofLeft(AppleBundleExtension.XCTEST);
    xctestArg.deps = Optional.absent();

    BuildRule xctestRule =
        appleBundleDescription.createBuildRule(xctestParams, resolver, xctestArg);
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

    BuildRule testRule = appleTestDescription.createBuildRule(params, resolver, arg);
    resolver.addToIndex(testRule);

    BuildRule rootRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "root").build(),
        ImmutableSortedSet.<BuildRule>of(),
        appleLibraryDescription,
        resolver);

    ImmutableMap.Builder<BuildRule, PBXTarget> buildRuleToTargetMapBuilder =
        ImmutableMap.builder();
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder =
        ImmutableMap.builder();

    PBXTarget testDepTarget = new PBXNativeTarget("testDep", PBXTarget.ProductType.STATIC_LIBRARY);
    testDepTarget.setGlobalID("testDepGID");
    testDepTarget.setProductReference(
        new PBXFileReference(
            "libDep.a", "libDep.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    buildRuleToTargetMapBuilder.put(testDepRule, testDepTarget);

    PBXTarget testLibraryTarget =
        new PBXNativeTarget("testLibrary", PBXTarget.ProductType.STATIC_LIBRARY);
    testLibraryTarget.setGlobalID("testLibraryGID");
    testLibraryTarget.setProductReference(
        new PBXFileReference(
            "lib.a", "lib.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    buildRuleToTargetMapBuilder.put(libraryRule, testLibraryTarget);

    PBXTarget testTarget = new PBXNativeTarget("test", PBXTarget.ProductType.UNIT_TEST);
    testTarget.setGlobalID("testGID");
    testTarget.setProductReference(
        new PBXFileReference(
            "test.xctest", "test.xctest", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    buildRuleToTargetMapBuilder.put(testRule, testTarget);

    PBXTarget rootTarget = new PBXNativeTarget("root", PBXTarget.ProductType.STATIC_LIBRARY);
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductReference(
        new PBXFileReference(
            "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    buildRuleToTargetMapBuilder.put(rootRule, rootTarget);

    Path projectPath = Paths.get("foo/test.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(testTarget, projectPath);
    targetToProjectPathMapBuilder.put(testDepTarget, projectPath);
    targetToProjectPathMapBuilder.put(testLibraryTarget, projectPath);
    targetToProjectPathMapBuilder.put(rootTarget, projectPath);

    SchemeGenerator schemeGenerator = new SchemeGenerator(
        projectFilesystem,
        Optional.of(rootRule),
        ImmutableSet.of(rootRule),
        ImmutableSet.of(testDepRule, testRule),
        ImmutableSet.of(testRule),
        "TestScheme",
        Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
        SchemeActionType.DEFAULT_CONFIG_NAMES,
        buildRuleToTargetMapBuilder.build(),
        targetToProjectPathMapBuilder.build());

    Path schemePath = schemeGenerator.writeScheme();
    String schemeXml = projectFilesystem.readFileIfItExists(schemePath).get();
    System.out.println(schemeXml);

    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
    Document scheme = dBuilder.parse(projectFilesystem.newFileInputStream(schemePath));

    XPathFactory xpathFactory = XPathFactory.newInstance();

    XPath buildXpath = xpathFactory.newXPath();
    XPathExpression buildExpr =
        buildXpath.compile("//BuildAction//BuildableReference/@BlueprintIdentifier");
    NodeList buildNodes = (NodeList) buildExpr.evaluate(scheme, XPathConstants.NODESET);

    List<String> expectedBuildOrdering = ImmutableList.of("rootGID", "testDepGID", "testGID");

    List<String> actualBuildOrdering = Lists.newArrayList();
    for (int i = 0; i < buildNodes.getLength(); i++) {
      actualBuildOrdering.add(buildNodes.item(i).getNodeValue());
    }
    assertThat(actualBuildOrdering, equalTo(expectedBuildOrdering));

    XPath textXpath = xpathFactory.newXPath();
    XPathExpression testExpr = textXpath.compile(
      "//TestAction//TestableReference/BuildableReference/@BlueprintIdentifier");
    NodeList testNodes = (NodeList) testExpr.evaluate(scheme, XPathConstants.NODESET);

    List<String> expectedTestOrdering = ImmutableList.of("testGID");

    List<String> actualTestOrdering = Lists.newArrayList();
    for (int i = 0; i < testNodes.getLength(); i++) {
      actualTestOrdering.add(testNodes.item(i).getNodeValue());
    }
    assertThat(actualTestOrdering, equalTo(expectedTestOrdering));
  }

  @Test
  public void schemeIncludesAllExpectedActions() throws Exception {
    BuildRuleResolver resolver = new BuildRuleResolver();
    BuildRule rootRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "root").build(),
        ImmutableSortedSet.<BuildRule>of(),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(rootRule);

    BuildRuleParams libraryParams =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "lib").build())
            .setType(AppleLibraryDescription.TYPE)
            .build();
    AppleNativeTargetDescriptionArg libraryArg =
        appleLibraryDescription.createUnpopulatedConstructorArg();
    libraryArg.configs = Optional.of(
        ImmutableMap.<String, ImmutableList<Either<SourcePath, ImmutableMap<String, String>>>>of());
    libraryArg.srcs = Optional.of(ImmutableList.<AppleSource>of());
    libraryArg.frameworks = Optional.of(ImmutableSortedSet.<String>of());
    libraryArg.weakFrameworks = Optional.of(ImmutableSortedSet.<String>of());
    libraryArg.deps = Optional.absent();
    libraryArg.gid = Optional.absent();
    libraryArg.headerPathPrefix = Optional.absent();
    libraryArg.useBuckHeaderMaps = Optional.absent();
    BuildRule libraryRule =
        appleLibraryDescription.createBuildRule(libraryParams, resolver, libraryArg);
    resolver.addToIndex(libraryRule);

    BuildRuleParams xctestParams =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "xctest").build())
            .setDeps(ImmutableSortedSet.of(libraryRule))
            .setType(AppleBundleDescription.TYPE)
            .build();

    AppleBundleDescription.Arg xctestArg =
        appleBundleDescription.createUnpopulatedConstructorArg();
    xctestArg.infoPlist = Optional.<SourcePath>of(new TestSourcePath("Info.plist"));
    xctestArg.binary = libraryRule.getBuildTarget();
    xctestArg.extension = Either.ofLeft(AppleBundleExtension.XCTEST);
    xctestArg.deps = Optional.absent();

    BuildRule xctestRule =
        appleBundleDescription.createBuildRule(xctestParams, resolver, xctestArg);
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
    arg.sourceUnderTest = Optional.of(ImmutableSortedSet.of(rootRule.getBuildTarget()));

    BuildRule testRule = appleTestDescription.createBuildRule(params, resolver, arg);
    resolver.addToIndex(testRule);

    ImmutableMap.Builder<BuildRule, PBXTarget> buildRuleToTargetMapBuilder =
      ImmutableMap.builder();
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder =
      ImmutableMap.builder();

    PBXTarget rootTarget = new PBXNativeTarget("rootRule", PBXTarget.ProductType.STATIC_LIBRARY);
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductReference(
        new PBXFileReference(
            "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    buildRuleToTargetMapBuilder.put(rootRule, rootTarget);
    PBXTarget testTarget = new PBXNativeTarget("testRule", PBXTarget.ProductType.STATIC_LIBRARY);
    testTarget.setGlobalID("testGID");
    testTarget.setProductReference(
        new PBXFileReference(
            "test.a", "test.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    buildRuleToTargetMapBuilder.put(testRule, testTarget);
    PBXTarget testBundleTarget =
        new PBXNativeTarget("testBundleRule", PBXTarget.ProductType.UNIT_TEST);
    testBundleTarget.setGlobalID("testBundleGID");
    testBundleTarget.setProductReference(
        new PBXFileReference(
            "test.xctest",
            "test.xctest",
            PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    buildRuleToTargetMapBuilder.put(xctestRule, testBundleTarget);

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(rootTarget, pbxprojectPath);
    targetToProjectPathMapBuilder.put(testTarget, pbxprojectPath);
    targetToProjectPathMapBuilder.put(testBundleTarget, pbxprojectPath);

    SchemeGenerator schemeGenerator = new SchemeGenerator(
        projectFilesystem,
        Optional.of(rootRule),
        ImmutableSet.of(rootRule),
        ImmutableSet.of(xctestRule),
        ImmutableSet.of(xctestRule),
        "TestScheme",
        Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
        SchemeActionType.DEFAULT_CONFIG_NAMES,
        buildRuleToTargetMapBuilder.build(),
        targetToProjectPathMapBuilder.build());

    Path schemePath = schemeGenerator.writeScheme();
    String schemeXml = projectFilesystem.readFileIfItExists(schemePath).get();
    System.out.println(schemeXml);

    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
    Document scheme = dBuilder.parse(projectFilesystem.newFileInputStream(schemePath));

    XPathFactory xpathFactory = XPathFactory.newInstance();

    XPath buildActionXpath = xpathFactory.newXPath();
    XPathExpression buildActionExpr =
        buildActionXpath.compile("//BuildAction//BuildableReference/@BlueprintIdentifier");
    NodeList buildActionNodes = (NodeList) buildActionExpr.evaluate(scheme, XPathConstants.NODESET);

    List<String> expectedOrdering = ImmutableList.of(
        "rootGID",
        "testBundleGID");

    List<String> actualOrdering = Lists.newArrayList();
    for (int i = 0; i < buildActionNodes.getLength(); i++) {
      actualOrdering.add(buildActionNodes.item(i).getNodeValue());
    }
    assertThat(actualOrdering, equalTo(expectedOrdering));

    XPath testActionXpath = xpathFactory.newXPath();
    XPathExpression testActionExpr =
        testActionXpath.compile("//TestAction//BuildableReference/@BlueprintIdentifier");
    String testActionBlueprintIdentifier =
        (String) testActionExpr.evaluate(scheme, XPathConstants.STRING);
    assertThat(testActionBlueprintIdentifier, equalTo("testBundleGID"));

    XPath launchActionXpath = xpathFactory.newXPath();
    XPathExpression launchActionExpr =
        launchActionXpath.compile("//LaunchAction//BuildableReference/@BlueprintIdentifier");
    String launchActionBlueprintIdentifier =
        (String) launchActionExpr.evaluate(scheme, XPathConstants.STRING);
    assertThat(launchActionBlueprintIdentifier, equalTo("rootGID"));

    XPath profileActionXpath = xpathFactory.newXPath();
    XPathExpression profileActionExpr =
        profileActionXpath.compile("//ProfileAction//BuildableReference/@BlueprintIdentifier");
    String profileActionBlueprintIdentifier =
        (String) profileActionExpr.evaluate(scheme, XPathConstants.STRING);
    assertThat(profileActionBlueprintIdentifier, equalTo("rootGID"));
  }

  @Test
  public void buildableReferenceShouldHaveExpectedProperties() throws Exception {
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildRule rootRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "root").build(),
        ImmutableSortedSet.<BuildRule>of(),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(rootRule);

    ImmutableMap.Builder<BuildRule, PBXTarget> buildRuleToTargetMapBuilder =
      ImmutableMap.builder();
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder =
      ImmutableMap.builder();

    PBXTarget rootTarget = new PBXNativeTarget("rootRule", PBXTarget.ProductType.STATIC_LIBRARY);
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductReference(
        new PBXFileReference(
            "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    buildRuleToTargetMapBuilder.put(rootRule, rootTarget);

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(rootTarget, pbxprojectPath);

    SchemeGenerator schemeGenerator = new SchemeGenerator(
        projectFilesystem,
        Optional.of(rootRule),
        ImmutableSet.of(rootRule),
        ImmutableSet.<BuildRule>of(),
        ImmutableSet.<BuildRule>of(),
        "TestScheme",
        Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
        SchemeActionType.DEFAULT_CONFIG_NAMES,
        buildRuleToTargetMapBuilder.build(),
        targetToProjectPathMapBuilder.build());

    Path schemePath = schemeGenerator.writeScheme();

    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
    Document scheme = dBuilder.parse(projectFilesystem.newFileInputStream(schemePath));

    XPathFactory xpathFactory = XPathFactory.newInstance();

    XPath buildableReferenceXPath = xpathFactory.newXPath();
    XPathExpression buildableReferenceExpr =
        buildableReferenceXPath.compile("//BuildableReference");
    NodeList buildableReferences = (NodeList) buildableReferenceExpr.evaluate(
        scheme, XPathConstants.NODESET);

    assertThat(buildableReferences.getLength(), greaterThan(0));

    for (int i = 0; i < buildableReferences.getLength(); i++) {
      NamedNodeMap attributes = buildableReferences.item(i).getAttributes();
      assertThat(attributes, notNullValue());
      assertThat(attributes.getNamedItem("BlueprintIdentifier"), notNullValue());
      assertThat(attributes.getNamedItem("BuildableIdentifier"), notNullValue());
      assertThat(attributes.getNamedItem("ReferencedContainer"), notNullValue());
      assertThat(attributes.getNamedItem("BlueprintName"), notNullValue());
      assertThat(attributes.getNamedItem("BuildableName"), notNullValue());
    }
  }

  @Test
  public void allActionsShouldBePresentInSchemeWithDefaultBuildConfigurations() throws Exception {
    BuildRuleResolver resolver = new BuildRuleResolver();

    BuildRule rootRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "root").build(),
        ImmutableSortedSet.<BuildRule>of(),
        appleLibraryDescription,
        resolver);
    resolver.addToIndex(rootRule);

    ImmutableMap.Builder<BuildRule, PBXTarget> buildRuleToTargetMapBuilder =
      ImmutableMap.builder();
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder =
      ImmutableMap.builder();

    PBXTarget rootTarget = new PBXNativeTarget("rootRule", PBXTarget.ProductType.STATIC_LIBRARY);
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductReference(
        new PBXFileReference(
            "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    buildRuleToTargetMapBuilder.put(rootRule, rootTarget);

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(rootTarget, pbxprojectPath);

    SchemeGenerator schemeGenerator = new SchemeGenerator(
        projectFilesystem,
        Optional.of(rootRule),
        ImmutableSet.of(rootRule),
        ImmutableSet.<BuildRule>of(),
        ImmutableSet.<BuildRule>of(),
        "TestScheme",
        Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
        SchemeActionType.DEFAULT_CONFIG_NAMES,
        buildRuleToTargetMapBuilder.build(),
        targetToProjectPathMapBuilder.build());

    Path schemePath = schemeGenerator.writeScheme();

    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
    Document scheme = dBuilder.parse(projectFilesystem.newFileInputStream(schemePath));

    XPathFactory xpathFactory = XPathFactory.newInstance();

    XPath schemeChildrenXPath = xpathFactory.newXPath();
    XPathExpression schemeChildrenExpr =
        schemeChildrenXPath.compile("/Scheme/node()");
    NodeList actions = (NodeList) schemeChildrenExpr.evaluate(scheme, XPathConstants.NODESET);

    assertThat(actions.getLength(), equalTo(6));

    Node buildAction = actions.item(0);
    assertThat(buildAction.getNodeName(), equalTo("BuildAction"));
    assertThat(
        buildAction.getAttributes().getNamedItem("buildConfiguration"),
        nullValue());

    Node testAction = actions.item(1);
    assertThat(testAction.getNodeName(), equalTo("TestAction"));
    assertThat(
        testAction.getAttributes().getNamedItem("buildConfiguration").getNodeValue(),
        equalTo("Debug"));

    Node launchAction = actions.item(2);
    assertThat(launchAction.getNodeName(), equalTo("LaunchAction"));
    assertThat(
        launchAction.getAttributes().getNamedItem("buildConfiguration").getNodeValue(),
        equalTo("Debug"));

    Node profileAction = actions.item(3);
    assertThat(profileAction.getNodeName(), equalTo("ProfileAction"));
    assertThat(
        profileAction.getAttributes().getNamedItem("buildConfiguration").getNodeValue(),
        equalTo("Release"));

    Node analyzeAction = actions.item(4);
    assertThat(analyzeAction.getNodeName(), equalTo("AnalyzeAction"));
    assertThat(
        analyzeAction.getAttributes().getNamedItem("buildConfiguration").getNodeValue(),
        equalTo("Debug"));

    Node archiveAction = actions.item(5);
    assertThat(archiveAction.getNodeName(), equalTo("ArchiveAction"));
    assertThat(
        archiveAction.getAttributes().getNamedItem("buildConfiguration").getNodeValue(),
        equalTo("Release"));
  }

  @Test
  public void schemeIsRewrittenIfContentsHaveChanged() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();

    {
      BuildRule rootRule = createBuildRuleWithDefaults(
          BuildTarget.builder("//foo", "root").build(),
          ImmutableSortedSet.<BuildRule>of(),
          appleLibraryDescription, resolver);
      resolver.addToIndex(rootRule);

      ImmutableMap.Builder<BuildRule, PBXTarget> buildRuleToTargetMapBuilder =
        ImmutableMap.builder();
      ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder =
        ImmutableMap.builder();

      PBXTarget rootTarget = new PBXNativeTarget("rootRule", PBXTarget.ProductType.STATIC_LIBRARY);
      rootTarget.setGlobalID("rootGID");
      rootTarget.setProductReference(
          new PBXFileReference(
              "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
      buildRuleToTargetMapBuilder.put(rootRule, rootTarget);

      Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
      targetToProjectPathMapBuilder.put(rootTarget, pbxprojectPath);

      clock.setCurrentTimeMillis(49152);
      SchemeGenerator schemeGenerator = new SchemeGenerator(
          projectFilesystem,
          Optional.of(rootRule),
          ImmutableSet.of(rootRule),
          ImmutableSet.<BuildRule>of(),
          ImmutableSet.<BuildRule>of(),
          "TestScheme",
          Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
          SchemeActionType.DEFAULT_CONFIG_NAMES,
          buildRuleToTargetMapBuilder.build(),
          targetToProjectPathMapBuilder.build());

      Path schemePath = schemeGenerator.writeScheme();
      assertThat(projectFilesystem.getLastModifiedTime(schemePath), equalTo(49152L));
    }

    {
      BuildRule rootRule = createBuildRuleWithDefaults(
          BuildTarget.builder("//foo", "root2").build(),
          ImmutableSortedSet.<BuildRule>of(),
          appleLibraryDescription,
          resolver);
      resolver.addToIndex(rootRule);

      PBXTarget rootTarget = new PBXNativeTarget("rootRule2", PBXTarget.ProductType.STATIC_LIBRARY);
      rootTarget.setGlobalID("root2GID");
      rootTarget.setProductReference(
          new PBXFileReference(
              "root2.a", "root2.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));

      Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");

      clock.setCurrentTimeMillis(64738);
      SchemeGenerator schemeGenerator = new SchemeGenerator(
          projectFilesystem,
          Optional.of(rootRule),
          ImmutableSet.of(rootRule),
          ImmutableSet.<BuildRule>of(),
          ImmutableSet.<BuildRule>of(),
          "TestScheme",
          Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
          SchemeActionType.DEFAULT_CONFIG_NAMES,
          ImmutableMap.of(rootRule, rootTarget),
          ImmutableMap.of(rootTarget, pbxprojectPath));

      Path schemePath = schemeGenerator.writeScheme();
      assertThat(projectFilesystem.getLastModifiedTime(schemePath), equalTo(64738L));
    }
  }

  @Test
  public void schemeIsNotRewrittenIfContentsHaveNotChanged() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();

    {
      BuildRule rootRule = createBuildRuleWithDefaults(
          BuildTarget.builder("//foo", "root1").build(),
          ImmutableSortedSet.<BuildRule>of(),
          appleLibraryDescription,
          resolver);
      resolver.addToIndex(rootRule);

      PBXTarget rootTarget = new PBXNativeTarget("rootRule", PBXTarget.ProductType.STATIC_LIBRARY);
      rootTarget.setGlobalID("rootGID");
      rootTarget.setProductReference(
          new PBXFileReference(
              "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));

      Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");

      clock.setCurrentTimeMillis(49152);
      SchemeGenerator schemeGenerator = new SchemeGenerator(
          projectFilesystem,
          Optional.of(rootRule),
          ImmutableSet.of(rootRule),
          ImmutableSet.<BuildRule>of(),
          ImmutableSet.<BuildRule>of(),
          "TestScheme",
          Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
          SchemeActionType.DEFAULT_CONFIG_NAMES,
          ImmutableMap.of(rootRule, rootTarget),
          ImmutableMap.of(rootTarget, pbxprojectPath));

      Path schemePath = schemeGenerator.writeScheme();
      assertThat(projectFilesystem.getLastModifiedTime(schemePath), equalTo(49152L));
    }

    {
      BuildRule rootRule = createBuildRuleWithDefaults(
          BuildTarget.builder("//foo", "root2").build(),
          ImmutableSortedSet.<BuildRule>of(),
          appleLibraryDescription,
          resolver);
      resolver.addToIndex(rootRule);

      PBXTarget rootTarget = new PBXNativeTarget("rootRule", PBXTarget.ProductType.STATIC_LIBRARY);
      rootTarget.setGlobalID("rootGID");
      rootTarget.setProductReference(
          new PBXFileReference(
              "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));

      Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");

      clock.setCurrentTimeMillis(64738);
      SchemeGenerator schemeGenerator = new SchemeGenerator(
          projectFilesystem,
          Optional.of(rootRule),
          ImmutableSet.of(rootRule),
          ImmutableSet.<BuildRule>of(),
          ImmutableSet.<BuildRule>of(),
          "TestScheme",
          Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
          SchemeActionType.DEFAULT_CONFIG_NAMES,
          ImmutableMap.of(rootRule, rootTarget),
          ImmutableMap.of(rootTarget, pbxprojectPath));
      Path schemePath = schemeGenerator.writeScheme();
      assertThat(projectFilesystem.getLastModifiedTime(schemePath), equalTo(49152L));
    }
  }

  @Test
  public void schemeWithNoPrimaryRuleCanIncludeTests() throws Exception{
    BuildRuleResolver resolver = new BuildRuleResolver();
    BuildRuleParams libraryParams =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "lib").build())
            .setType(AppleLibraryDescription.TYPE)
            .build();
    AppleNativeTargetDescriptionArg libraryArg =
        appleLibraryDescription.createUnpopulatedConstructorArg();
    libraryArg.configs = Optional.of(
        ImmutableMap.<String, ImmutableList<Either<SourcePath, ImmutableMap<String, String>>>>of());
    libraryArg.srcs = Optional.of(ImmutableList.<AppleSource>of());
    libraryArg.frameworks = Optional.of(ImmutableSortedSet.<String>of());
    libraryArg.weakFrameworks = Optional.of(ImmutableSortedSet.<String>of());
    libraryArg.deps = Optional.absent();
    libraryArg.gid = Optional.absent();
    libraryArg.headerPathPrefix = Optional.absent();
    libraryArg.useBuckHeaderMaps = Optional.absent();
    BuildRule libraryRule =
        appleLibraryDescription.createBuildRule(libraryParams, resolver, libraryArg);
    resolver.addToIndex(libraryRule);

    BuildRuleParams xctestParams =
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//foo", "xctest").build())
            .setDeps(ImmutableSortedSet.of(libraryRule))
            .setType(AppleBundleDescription.TYPE)
            .build();

    AppleBundleDescription.Arg xctestArg =
        appleBundleDescription.createUnpopulatedConstructorArg();
    xctestArg.infoPlist = Optional.<SourcePath>of(new TestSourcePath("Info.plist"));
    xctestArg.binary = libraryRule.getBuildTarget();
    xctestArg.extension = Either.ofLeft(AppleBundleExtension.XCTEST);
    xctestArg.deps = Optional.absent();

    BuildRule xctestRule =
        appleBundleDescription.createBuildRule(xctestParams, resolver, xctestArg);
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
    arg.sourceUnderTest = Optional.of(ImmutableSortedSet.of(libraryRule.getBuildTarget()));

    BuildRule testRule = appleTestDescription.createBuildRule(params, resolver, arg);
    resolver.addToIndex(testRule);

    ImmutableMap.Builder<BuildRule, PBXTarget> buildRuleToTargetMapBuilder =
      ImmutableMap.builder();
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder =
      ImmutableMap.builder();

    PBXTarget testLibraryTarget =
        new PBXNativeTarget("testLibrary", PBXTarget.ProductType.STATIC_LIBRARY);
    testLibraryTarget.setGlobalID("testLibraryGID");
    testLibraryTarget.setProductReference(
        new PBXFileReference(
            "lib.a", "lib.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    buildRuleToTargetMapBuilder.put(libraryRule, testLibraryTarget);
    PBXTarget testTarget = new PBXNativeTarget("testRule", PBXTarget.ProductType.STATIC_LIBRARY);
    testTarget.setGlobalID("testGID");
    testTarget.setProductReference(
        new PBXFileReference(
            "test.a", "test.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    buildRuleToTargetMapBuilder.put(testRule, testTarget);
    PBXTarget testBundleTarget =
        new PBXNativeTarget("testBundleRule", PBXTarget.ProductType.UNIT_TEST);
    testBundleTarget.setGlobalID("testBundleGID");
    testBundleTarget.setProductReference(
        new PBXFileReference(
            "test.xctest",
            "test.xctest",
            PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    buildRuleToTargetMapBuilder.put(xctestRule, testBundleTarget);

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(testLibraryTarget, pbxprojectPath);
    targetToProjectPathMapBuilder.put(testTarget, pbxprojectPath);
    targetToProjectPathMapBuilder.put(testBundleTarget, pbxprojectPath);

    SchemeGenerator schemeGenerator = new SchemeGenerator(
        projectFilesystem,
        Optional.<BuildRule>absent(),
        ImmutableSet.<BuildRule>of(),
        ImmutableSet.of(xctestRule),
        ImmutableSet.of(xctestRule),
        "TestScheme",
        Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
        SchemeActionType.DEFAULT_CONFIG_NAMES,
        buildRuleToTargetMapBuilder.build(),
        targetToProjectPathMapBuilder.build());

    Path schemePath = schemeGenerator.writeScheme();
    String schemeXml = projectFilesystem.readFileIfItExists(schemePath).get();
    System.out.println(schemeXml);

    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
    Document scheme = dBuilder.parse(projectFilesystem.newFileInputStream(schemePath));

    XPathFactory xpathFactory = XPathFactory.newInstance();

    XPath buildActionXpath = xpathFactory.newXPath();
    XPathExpression buildActionExpr =
        buildActionXpath.compile("//BuildAction//BuildableReference/@BlueprintIdentifier");
    NodeList buildActionNodes = (NodeList) buildActionExpr.evaluate(scheme, XPathConstants.NODESET);

    List<String> expectedOrdering = ImmutableList.of(
        "testBundleGID");

    List<String> actualOrdering = Lists.newArrayList();
    for (int i = 0; i < buildActionNodes.getLength(); i++) {
      actualOrdering.add(buildActionNodes.item(i).getNodeValue());
    }
    assertThat(actualOrdering, equalTo(expectedOrdering));

    XPath testActionXpath = xpathFactory.newXPath();
    XPathExpression testActionExpr =
        testActionXpath.compile("//TestAction//BuildableReference/@BlueprintIdentifier");
    String testActionBlueprintIdentifier =
        (String) testActionExpr.evaluate(scheme, XPathConstants.STRING);
    assertThat(testActionBlueprintIdentifier, equalTo("testBundleGID"));

    XPath launchActionXpath = xpathFactory.newXPath();
    XPathExpression launchActionExpr =
        launchActionXpath.compile("//LaunchAction//BuildableReference/@BlueprintIdentifier");
    String launchActionBlueprintIdentifier =
        (String) launchActionExpr.evaluate(scheme, XPathConstants.STRING);
    assertThat(launchActionBlueprintIdentifier, equalTo(""));

    XPath launchActionBuildConfigurationXpath = xpathFactory.newXPath();
    XPathExpression launchActionBuildConfigurationExpr =
        launchActionBuildConfigurationXpath.compile("//LaunchAction//@buildConfiguration");
    String launchActionBuildConfigurationBlueprintIdentifier =
        (String) launchActionBuildConfigurationExpr.evaluate(scheme, XPathConstants.STRING);
    assertThat(launchActionBuildConfigurationBlueprintIdentifier, equalTo("Debug"));

    XPath profileActionXpath = xpathFactory.newXPath();
    XPathExpression profileActionExpr =
        profileActionXpath.compile("//ProfileAction//BuildableReference/@BlueprintIdentifier");
    String profileActionBlueprintIdentifier =
        (String) profileActionExpr.evaluate(scheme, XPathConstants.STRING);
    assertThat(profileActionBlueprintIdentifier, equalTo(""));

    XPath profileActionBuildConfigurationXpath = xpathFactory.newXPath();
    XPathExpression profileActionBuildConfigurationExpr =
        profileActionBuildConfigurationXpath.compile("//ProfileAction//@buildConfiguration");
    String profileActionBuildConfigurationBlueprintIdentifier =
        (String) profileActionBuildConfigurationExpr.evaluate(scheme, XPathConstants.STRING);
    assertThat(profileActionBuildConfigurationBlueprintIdentifier, equalTo("Release"));

    XPath analyzeActionBuildConfigurationXpath = xpathFactory.newXPath();
    XPathExpression analyzeActionBuildConfigurationExpr =
        analyzeActionBuildConfigurationXpath.compile("//AnalyzeAction//@buildConfiguration");
    String analyzeActionBuildConfigurationBlueprintIdentifier =
        (String) analyzeActionBuildConfigurationExpr.evaluate(scheme, XPathConstants.STRING);
    assertThat(analyzeActionBuildConfigurationBlueprintIdentifier, equalTo("Debug"));

    XPath archiveActionBuildConfigurationXpath = xpathFactory.newXPath();
    XPathExpression archiveActionBuildConfigurationExpr =
        archiveActionBuildConfigurationXpath.compile("//ArchiveAction//@buildConfiguration");
    String archiveActionBuildConfigurationBlueprintIdentifier =
        (String) archiveActionBuildConfigurationExpr.evaluate(scheme, XPathConstants.STRING);
    assertThat(archiveActionBuildConfigurationBlueprintIdentifier, equalTo("Release"));
  }
}
