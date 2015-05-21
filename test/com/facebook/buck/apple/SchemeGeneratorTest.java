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

package com.facebook.buck.apple;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.core.IsNull.nullValue;
import static org.junit.Assert.assertThat;

import com.facebook.buck.apple.xcode.xcodeproj.PBXFileReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXNativeTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.apple.xcode.xcodeproj.ProductType;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.timing.SettableFakeClock;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
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

  @Before
  public void setUp() throws IOException {
    clock = new SettableFakeClock(0, 0);
    projectFilesystem = new FakeProjectFilesystem(clock);
  }

  @Test
  public void schemeWithMultipleTargetsBuildsInCorrectOrder() throws Exception {
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder = ImmutableMap.builder();

    PBXTarget rootTarget = new PBXNativeTarget("rootRule");
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductReference(
        new PBXFileReference("root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    rootTarget.setProductType(ProductType.STATIC_LIBRARY);

    PBXTarget leftTarget = new PBXNativeTarget("leftRule");
    leftTarget.setGlobalID("leftGID");
    leftTarget.setProductReference(
        new PBXFileReference("left.a", "left.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    leftTarget.setProductType(ProductType.STATIC_LIBRARY);

    PBXTarget rightTarget = new PBXNativeTarget("rightRule");
    rightTarget.setGlobalID("rightGID");
    rightTarget.setProductReference(
        new PBXFileReference("right.a", "right.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    rightTarget.setProductType(ProductType.STATIC_LIBRARY);

    PBXTarget childTarget = new PBXNativeTarget("childRule");
    childTarget.setGlobalID("childGID");
    childTarget.setProductReference(
        new PBXFileReference("child.a", "child.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    childTarget.setProductType(ProductType.STATIC_LIBRARY);

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(rootTarget, pbxprojectPath);
    targetToProjectPathMapBuilder.put(leftTarget, pbxprojectPath);
    targetToProjectPathMapBuilder.put(rightTarget, pbxprojectPath);
    targetToProjectPathMapBuilder.put(childTarget, pbxprojectPath);

    SchemeGenerator schemeGenerator = new SchemeGenerator(
        projectFilesystem,
        Optional.of(childTarget),
        ImmutableSet.of(rootTarget, leftTarget, rightTarget, childTarget),
        ImmutableSet.<PBXTarget>of(),
        ImmutableSet.<PBXTarget>of(),
        "TestScheme",
        Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
        false /* primaryTargetIsBuildWithBuck */,
        Optional.<String>absent() /* runnablePath */,
        Optional.<String>absent() /* remoteRunnablePath */,
        SchemeActionType.DEFAULT_CONFIG_NAMES,
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

  @Test
  public void schemeBuildsAndTestsAppleTestTargets() throws Exception {
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder = ImmutableMap.builder();

    PBXTarget testDepTarget = new PBXNativeTarget("testDep");
    testDepTarget.setGlobalID("testDepGID");
    testDepTarget.setProductReference(
        new PBXFileReference(
            "libDep.a", "libDep.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    testDepTarget.setProductType(ProductType.STATIC_LIBRARY);

    PBXTarget testLibraryTarget =
        new PBXNativeTarget("testLibrary");
    testLibraryTarget.setGlobalID("testLibraryGID");
    testLibraryTarget.setProductReference(
        new PBXFileReference(
            "lib.a", "lib.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    testLibraryTarget.setProductType(ProductType.STATIC_LIBRARY);

    PBXTarget testTarget = new PBXNativeTarget("test");
    testTarget.setGlobalID("testGID");
    testTarget.setProductReference(
        new PBXFileReference(
            "test.xctest", "test.xctest", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    testTarget.setProductType(ProductType.UNIT_TEST);

    PBXTarget rootTarget = new PBXNativeTarget("root");
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductReference(
        new PBXFileReference(
            "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    rootTarget.setProductType(ProductType.STATIC_LIBRARY);

    Path projectPath = Paths.get("foo/test.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(testTarget, projectPath);
    targetToProjectPathMapBuilder.put(testDepTarget, projectPath);
    targetToProjectPathMapBuilder.put(testLibraryTarget, projectPath);
    targetToProjectPathMapBuilder.put(rootTarget, projectPath);

    SchemeGenerator schemeGenerator = new SchemeGenerator(
        projectFilesystem,
        Optional.of(rootTarget),
        ImmutableSet.of(rootTarget),
        ImmutableSet.of(testDepTarget, testTarget),
        ImmutableSet.of(testTarget),
        "TestScheme",
        Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
        false /* primaryTargetIsBuildWithBuck */,
        Optional.<String>absent() /* runnablePath */,
        Optional.<String>absent() /* remoteRunnablePath */,
        SchemeActionType.DEFAULT_CONFIG_NAMES,
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
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder = ImmutableMap.builder();

    PBXTarget rootTarget = new PBXNativeTarget("rootRule");
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductReference(
        new PBXFileReference(
            "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    rootTarget.setProductType(ProductType.STATIC_LIBRARY);

    PBXTarget testTarget = new PBXNativeTarget("testRule");
    testTarget.setGlobalID("testGID");
    testTarget.setProductReference(
        new PBXFileReference(
            "test.a", "test.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    testTarget.setProductType(ProductType.STATIC_LIBRARY);

    PBXTarget testBundleTarget =
        new PBXNativeTarget("testBundleRule");
    testBundleTarget.setGlobalID("testBundleGID");
    testBundleTarget.setProductReference(
        new PBXFileReference(
            "test.xctest",
            "test.xctest",
            PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    testBundleTarget.setProductType(ProductType.UNIT_TEST);

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(rootTarget, pbxprojectPath);
    targetToProjectPathMapBuilder.put(testTarget, pbxprojectPath);
    targetToProjectPathMapBuilder.put(testBundleTarget, pbxprojectPath);

    SchemeGenerator schemeGenerator = new SchemeGenerator(
        projectFilesystem,
        Optional.of(rootTarget),
        ImmutableSet.of(rootTarget),
        ImmutableSet.of(testBundleTarget),
        ImmutableSet.of(testBundleTarget),
        "TestScheme",
        Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
        false /* primaryTargetIsBuildWithBuck */,
        Optional.<String>absent() /* runnablePath */,
        Optional.<String>absent() /* remoteRunnablePath */,
        SchemeActionType.DEFAULT_CONFIG_NAMES,
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
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder = ImmutableMap.builder();

    PBXTarget rootTarget = new PBXNativeTarget("rootRule");
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductReference(
        new PBXFileReference(
            "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    rootTarget.setProductType(ProductType.STATIC_LIBRARY);

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(rootTarget, pbxprojectPath);

    SchemeGenerator schemeGenerator = new SchemeGenerator(
        projectFilesystem,
        Optional.of(rootTarget),
        ImmutableSet.of(rootTarget),
        ImmutableSet.<PBXTarget>of(),
        ImmutableSet.<PBXTarget>of(),
        "TestScheme",
        Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
        false /* primaryTargetIsBuildWithBuck */,
        Optional.<String>absent() /* runnablePath */,
        Optional.<String>absent() /* remoteRunnablePath */,
        SchemeActionType.DEFAULT_CONFIG_NAMES,
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
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder = ImmutableMap.builder();

    PBXTarget rootTarget = new PBXNativeTarget("rootRule");
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductReference(
        new PBXFileReference(
            "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    rootTarget.setProductType(ProductType.STATIC_LIBRARY);

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(rootTarget, pbxprojectPath);

    SchemeGenerator schemeGenerator = new SchemeGenerator(
        projectFilesystem,
        Optional.of(rootTarget),
        ImmutableSet.of(rootTarget),
        ImmutableSet.<PBXTarget>of(),
        ImmutableSet.<PBXTarget>of(),
        "TestScheme",
        Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
        false /* primaryTargetIsBuildWithBuck */,
        Optional.<String>absent() /* runnablePath */,
        Optional.<String>absent() /* remoteRunnablePath */,
        SchemeActionType.DEFAULT_CONFIG_NAMES,
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
      ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder = ImmutableMap.builder();

      PBXTarget rootTarget = new PBXNativeTarget("rootRule");
      rootTarget.setGlobalID("rootGID");
      rootTarget.setProductReference(
          new PBXFileReference(
              "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
      rootTarget.setProductType(ProductType.STATIC_LIBRARY);

      Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
      targetToProjectPathMapBuilder.put(rootTarget, pbxprojectPath);

      clock.setCurrentTimeMillis(49152);
      SchemeGenerator schemeGenerator = new SchemeGenerator(
          projectFilesystem,
          Optional.of(rootTarget),
          ImmutableSet.of(rootTarget),
          ImmutableSet.<PBXTarget>of(),
          ImmutableSet.<PBXTarget>of(),
          "TestScheme",
          Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
          false /* primaryTargetIsBuildWithBuck */,
          Optional.<String>absent() /* runnablePath */,
          Optional.<String>absent() /* remoteRunnablePath */,
          SchemeActionType.DEFAULT_CONFIG_NAMES,
          targetToProjectPathMapBuilder.build());

      Path schemePath = schemeGenerator.writeScheme();
      assertThat(projectFilesystem.getLastModifiedTime(schemePath), equalTo(49152L));
    }

    {
      PBXTarget rootTarget = new PBXNativeTarget("rootRule2");
      rootTarget.setGlobalID("root2GID");
      rootTarget.setProductReference(
          new PBXFileReference(
              "root2.a", "root2.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
      rootTarget.setProductType(ProductType.STATIC_LIBRARY);

      Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");

      clock.setCurrentTimeMillis(64738);
      SchemeGenerator schemeGenerator = new SchemeGenerator(
          projectFilesystem,
          Optional.of(rootTarget),
          ImmutableSet.of(rootTarget),
          ImmutableSet.<PBXTarget>of(),
          ImmutableSet.<PBXTarget>of(),
          "TestScheme",
          Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
          false /* primaryTargetIsBuildWithBuck */,
          Optional.<String>absent() /* runnablePath */,
          Optional.<String>absent() /* remoteRunnablePath */,
          SchemeActionType.DEFAULT_CONFIG_NAMES,
          ImmutableMap.of(rootTarget, pbxprojectPath));

      Path schemePath = schemeGenerator.writeScheme();
      assertThat(projectFilesystem.getLastModifiedTime(schemePath), equalTo(64738L));
    }
  }

  @Test
  public void schemeIsNotRewrittenIfContentsHaveNotChanged() throws IOException {
    {
      PBXTarget rootTarget = new PBXNativeTarget("rootRule");
      rootTarget.setGlobalID("rootGID");
      rootTarget.setProductReference(
          new PBXFileReference(
              "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
      rootTarget.setProductType(ProductType.STATIC_LIBRARY);

      Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");

      clock.setCurrentTimeMillis(49152);
      SchemeGenerator schemeGenerator = new SchemeGenerator(
          projectFilesystem,
          Optional.of(rootTarget),
          ImmutableSet.of(rootTarget),
          ImmutableSet.<PBXTarget>of(),
          ImmutableSet.<PBXTarget>of(),
          "TestScheme",
          Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
          false /* primaryTargetIsBuildWithBuck */,
          Optional.<String>absent() /* runnablePath */,
          Optional.<String>absent() /* remoteRunnablePath */,
          SchemeActionType.DEFAULT_CONFIG_NAMES,
          ImmutableMap.of(rootTarget, pbxprojectPath));

      Path schemePath = schemeGenerator.writeScheme();
      assertThat(projectFilesystem.getLastModifiedTime(schemePath), equalTo(49152L));
    }

    {
      PBXTarget rootTarget = new PBXNativeTarget("rootRule");
      rootTarget.setGlobalID("rootGID");
      rootTarget.setProductReference(
          new PBXFileReference(
              "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
      rootTarget.setProductType(ProductType.STATIC_LIBRARY);

      Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");

      clock.setCurrentTimeMillis(64738);
      SchemeGenerator schemeGenerator = new SchemeGenerator(
          projectFilesystem,
          Optional.of(rootTarget),
          ImmutableSet.of(rootTarget),
          ImmutableSet.<PBXTarget>of(),
          ImmutableSet.<PBXTarget>of(),
          "TestScheme",
          Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
          false /* primaryTargetIsBuildWithBuck */,
          Optional.<String>absent() /* runnablePath */,
          Optional.<String>absent() /* remoteRunnablePath */,
          SchemeActionType.DEFAULT_CONFIG_NAMES,
          ImmutableMap.of(rootTarget, pbxprojectPath));
      Path schemePath = schemeGenerator.writeScheme();
      assertThat(projectFilesystem.getLastModifiedTime(schemePath), equalTo(49152L));
    }
  }

  @Test
  public void schemeWithNoPrimaryRuleCanIncludeTests() throws Exception{
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder = ImmutableMap.builder();

    PBXTarget testLibraryTarget = new PBXNativeTarget("testLibrary");
    testLibraryTarget.setGlobalID("testLibraryGID");
    testLibraryTarget.setProductReference(
        new PBXFileReference(
            "lib.a", "lib.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    testLibraryTarget.setProductType(ProductType.STATIC_LIBRARY);

    PBXTarget testTarget = new PBXNativeTarget("testRule");
    testTarget.setGlobalID("testGID");
    testTarget.setProductReference(
        new PBXFileReference(
            "test.a", "test.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    testTarget.setProductType(ProductType.STATIC_LIBRARY);

    PBXTarget testBundleTarget = new PBXNativeTarget("testBundleRule");
    testBundleTarget.setGlobalID("testBundleGID");
    testBundleTarget.setProductReference(
        new PBXFileReference(
            "test.xctest",
            "test.xctest",
            PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    testBundleTarget.setProductType(ProductType.UNIT_TEST);

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(testLibraryTarget, pbxprojectPath);
    targetToProjectPathMapBuilder.put(testTarget, pbxprojectPath);
    targetToProjectPathMapBuilder.put(testBundleTarget, pbxprojectPath);

    SchemeGenerator schemeGenerator = new SchemeGenerator(
        projectFilesystem,
        Optional.<PBXTarget>absent(),
        ImmutableSet.<PBXTarget>of(),
        ImmutableSet.of(testBundleTarget),
        ImmutableSet.of(testBundleTarget),
        "TestScheme",
        Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
        false /* primaryTargetIsBuildWithBuck */,
        Optional.<String>absent() /* runnablePath */,
        Optional.<String>absent() /* remoteRunnablePath */,
        SchemeActionType.DEFAULT_CONFIG_NAMES,
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

  @Test
  public void launchActionShouldNotContainRemoteRunnableWhenNotProvided() throws Exception {
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder = ImmutableMap.builder();

    PBXTarget rootTarget = new PBXNativeTarget("rootRule");
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductReference(
        new PBXFileReference(
            "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    rootTarget.setProductType(ProductType.STATIC_LIBRARY);

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(rootTarget, pbxprojectPath);

    SchemeGenerator schemeGenerator = new SchemeGenerator(
        projectFilesystem,
        Optional.of(rootTarget),
        ImmutableSet.of(rootTarget),
        ImmutableSet.<PBXTarget>of(),
        ImmutableSet.<PBXTarget>of(),
        "TestScheme",
        Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
        false /* primaryTargetIsBuildWithBuck */,
        Optional.<String>absent() /* runnablePath */,
        Optional.<String>absent() /* remoteRunnablePath */,
        SchemeActionType.DEFAULT_CONFIG_NAMES,
        targetToProjectPathMapBuilder.build());

    Path schemePath = schemeGenerator.writeScheme();

    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
    Document scheme = dBuilder.parse(projectFilesystem.newFileInputStream(schemePath));

    XPathFactory xpathFactory = XPathFactory.newInstance();

    XPath remoteRunnableLaunchActionXPath = xpathFactory.newXPath();
    XPathExpression remoteRunnableLaunchActionExpr =
        remoteRunnableLaunchActionXPath.compile("//LaunchAction/RemoteRunnable");
    NodeList remoteRunnables = (NodeList) remoteRunnableLaunchActionExpr.evaluate(
        scheme, XPathConstants.NODESET);

    assertThat(remoteRunnables.getLength(), equalTo(0));
  }

  @Test
  public void launchActionShouldContainRemoteRunnableWhenProvided() throws Exception {
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder = ImmutableMap.builder();

    PBXTarget rootTarget = new PBXNativeTarget("rootRule");
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductReference(
        new PBXFileReference(
            "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    rootTarget.setProductType(ProductType.STATIC_LIBRARY);

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(rootTarget, pbxprojectPath);

    SchemeGenerator schemeGenerator = new SchemeGenerator(
        projectFilesystem,
        Optional.of(rootTarget),
        ImmutableSet.of(rootTarget),
        ImmutableSet.<PBXTarget>of(),
        ImmutableSet.<PBXTarget>of(),
        "TestScheme",
        Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
        false /* primaryTargetIsBuildWithBuck */,
        Optional.<String>absent() /* runnablePath */,
        Optional.of("/RemoteApp") /* remoteRunnablePath */,
        SchemeActionType.DEFAULT_CONFIG_NAMES,
        targetToProjectPathMapBuilder.build());

    Path schemePath = schemeGenerator.writeScheme();

    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
    Document scheme = dBuilder.parse(projectFilesystem.newFileInputStream(schemePath));

    XPathFactory xpathFactory = XPathFactory.newInstance();

    XPath remoteRunnableLaunchActionXPath = xpathFactory.newXPath();
    XPathExpression remoteRunnableLaunchActionExpr =
        remoteRunnableLaunchActionXPath.compile("//LaunchAction/RemoteRunnable");
    NodeList remoteRunnables = (NodeList) remoteRunnableLaunchActionExpr.evaluate(
        scheme, XPathConstants.NODESET);

    assertThat(remoteRunnables.getLength(), equalTo(1));

    Node remoteRunnable = remoteRunnables.item(0);
    assertThat(
        remoteRunnable.getAttributes().getNamedItem("runnableDebuggingMode").getNodeValue(),
        equalTo("2"));
    assertThat(
        remoteRunnable.getAttributes().getNamedItem("BundleIdentifier").getNodeValue(),
        equalTo("com.apple.springboard"));
    assertThat(
        remoteRunnable.getAttributes().getNamedItem("RemotePath").getNodeValue(),
        equalTo("/RemoteApp"));

    XPath buildXpath = xpathFactory.newXPath();
    XPathExpression buildExpr =
        buildXpath.compile("//LaunchAction//BuildableReference/@BlueprintIdentifier");
    NodeList buildNodes = (NodeList) buildExpr.evaluate(scheme, XPathConstants.NODESET);

    // Make sure both copies of the BuildableReference are present.
    assertThat(buildNodes.getLength(), equalTo(2));
    assertThat(buildNodes.item(0).getNodeValue(), equalTo("rootGID"));
    assertThat(buildNodes.item(1).getNodeValue(), equalTo("rootGID"));
  }

  @Test
  public void whenProvidedAPrimaryTargetThatIsBuiltWithBuckLaunchesIt() throws Exception {
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder = ImmutableMap.builder();

    PBXTarget rootTarget = new PBXNativeTarget("rootRule");
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductName("Foo");
    String runnablePath = "buck-out/gen/Foo/Foo.app";

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(rootTarget, pbxprojectPath);

    SchemeGenerator schemeGenerator = new SchemeGenerator(
        projectFilesystem,
        Optional.of(rootTarget),
        ImmutableSet.of(rootTarget),
        ImmutableSet.<PBXTarget>of(),
        ImmutableSet.<PBXTarget>of(),
        "TestScheme",
        Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
        true /* primaryTargetIsBuildWithBuck */,
        Optional.of(runnablePath) /* runnablePath */,
        Optional.<String>absent() /* remoteRunnablePath */,
        SchemeActionType.DEFAULT_CONFIG_NAMES,
        targetToProjectPathMapBuilder.build());

    Path schemePath = schemeGenerator.writeScheme();

    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
    Document scheme = dBuilder.parse(projectFilesystem.newFileInputStream(schemePath));

    XPathFactory xpathFactory = XPathFactory.newInstance();

    XPath runnableLaunchActionXPath = xpathFactory.newXPath();
    XPathExpression runnableLaunchActionExpr =
        runnableLaunchActionXPath.compile("//LaunchAction/PathRunnable");
    NodeList runnables = (NodeList) runnableLaunchActionExpr.evaluate(
        scheme, XPathConstants.NODESET);

    assertThat(runnables.getLength(), equalTo(1));

    Node runnable = runnables.item(0);
    assertThat(
        runnable.getAttributes().getNamedItem("FilePath").getNodeValue(),
        equalTo(runnablePath));
  }

  @Test
  public void whenProvidedAPrimaryTargetThatIsBuiltWithBuckDoesntBuildDependencies()
      throws Exception {
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder = ImmutableMap.builder();

    PBXTarget libraryTarget = new PBXNativeTarget("library");
    libraryTarget.setGlobalID("libraryGID");
    libraryTarget.setProductReference(
        new PBXFileReference("lib.a", "lib.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR));
    libraryTarget.setProductType(ProductType.STATIC_LIBRARY);

    PBXTarget rootTarget = new PBXNativeTarget("rootRule");
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductName("Foo");
    String runnablePath = "buck-out/gen/Foo/Foo.app";

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(libraryTarget, pbxprojectPath);
    targetToProjectPathMapBuilder.put(rootTarget, pbxprojectPath);

    SchemeGenerator schemeGenerator = new SchemeGenerator(
        projectFilesystem,
        Optional.of(rootTarget),
        ImmutableSet.of(libraryTarget, rootTarget),
        ImmutableSet.<PBXTarget>of(),
        ImmutableSet.<PBXTarget>of(),
        "TestScheme",
        Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
        true /* primaryTargetIsBuildWithBuck */,
        Optional.of(runnablePath) /* runnablePath */,
        Optional.<String>absent() /* remoteRunnablePath */,
        SchemeActionType.DEFAULT_CONFIG_NAMES,
        targetToProjectPathMapBuilder.build());

    Path schemePath = schemeGenerator.writeScheme();

    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
    Document scheme = dBuilder.parse(projectFilesystem.newFileInputStream(schemePath));

    XPathFactory xpathFactory = XPathFactory.newInstance();

    XPath buildActionXpath = xpathFactory.newXPath();
    XPathExpression buildActionExpr =
        buildActionXpath.compile("//BuildAction//BuildActionEntry");
    NodeList buildActionNodes = (NodeList) buildActionExpr.evaluate(scheme, XPathConstants.NODESET);

    Node libraryNode = null;
    for (int i = 0; i < buildActionNodes.getLength(); i++) {
      Node node = buildActionNodes.item(i);
      if (node.getChildNodes().getLength() != 1) {
        continue;
      }
      Node buildableReference = node.getChildNodes().item(0);
      if (buildableReference
          .getAttributes()
          .getNamedItem("BlueprintIdentifier")
          .getNodeValue()
          .equals("libraryGID")) {
        libraryNode = node;
        break;
      }
    }
    assertThat(libraryNode, is(notNullValue()));
    assertThat(
        libraryNode.getAttributes().getNamedItem("buildForRunning").getNodeValue(),
        equalTo("NO"));
  }

}
