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
import static com.facebook.buck.apple.xcode.ProjectGeneratorTestUtils.createPartialGraphFromBuildRules;
import static org.hamcrest.Matchers.either;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.core.IsNull.nullValue;
import static org.junit.Assert.assertThat;

import com.facebook.buck.apple.IosLibraryDescription;
import com.facebook.buck.apple.IosTestDescription;
import com.facebook.buck.apple.SchemeActionType;
import com.facebook.buck.apple.XcodeNativeDescription;
import com.facebook.buck.apple.xcode.xcodeproj.PBXFileReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXNativeTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.cxx.Archives;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.timing.SettableFakeClock;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProjectFilesystem;
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
  private IosLibraryDescription iosLibraryDescription;
  private IosTestDescription iosTestDescription;
  private XcodeNativeDescription xcodeNativeDescription;

  @Before
  public void setUp() throws IOException {
    clock = new SettableFakeClock(0, 0);
    projectFilesystem = new FakeProjectFilesystem(clock);
    iosLibraryDescription = new IosLibraryDescription(Archives.DEFAULT_ARCHIVE_PATH);
    iosTestDescription = new IosTestDescription();
    xcodeNativeDescription = new XcodeNativeDescription();
  }

  @Test
  public void schemeWithMultipleTargetsBuildsInCorrectOrder() throws Exception {
    BuildRule rootRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "root").build(),
        ImmutableSortedSet.<BuildRule>of(),
        iosLibraryDescription);
    BuildRule leftRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "left").build(),
        ImmutableSortedSet.of(rootRule),
        iosLibraryDescription);
    BuildRule rightRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "right").build(),
        ImmutableSortedSet.of(rootRule),
        iosLibraryDescription);
    BuildRule childRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "child").build(),
        ImmutableSortedSet.of(leftRule, rightRule),
        iosLibraryDescription);

    PartialGraph partialGraph = createPartialGraphFromBuildRules(
        ImmutableSet.<BuildRule>of(
            rootRule,
            leftRule,
            rightRule,
            childRule));

    ImmutableMap.Builder<BuildRule, PBXTarget> buildRuleToTargetMapBuilder =
      ImmutableMap.builder();
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder =
      ImmutableMap.builder();

    PBXTarget rootTarget = new PBXNativeTarget("rootRule");
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductReference(
        new PBXFileReference(
            "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    buildRuleToTargetMapBuilder.put(rootRule, rootTarget);
    PBXTarget leftTarget = new PBXNativeTarget("leftRule");
    leftTarget.setGlobalID("leftGID");
    leftTarget.setProductReference(
        new PBXFileReference(
            "left.a", "left.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    buildRuleToTargetMapBuilder.put(leftRule, leftTarget);
    PBXTarget rightTarget = new PBXNativeTarget("rightRule");
    rightTarget.setGlobalID("rightGID");
    rightTarget.setProductReference(
        new PBXFileReference(
            "right.a", "right.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    buildRuleToTargetMapBuilder.put(rightRule, rightTarget);
    PBXTarget childTarget = new PBXNativeTarget("childRule");
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
        partialGraph,
        rootRule,
        ImmutableSet.of(rootRule, leftRule, rightRule, childRule),
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

    List<String> expectedOrdering1 = ImmutableList.of(
        "rootGID",
        "leftGID",
        "rightGID",
        "childGID");
    List<String> expectedOrdering2 = ImmutableList.of(
        "rootGID",
        "rightGID",
        "leftGID",
        "childGID");

    List<String> actualOrdering = Lists.newArrayList();
    for (int i = 0; i < nodes.getLength(); i++) {
      actualOrdering.add(nodes.item(i).getNodeValue());
    }
    assertThat(actualOrdering, either(equalTo(expectedOrdering1)).or(equalTo(expectedOrdering2)));
  }

  @Test(expected = HumanReadableException.class)
  public void schemeWithTargetWithoutCorrespondingProjectsFails() throws Exception {
    BuildRule rootRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "root").build(),
        ImmutableSortedSet.<BuildRule>of(),
        iosLibraryDescription);
    PartialGraph partialGraph = createPartialGraphFromBuildRules(ImmutableSet.of(rootRule));

    SchemeGenerator schemeGenerator = new SchemeGenerator(
        projectFilesystem,
        partialGraph,
        rootRule,
        ImmutableSet.of(rootRule),
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
    BuildRule xcodeNativeRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "xcode-native").build(),
        ImmutableSortedSet.<BuildRule>of(),
        xcodeNativeDescription);
    BuildRule rootRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "root").build(),
        ImmutableSortedSet.of(xcodeNativeRule),
        iosLibraryDescription);

    PartialGraph partialGraph = createPartialGraphFromBuildRules(
        ImmutableSet.of(
            rootRule));

    ImmutableMap.Builder<BuildRule, PBXTarget> buildRuleToTargetMapBuilder =
      ImmutableMap.builder();
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder =
      ImmutableMap.builder();

    PBXTarget rootTarget = new PBXNativeTarget("root");
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductReference(
        new PBXFileReference(
            "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    buildRuleToTargetMapBuilder.put(rootRule, rootTarget);
    PBXTarget xcodeNativeTarget = new PBXNativeTarget("xcode-native");
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
        partialGraph,
        rootRule,
        ImmutableSet.of(rootRule, xcodeNativeRule),
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
  public void schemeIncludesAllExpectedActions() throws Exception {
    BuildRule rootRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "root").build(),
        ImmutableSortedSet.<BuildRule>of(),
        iosLibraryDescription);
    BuildRule testRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "test").build(),
        ImmutableSortedSet.of(rootRule),
        iosTestDescription);

    PartialGraph partialGraph = createPartialGraphFromBuildRules(
        ImmutableSet.<BuildRule>of(
            rootRule,
            testRule));

    ImmutableMap.Builder<BuildRule, PBXTarget> buildRuleToTargetMapBuilder =
      ImmutableMap.builder();
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder =
      ImmutableMap.builder();

    PBXTarget rootTarget = new PBXNativeTarget("rootRule");
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductReference(
        new PBXFileReference(
            "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    buildRuleToTargetMapBuilder.put(rootRule, rootTarget);
    PBXTarget testTarget = new PBXNativeTarget("testRule");
    testTarget.setGlobalID("testGID");
    testTarget.setProductReference(
        new PBXFileReference(
            "test.a", "test.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    buildRuleToTargetMapBuilder.put(testRule, testTarget);

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(rootTarget, pbxprojectPath);
    targetToProjectPathMapBuilder.put(testTarget, pbxprojectPath);

    SchemeGenerator schemeGenerator = new SchemeGenerator(
        projectFilesystem,
        partialGraph,
        rootRule,
        ImmutableSet.of(rootRule),
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

    XPath buildActionXpath = xpathFactory.newXPath();
    XPathExpression buildActionExpr =
        buildActionXpath.compile("//BuildAction//BuildableReference/@BlueprintIdentifier");
    NodeList buildActionNodes = (NodeList) buildActionExpr.evaluate(scheme, XPathConstants.NODESET);

    List<String> expectedOrdering = ImmutableList.of(
        "rootGID",
        "testGID");

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
    assertThat(testActionBlueprintIdentifier, equalTo("testGID"));

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
    BuildRule rootRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "root").build(),
        ImmutableSortedSet.<BuildRule>of(),
        iosLibraryDescription);

    PartialGraph partialGraph = createPartialGraphFromBuildRules(
        ImmutableSet.<BuildRule>of(rootRule));

    ImmutableMap.Builder<BuildRule, PBXTarget> buildRuleToTargetMapBuilder =
      ImmutableMap.builder();
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder =
      ImmutableMap.builder();

    PBXTarget rootTarget = new PBXNativeTarget("rootRule");
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductReference(
        new PBXFileReference(
            "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    buildRuleToTargetMapBuilder.put(rootRule, rootTarget);

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(rootTarget, pbxprojectPath);

    SchemeGenerator schemeGenerator = new SchemeGenerator(
        projectFilesystem,
        partialGraph,
        rootRule,
        ImmutableSet.of(rootRule),
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
    BuildRule rootRule = createBuildRuleWithDefaults(
        BuildTarget.builder("//foo", "root").build(),
        ImmutableSortedSet.<BuildRule>of(),
        iosLibraryDescription);

    PartialGraph partialGraph = createPartialGraphFromBuildRules(
        ImmutableSet.<BuildRule>of(rootRule));

    ImmutableMap.Builder<BuildRule, PBXTarget> buildRuleToTargetMapBuilder =
      ImmutableMap.builder();
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder =
      ImmutableMap.builder();

    PBXTarget rootTarget = new PBXNativeTarget("rootRule");
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductReference(
        new PBXFileReference(
            "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    buildRuleToTargetMapBuilder.put(rootRule, rootTarget);

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(rootTarget, pbxprojectPath);

    SchemeGenerator schemeGenerator = new SchemeGenerator(
        projectFilesystem,
        partialGraph,
        rootRule,
        ImmutableSet.of(rootRule),
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
    {
      BuildRule rootRule = createBuildRuleWithDefaults(
          BuildTarget.builder("//foo", "root").build(),
          ImmutableSortedSet.<BuildRule>of(),
          iosLibraryDescription);

      PartialGraph partialGraph = createPartialGraphFromBuildRules(ImmutableSet.of(rootRule));

      ImmutableMap.Builder<BuildRule, PBXTarget> buildRuleToTargetMapBuilder =
        ImmutableMap.builder();
      ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder =
        ImmutableMap.builder();

      PBXTarget rootTarget = new PBXNativeTarget("rootRule");
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
          partialGraph,
          rootRule,
          ImmutableSet.of(rootRule),
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
          iosLibraryDescription);

      PartialGraph partialGraph = createPartialGraphFromBuildRules(ImmutableSet.of(rootRule));

      PBXTarget rootTarget = new PBXNativeTarget("rootRule2");
      rootTarget.setGlobalID("root2GID");
      rootTarget.setProductReference(
          new PBXFileReference(
              "root2.a", "root2.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));

      Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");

      clock.setCurrentTimeMillis(64738);
      SchemeGenerator schemeGenerator = new SchemeGenerator(
          projectFilesystem,
          partialGraph,
          rootRule,
          ImmutableSet.of(rootRule),
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
    {
      BuildRule rootRule = createBuildRuleWithDefaults(
          BuildTarget.builder("//foo", "root").build(),
          ImmutableSortedSet.<BuildRule>of(),
          iosLibraryDescription);

      PartialGraph partialGraph = createPartialGraphFromBuildRules(ImmutableSet.of(rootRule));

      PBXTarget rootTarget = new PBXNativeTarget("rootRule");
      rootTarget.setGlobalID("rootGID");
      rootTarget.setProductReference(
          new PBXFileReference(
              "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));

      Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");

      clock.setCurrentTimeMillis(49152);
      SchemeGenerator schemeGenerator = new SchemeGenerator(
          projectFilesystem,
          partialGraph,
          rootRule,
          ImmutableSet.of(rootRule),
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
          BuildTarget.builder("//foo", "root").build(),
          ImmutableSortedSet.<BuildRule>of(),
          iosLibraryDescription);

      PartialGraph partialGraph = createPartialGraphFromBuildRules(ImmutableSet.of(rootRule));

      PBXTarget rootTarget = new PBXNativeTarget("rootRule");
      rootTarget.setGlobalID("rootGID");
      rootTarget.setProductReference(
          new PBXFileReference(
              "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));

      Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");

      clock.setCurrentTimeMillis(64738);
      SchemeGenerator schemeGenerator = new SchemeGenerator(
          projectFilesystem,
          partialGraph,
          rootRule,
          ImmutableSet.of(rootRule),
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
}
