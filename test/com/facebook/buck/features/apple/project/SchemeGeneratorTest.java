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

package com.facebook.buck.features.apple.project;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.core.IsNull.nullValue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;

import com.facebook.buck.apple.xcode.AbstractPBXObjectFactory;
import com.facebook.buck.apple.xcode.XCScheme;
import com.facebook.buck.apple.xcode.xcodeproj.PBXFileReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXNativeTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.apple.xcode.xcodeproj.ProductTypes;
import com.facebook.buck.features.apple.common.SchemeActionType;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.util.timing.SettableFakeClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class SchemeGeneratorTest {

  private SettableFakeClock clock;
  private ProjectFilesystem projectFilesystem;

  @Before
  public void setUp() {
    clock = SettableFakeClock.DO_NOT_CARE;
    projectFilesystem = new FakeProjectFilesystem(clock);
  }

  @Test
  public void schemeWithMultipleTargetsBuildsInCorrectOrder() throws Exception {
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder = ImmutableMap.builder();

    PBXTarget rootTarget =
        new PBXNativeTarget("rootRule", AbstractPBXObjectFactory.DefaultFactory());
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductReference(
        new PBXFileReference(
            "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR, Optional.empty()));
    rootTarget.setProductType(ProductTypes.STATIC_LIBRARY);

    PBXTarget leftTarget =
        new PBXNativeTarget("leftRule", AbstractPBXObjectFactory.DefaultFactory());
    leftTarget.setGlobalID("leftGID");
    leftTarget.setProductReference(
        new PBXFileReference(
            "left.a", "left.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR, Optional.empty()));
    leftTarget.setProductType(ProductTypes.STATIC_LIBRARY);

    PBXTarget rightTarget =
        new PBXNativeTarget("rightRule", AbstractPBXObjectFactory.DefaultFactory());
    rightTarget.setGlobalID("rightGID");
    rightTarget.setProductReference(
        new PBXFileReference(
            "right.a", "right.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR, Optional.empty()));
    rightTarget.setProductType(ProductTypes.STATIC_LIBRARY);

    PBXTarget childTarget =
        new PBXNativeTarget("childRule", AbstractPBXObjectFactory.DefaultFactory());
    childTarget.setGlobalID("childGID");
    childTarget.setProductReference(
        new PBXFileReference(
            "child.a", "child.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR, Optional.empty()));
    childTarget.setProductType(ProductTypes.STATIC_LIBRARY);

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(rootTarget, pbxprojectPath);
    targetToProjectPathMapBuilder.put(leftTarget, pbxprojectPath);
    targetToProjectPathMapBuilder.put(rightTarget, pbxprojectPath);
    targetToProjectPathMapBuilder.put(childTarget, pbxprojectPath);

    SchemeGenerator schemeGenerator =
        new SchemeGenerator(
            projectFilesystem,
            Optional.of(childTarget),
            ImmutableSet.of(rootTarget, leftTarget, rightTarget, childTarget),
            ImmutableSet.of(),
            ImmutableSet.of(),
            "TestScheme",
            Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
            false /* parallelizeBuild */,
            Optional.empty() /* wasCreatedForAppExtension */,
            Optional.empty() /* runnablePath */,
            Optional.empty() /* remoteRunnablePath */,
            SchemeActionType.DEFAULT_CONFIG_NAMES,
            targetToProjectPathMapBuilder.build(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            XCScheme.LaunchAction.LaunchStyle.AUTO,
            Optional.empty(), /* watchAdapter */
            Optional.empty(), /* notificationPayloadFile */
            Optional.empty(), /* applicationRegion */
            Optional.empty() /* applicationLanguage */);

    Path schemePath = schemeGenerator.writeScheme();
    String schemeXml = projectFilesystem.readFileIfItExists(schemePath).get();
    System.out.println(schemeXml);

    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
    Document scheme = dBuilder.parse(projectFilesystem.newFileInputStream(schemePath));

    XPathFactory xpathFactory = XPathFactory.newInstance();
    XPath xpath = xpathFactory.newXPath();
    XPathExpression expr = xpath.compile("//BuildAction//BuildableReference/@BlueprintIdentifier");
    NodeList nodes = (NodeList) expr.evaluate(scheme, XPathConstants.NODESET);

    List<String> expectedOrdering = ImmutableList.of("rootGID", "leftGID", "rightGID", "childGID");

    List<String> actualOrdering = new ArrayList<>();
    for (int i = 0; i < nodes.getLength(); i++) {
      actualOrdering.add(nodes.item(i).getNodeValue());
    }
    assertThat(actualOrdering, equalTo(expectedOrdering));
  }

  @Test
  public void schemeBuildsAndTestsAppleTestTargets() throws Exception {
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder = ImmutableMap.builder();

    PBXTarget testDepTarget =
        new PBXNativeTarget("testDep", AbstractPBXObjectFactory.DefaultFactory());
    testDepTarget.setGlobalID("testDepGID");
    testDepTarget.setProductReference(
        new PBXFileReference(
            "libDep.a", "libDep.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR, Optional.empty()));
    testDepTarget.setProductType(ProductTypes.STATIC_LIBRARY);

    PBXTarget testLibraryTarget =
        new PBXNativeTarget("testLibrary", AbstractPBXObjectFactory.DefaultFactory());
    testLibraryTarget.setGlobalID("testLibraryGID");
    testLibraryTarget.setProductReference(
        new PBXFileReference(
            "lib.a", "lib.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR, Optional.empty()));
    testLibraryTarget.setProductType(ProductTypes.STATIC_LIBRARY);

    PBXTarget testTarget = new PBXNativeTarget("test", AbstractPBXObjectFactory.DefaultFactory());
    testTarget.setGlobalID("testGID");
    testTarget.setProductReference(
        new PBXFileReference(
            "test.xctest",
            "test.xctest",
            PBXReference.SourceTree.BUILT_PRODUCTS_DIR,
            Optional.empty()));
    testTarget.setProductType(ProductTypes.UNIT_TEST);

    PBXTarget rootTarget = new PBXNativeTarget("root", AbstractPBXObjectFactory.DefaultFactory());
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductReference(
        new PBXFileReference(
            "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR, Optional.empty()));
    rootTarget.setProductType(ProductTypes.STATIC_LIBRARY);

    Path projectPath = Paths.get("foo/test.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(testTarget, projectPath);
    targetToProjectPathMapBuilder.put(testDepTarget, projectPath);
    targetToProjectPathMapBuilder.put(testLibraryTarget, projectPath);
    targetToProjectPathMapBuilder.put(rootTarget, projectPath);

    SchemeGenerator schemeGenerator =
        new SchemeGenerator(
            projectFilesystem,
            Optional.of(rootTarget),
            ImmutableSet.of(rootTarget),
            ImmutableSet.of(testDepTarget, testTarget),
            ImmutableSet.of(testTarget),
            "TestScheme",
            Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
            false /* parallelizeBuild */,
            Optional.empty() /* wasCreatedForAppExtension */,
            Optional.empty() /* runnablePath */,
            Optional.empty() /* remoteRunnablePath */,
            SchemeActionType.DEFAULT_CONFIG_NAMES,
            targetToProjectPathMapBuilder.build(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            XCScheme.LaunchAction.LaunchStyle.AUTO,
            Optional.empty(), /* watchAdapter */
            Optional.empty(), /* notificationPayloadFile */
            Optional.empty(), /* applicationRegion */
            Optional.empty() /* applicationLanguage */);

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

    List<String> actualBuildOrdering = new ArrayList<>();
    for (int i = 0; i < buildNodes.getLength(); i++) {
      actualBuildOrdering.add(buildNodes.item(i).getNodeValue());
    }
    assertThat(actualBuildOrdering, equalTo(expectedBuildOrdering));

    XPath textXpath = xpathFactory.newXPath();
    XPathExpression testExpr =
        textXpath.compile(
            "//TestAction//TestableReference/BuildableReference/@BlueprintIdentifier");
    NodeList testNodes = (NodeList) testExpr.evaluate(scheme, XPathConstants.NODESET);

    List<String> expectedTestOrdering = ImmutableList.of("testGID");

    List<String> actualTestOrdering = new ArrayList<>();
    for (int i = 0; i < testNodes.getLength(); i++) {
      actualTestOrdering.add(testNodes.item(i).getNodeValue());
    }
    assertThat(actualTestOrdering, equalTo(expectedTestOrdering));
  }

  @Test
  public void schemeIncludesAllExpectedActions() throws Exception {
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder = ImmutableMap.builder();

    PBXTarget rootTarget =
        new PBXNativeTarget("rootRule", AbstractPBXObjectFactory.DefaultFactory());
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductReference(
        new PBXFileReference(
            "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR, Optional.empty()));
    rootTarget.setProductType(ProductTypes.STATIC_LIBRARY);

    PBXTarget testTarget =
        new PBXNativeTarget("testRule", AbstractPBXObjectFactory.DefaultFactory());
    testTarget.setGlobalID("testGID");
    testTarget.setProductReference(
        new PBXFileReference(
            "test.a", "test.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR, Optional.empty()));
    testTarget.setProductType(ProductTypes.STATIC_LIBRARY);

    PBXTarget testBundleTarget =
        new PBXNativeTarget("testBundleRule", AbstractPBXObjectFactory.DefaultFactory());
    testBundleTarget.setGlobalID("testBundleGID");
    testBundleTarget.setProductReference(
        new PBXFileReference(
            "test.xctest",
            "test.xctest",
            PBXReference.SourceTree.BUILT_PRODUCTS_DIR,
            Optional.empty()));
    testBundleTarget.setProductType(ProductTypes.UNIT_TEST);

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(rootTarget, pbxprojectPath);
    targetToProjectPathMapBuilder.put(testTarget, pbxprojectPath);
    targetToProjectPathMapBuilder.put(testBundleTarget, pbxprojectPath);

    SchemeGenerator schemeGenerator =
        new SchemeGenerator(
            projectFilesystem,
            Optional.of(rootTarget),
            ImmutableSet.of(rootTarget),
            ImmutableSet.of(testBundleTarget),
            ImmutableSet.of(testBundleTarget),
            "TestScheme",
            Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
            false /* parallelizeBuild */,
            Optional.empty() /* wasCreatedForAppExtension */,
            Optional.empty() /* runnablePath */,
            Optional.empty() /* remoteRunnablePath */,
            SchemeActionType.DEFAULT_CONFIG_NAMES,
            targetToProjectPathMapBuilder.build(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            XCScheme.LaunchAction.LaunchStyle.AUTO,
            Optional.empty(), /* watchAdapter */
            Optional.empty(), /* notificationPayloadFile */
            Optional.empty(), /* applicationRegion */
            Optional.empty() /* applicationLanguage */);

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

    List<String> expectedOrdering = ImmutableList.of("rootGID", "testBundleGID");

    List<String> actualOrdering = new ArrayList<>();
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

    PBXTarget rootTarget =
        new PBXNativeTarget("rootRule", AbstractPBXObjectFactory.DefaultFactory());
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductReference(
        new PBXFileReference(
            "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR, Optional.empty()));
    rootTarget.setProductType(ProductTypes.STATIC_LIBRARY);

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(rootTarget, pbxprojectPath);
    SchemeGenerator schemeGenerator =
      new SchemeGenerator(
        projectFilesystem,
        Optional.of(rootTarget),
        ImmutableSet.of(rootTarget),
        ImmutableSet.of(),
        ImmutableSet.of(),
        "TestScheme",
        Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
        false /* parallelizeBuild */,
        Optional.empty() /* wasCreatedForAppExtension */,
        Optional.empty() /* runnablePath */,
        Optional.empty() /* remoteRunnablePath */,
        SchemeActionType.DEFAULT_CONFIG_NAMES,
        targetToProjectPathMapBuilder.build(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        XCScheme.LaunchAction.LaunchStyle.AUTO,
        Optional.empty(), /* watchAdapter */
        Optional.empty(), /* notificationPayloadFile */
        Optional.empty(), /* applicationRegion */
        Optional.empty() /* applicationLanguage */);


    Path schemePath = schemeGenerator.writeScheme();

    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
    Document scheme = dBuilder.parse(projectFilesystem.newFileInputStream(schemePath));

    XPathFactory xpathFactory = XPathFactory.newInstance();

    XPath buildableReferenceXPath = xpathFactory.newXPath();
    XPathExpression buildableReferenceExpr =
        buildableReferenceXPath.compile("//BuildableReference");
    NodeList buildableReferences =
        (NodeList) buildableReferenceExpr.evaluate(scheme, XPathConstants.NODESET);

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

    PBXTarget rootTarget =
        new PBXNativeTarget("rootRule", AbstractPBXObjectFactory.DefaultFactory());
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductReference(
        new PBXFileReference(
            "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR, Optional.empty()));
    rootTarget.setProductType(ProductTypes.STATIC_LIBRARY);

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(rootTarget, pbxprojectPath);

    SchemeGenerator schemeGenerator =
        new SchemeGenerator(
            projectFilesystem,
            Optional.of(rootTarget),
            ImmutableSet.of(rootTarget),
            ImmutableSet.of(),
            ImmutableSet.of(),
            "TestScheme",
            Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
            false /* parallelizeBuild */,
            Optional.empty() /* wasCreatedForAppExtension */,
            Optional.empty() /* runnablePath */,
            Optional.empty() /* remoteRunnablePath */,
            SchemeActionType.DEFAULT_CONFIG_NAMES,
            targetToProjectPathMapBuilder.build(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            XCScheme.LaunchAction.LaunchStyle.AUTO,
            Optional.empty(), /* watchAdapter */
            Optional.empty(), /* notificationPayloadFile */
            Optional.empty(), /* applicationRegion */
            Optional.empty() /* applicationLanguage */);

    Path schemePath = schemeGenerator.writeScheme();

    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
    Document scheme = dBuilder.parse(projectFilesystem.newFileInputStream(schemePath));

    XPathFactory xpathFactory = XPathFactory.newInstance();

    XPath schemeChildrenXPath = xpathFactory.newXPath();
    XPathExpression schemeChildrenExpr = schemeChildrenXPath.compile("/Scheme/node()");
    NodeList actions = (NodeList) schemeChildrenExpr.evaluate(scheme, XPathConstants.NODESET);

    assertThat(actions.getLength(), equalTo(6));

    Node buildAction = actions.item(0);
    assertThat(buildAction.getNodeName(), equalTo("BuildAction"));
    assertThat(buildAction.getAttributes().getNamedItem("buildConfiguration"), nullValue());

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

      PBXTarget rootTarget =
          new PBXNativeTarget("rootRule", AbstractPBXObjectFactory.DefaultFactory());
      rootTarget.setGlobalID("rootGID");
      rootTarget.setProductReference(
          new PBXFileReference(
              "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR, Optional.empty()));
      rootTarget.setProductType(ProductTypes.STATIC_LIBRARY);

      Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
      targetToProjectPathMapBuilder.put(rootTarget, pbxprojectPath);

      clock.setCurrentTimeMillis(49152);
      SchemeGenerator schemeGenerator =
          new SchemeGenerator(
              projectFilesystem,
              Optional.of(rootTarget),
              ImmutableSet.of(rootTarget),
              ImmutableSet.of(),
              ImmutableSet.of(),
              "TestScheme",
              Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
              false /* parallelizeBuild */,
              Optional.empty() /* wasCreatedForAppExtension */,
              Optional.empty() /* runnablePath */,
              Optional.empty() /* remoteRunnablePath */,
              SchemeActionType.DEFAULT_CONFIG_NAMES,
              targetToProjectPathMapBuilder.build(),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              XCScheme.LaunchAction.LaunchStyle.AUTO,
              Optional.empty(), /* watchAdapter */
              Optional.empty(), /* notificationPayloadFile */
              Optional.empty(), /* applicationRegion */
              Optional.empty() /* applicationLanguage */);

      Path schemePath = schemeGenerator.writeScheme();
      assertThat(
          projectFilesystem.getLastModifiedTime(schemePath), equalTo(FileTime.fromMillis(49152L)));
    }

    {
      PBXTarget rootTarget =
          new PBXNativeTarget("rootRule2", AbstractPBXObjectFactory.DefaultFactory());
      rootTarget.setGlobalID("root2GID");
      rootTarget.setProductReference(
          new PBXFileReference(
              "root2.a", "root2.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR, Optional.empty()));
      rootTarget.setProductType(ProductTypes.STATIC_LIBRARY);

      Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");

      clock.setCurrentTimeMillis(64738);
      SchemeGenerator schemeGenerator =
          new SchemeGenerator(
              projectFilesystem,
              Optional.of(rootTarget),
              ImmutableSet.of(rootTarget),
              ImmutableSet.of(),
              ImmutableSet.of(),
              "TestScheme",
              Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
              false /* parallelizeBuild */,
              Optional.empty() /* wasCreatedForAppExtension */,
              Optional.empty() /* runnablePath */,
              Optional.empty() /* remoteRunnablePath */,
              SchemeActionType.DEFAULT_CONFIG_NAMES,
              ImmutableMap.of(rootTarget, pbxprojectPath),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              XCScheme.LaunchAction.LaunchStyle.AUTO,
              Optional.empty(), /* watchAdapter */
              Optional.empty(), /* notificationPayloadFile */
              Optional.empty(), /* applicationRegion */
              Optional.empty() /* applicationLanguage */);

      Path schemePath = schemeGenerator.writeScheme();
      assertThat(
          projectFilesystem.getLastModifiedTime(schemePath), equalTo(FileTime.fromMillis(64738L)));
    }
  }

  @Test
  public void schemeIsNotRewrittenIfContentsHaveNotChanged() throws IOException {
    {
      PBXTarget rootTarget =
          new PBXNativeTarget("rootRule", AbstractPBXObjectFactory.DefaultFactory());
      rootTarget.setGlobalID("rootGID");
      rootTarget.setProductReference(
          new PBXFileReference(
              "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR, Optional.empty()));
      rootTarget.setProductType(ProductTypes.STATIC_LIBRARY);

      Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");

      clock.setCurrentTimeMillis(49152);
      SchemeGenerator schemeGenerator =
          new SchemeGenerator(
              projectFilesystem,
              Optional.of(rootTarget),
              ImmutableSet.of(rootTarget),
              ImmutableSet.of(),
              ImmutableSet.of(),
              "TestScheme",
              Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
              false /* parallelizeBuild */,
              Optional.empty() /* wasCreatedForAppExtension */,
              Optional.empty() /* runnablePath */,
              Optional.empty() /* remoteRunnablePath */,
              SchemeActionType.DEFAULT_CONFIG_NAMES,
              ImmutableMap.of(rootTarget, pbxprojectPath),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              XCScheme.LaunchAction.LaunchStyle.AUTO,
              Optional.empty(), /* watchAdapter */
              Optional.empty(), /* notificationPayloadFile */
              Optional.empty(), /* applicationRegion */
              Optional.empty() /* applicationLanguage */);

      Path schemePath = schemeGenerator.writeScheme();
      assertThat(
          projectFilesystem.getLastModifiedTime(schemePath), equalTo(FileTime.fromMillis(49152L)));
    }

    {
      PBXTarget rootTarget =
          new PBXNativeTarget("rootRule", AbstractPBXObjectFactory.DefaultFactory());
      rootTarget.setGlobalID("rootGID");
      rootTarget.setProductReference(
          new PBXFileReference(
              "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR, Optional.empty()));
      rootTarget.setProductType(ProductTypes.STATIC_LIBRARY);

      Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");

      clock.setCurrentTimeMillis(64738);
      SchemeGenerator schemeGenerator =
          new SchemeGenerator(
              projectFilesystem,
              Optional.of(rootTarget),
              ImmutableSet.of(rootTarget),
              ImmutableSet.of(),
              ImmutableSet.of(),
              "TestScheme",
              Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
              false /* parallelizeBuild */,
              Optional.empty() /* wasCreatedForAppExtension */,
              Optional.empty() /* runnablePath */,
              Optional.empty() /* remoteRunnablePath */,
              SchemeActionType.DEFAULT_CONFIG_NAMES,
              ImmutableMap.of(rootTarget, pbxprojectPath),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              XCScheme.LaunchAction.LaunchStyle.AUTO,
              Optional.empty(), /* watchAdapter */
              Optional.empty(), /* notificationPayloadFile */
              Optional.empty(), /* applicationRegion */
              Optional.empty() /* applicationLanguage */);
      Path schemePath = schemeGenerator.writeScheme();
      assertThat(
          projectFilesystem.getLastModifiedTime(schemePath), equalTo(FileTime.fromMillis(49152L)));
    }
  }

  @Test
  public void schemeWithNoPrimaryRuleCanIncludeTests() throws Exception {
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder = ImmutableMap.builder();

    PBXTarget testLibraryTarget =
        new PBXNativeTarget("testLibrary", AbstractPBXObjectFactory.DefaultFactory());
    testLibraryTarget.setGlobalID("testLibraryGID");
    testLibraryTarget.setProductReference(
        new PBXFileReference(
            "lib.a", "lib.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR, Optional.empty()));
    testLibraryTarget.setProductType(ProductTypes.STATIC_LIBRARY);

    PBXTarget testTarget =
        new PBXNativeTarget("testRule", AbstractPBXObjectFactory.DefaultFactory());
    testTarget.setGlobalID("testGID");
    testTarget.setProductReference(
        new PBXFileReference(
            "test.a", "test.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR, Optional.empty()));
    testTarget.setProductType(ProductTypes.STATIC_LIBRARY);

    PBXTarget testBundleTarget =
        new PBXNativeTarget("testBundleRule", AbstractPBXObjectFactory.DefaultFactory());
    testBundleTarget.setGlobalID("testBundleGID");
    testBundleTarget.setProductReference(
        new PBXFileReference(
            "test.xctest",
            "test.xctest",
            PBXReference.SourceTree.BUILT_PRODUCTS_DIR,
            Optional.empty()));
    testBundleTarget.setProductType(ProductTypes.UNIT_TEST);

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(testLibraryTarget, pbxprojectPath);
    targetToProjectPathMapBuilder.put(testTarget, pbxprojectPath);
    targetToProjectPathMapBuilder.put(testBundleTarget, pbxprojectPath);

    SchemeGenerator schemeGenerator =
        new SchemeGenerator(
            projectFilesystem,
            Optional.empty(),
            ImmutableSet.of(),
            ImmutableSet.of(testBundleTarget),
            ImmutableSet.of(testBundleTarget),
            "TestScheme",
            Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
            false /* parallelizeBuild */,
            Optional.empty() /* wasCreatedForAppExtension */,
            Optional.empty() /* runnablePath */,
            Optional.empty() /* remoteRunnablePath */,
            SchemeActionType.DEFAULT_CONFIG_NAMES,
            targetToProjectPathMapBuilder.build(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            XCScheme.LaunchAction.LaunchStyle.AUTO,
            Optional.empty(), /* watchAdapter */
            Optional.empty(), /* notificationPayloadFile */
            Optional.empty(), /* applicationRegion */
            Optional.empty() /* applicationLanguage */);

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

    List<String> expectedOrdering = ImmutableList.of("testBundleGID");

    List<String> actualOrdering = new ArrayList<>();
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

    PBXTarget rootTarget =
        new PBXNativeTarget("rootRule", AbstractPBXObjectFactory.DefaultFactory());
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductReference(
        new PBXFileReference(
            "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR, Optional.empty()));
    rootTarget.setProductType(ProductTypes.STATIC_LIBRARY);

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(rootTarget, pbxprojectPath);

    SchemeGenerator schemeGenerator =
        new SchemeGenerator(
            projectFilesystem,
            Optional.of(rootTarget),
            ImmutableSet.of(rootTarget),
            ImmutableSet.of(),
            ImmutableSet.of(),
            "TestScheme",
            Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
            false /* parallelizeBuild */,
            Optional.empty() /* wasCreatedForAppExtension */,
            Optional.empty() /* runnablePath */,
            Optional.empty() /* remoteRunnablePath */,
            SchemeActionType.DEFAULT_CONFIG_NAMES,
            targetToProjectPathMapBuilder.build(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            XCScheme.LaunchAction.LaunchStyle.AUTO,
            Optional.empty(), /* watchAdapter */
            Optional.empty(), /* notificationPayloadFile */
            Optional.empty(), /* applicationRegion */
            Optional.empty() /* applicationLanguage */);

    Path schemePath = schemeGenerator.writeScheme();

    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
    Document scheme = dBuilder.parse(projectFilesystem.newFileInputStream(schemePath));

    XPathFactory xpathFactory = XPathFactory.newInstance();

    XPath remoteRunnableLaunchActionXPath = xpathFactory.newXPath();
    XPathExpression remoteRunnableLaunchActionExpr =
        remoteRunnableLaunchActionXPath.compile("//LaunchAction/RemoteRunnable");
    NodeList remoteRunnables =
        (NodeList) remoteRunnableLaunchActionExpr.evaluate(scheme, XPathConstants.NODESET);

    assertThat(remoteRunnables.getLength(), equalTo(0));
  }

  @Test
  public void launchActionShouldContainRemoteRunnableWhenProvided() throws Exception {
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder = ImmutableMap.builder();

    PBXTarget rootTarget =
        new PBXNativeTarget("rootRule", AbstractPBXObjectFactory.DefaultFactory());
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductReference(
        new PBXFileReference(
            "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR, Optional.empty()));
    rootTarget.setProductType(ProductTypes.STATIC_LIBRARY);

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(rootTarget, pbxprojectPath);

    SchemeGenerator schemeGenerator =
        new SchemeGenerator(
            projectFilesystem,
            Optional.of(rootTarget),
            ImmutableSet.of(rootTarget),
            ImmutableSet.of(),
            ImmutableSet.of(),
            "TestScheme",
            Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
            false /* parallelizeBuild */,
            Optional.empty() /* wasCreatedForAppExtension */,
            Optional.empty() /* runnablePath */,
            Optional.of("/RemoteApp") /* remoteRunnablePath */,
            SchemeActionType.DEFAULT_CONFIG_NAMES,
            targetToProjectPathMapBuilder.build(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            XCScheme.LaunchAction.LaunchStyle.AUTO,
            Optional.empty(), /* watchAdapter */
            Optional.empty(), /* notificationPayloadFile */
            Optional.empty(), /* applicationRegion */
            Optional.empty() /* applicationLanguage */);

    Path schemePath = schemeGenerator.writeScheme();

    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
    Document scheme = dBuilder.parse(projectFilesystem.newFileInputStream(schemePath));

    XPathFactory xpathFactory = XPathFactory.newInstance();

    XPath remoteRunnableLaunchActionXPath = xpathFactory.newXPath();
    XPathExpression remoteRunnableLaunchActionExpr =
        remoteRunnableLaunchActionXPath.compile("//LaunchAction/RemoteRunnable");
    NodeList remoteRunnables =
        (NodeList) remoteRunnableLaunchActionExpr.evaluate(scheme, XPathConstants.NODESET);

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
  public void prePostActionsSerializedWithRootBuildable() throws Exception {
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder = ImmutableMap.builder();

    PBXTarget rootTarget =
        new PBXNativeTarget("rootRule", AbstractPBXObjectFactory.DefaultFactory());
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductReference(
        new PBXFileReference(
            "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR, Optional.empty()));
    rootTarget.setProductType(ProductTypes.STATIC_LIBRARY);

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(rootTarget, pbxprojectPath);

    ImmutableMap<SchemeActionType, ImmutableMap<XCScheme.AdditionalActions, ImmutableList<String>>>
        schemeActions =
            ImmutableMap.of(
                SchemeActionType.LAUNCH,
                ImmutableMap.of(
                    XCScheme.AdditionalActions.PRE_SCHEME_ACTIONS,
                    ImmutableList.of("echo takeoff")));

    SchemeGenerator schemeGenerator =
        new SchemeGenerator(
            projectFilesystem,
            Optional.of(rootTarget),
            ImmutableSet.of(rootTarget),
            ImmutableSet.of(),
            ImmutableSet.of(),
            "TestScheme",
            Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
            false /* parallelizeBuild */,
            Optional.empty() /* wasCreatedForAppExtension */,
            Optional.empty() /* runnablePath */,
            Optional.empty() /* remoteRunnablePath */,
            SchemeActionType.DEFAULT_CONFIG_NAMES,
            targetToProjectPathMapBuilder.build(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(schemeActions),
            XCScheme.LaunchAction.LaunchStyle.AUTO,
            Optional.empty(), /* watchAdapter */
            Optional.empty(), /* notificationPayloadFile */
            Optional.empty(), /* applicationRegion */
            Optional.empty() /* applicationLanguage */);

    Path schemePath = schemeGenerator.writeScheme();

    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
    Document scheme = dBuilder.parse(projectFilesystem.newFileInputStream(schemePath));

    XPathFactory xpathFactory = XPathFactory.newInstance();

    XPath preLaunchActionXPath = xpathFactory.newXPath();
    XPathExpression preLaunchActionExpr = preLaunchActionXPath.compile("//LaunchAction/PreActions");
    NodeList preActions = (NodeList) preLaunchActionExpr.evaluate(scheme, XPathConstants.NODESET);

    assertThat(preActions.getLength(), equalTo(1));

    Node executionAction = preActions.item(0).getFirstChild();
    assertThat(
        executionAction.getAttributes().getNamedItem("ActionType").getNodeValue(),
        equalTo("Xcode.IDEStandardExecutionActionsCore.ExecutionActionType.ShellScriptAction"));

    Node actionContent = executionAction.getFirstChild();
    assertThat(
        actionContent.getAttributes().getNamedItem("title").getNodeValue(), equalTo("Run Script"));
    assertThat(
        actionContent.getAttributes().getNamedItem("scriptText").getNodeValue(),
        equalTo("echo takeoff"));
    assertThat(
        actionContent.getAttributes().getNamedItem("shellToInvoke").getNodeValue(),
        equalTo("/bin/bash"));

    XPath buildXpath = xpathFactory.newXPath();
    XPathExpression buildableExpr =
        buildXpath.compile(
            "//LaunchAction//PreActions//ExecutionAction//EnvironmentBuildable//BuildableReference/@BlueprintIdentifier");
    NodeList buildableNodes = (NodeList) buildableExpr.evaluate(scheme, XPathConstants.NODESET);

    // Make sure both copies of the BuildableReference are present.
    assertThat(buildableNodes.getLength(), equalTo(1));
    assertThat(buildableNodes.item(0).getNodeValue(), equalTo("rootGID"));
  }

  @Test
  public void enablingParallelizeBuild() throws Exception {
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder = ImmutableMap.builder();

    PBXTarget rootTarget =
        new PBXNativeTarget("rootRule", AbstractPBXObjectFactory.DefaultFactory());
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductReference(
        new PBXFileReference(
            "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR, Optional.empty()));
    rootTarget.setProductType(ProductTypes.STATIC_LIBRARY);

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(rootTarget, pbxprojectPath);

    SchemeGenerator schemeGenerator =
        new SchemeGenerator(
            projectFilesystem,
            Optional.of(rootTarget),
            ImmutableSet.of(rootTarget),
            ImmutableSet.of(),
            ImmutableSet.of(),
            "TestScheme",
            Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
            true /* parallelizeBuild */,
            Optional.empty() /* wasCreatedForAppExtension */,
            Optional.empty() /* runnablePath */,
            Optional.empty() /* remoteRunnablePath */,
            SchemeActionType.DEFAULT_CONFIG_NAMES,
            targetToProjectPathMapBuilder.build(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            XCScheme.LaunchAction.LaunchStyle.AUTO,
            Optional.empty(), /* watchAdapter */
            Optional.empty(), /* notificationPayloadFile */
            Optional.empty(), /* applicationRegion */
            Optional.empty() /* applicationLanguage */);

    Path schemePath = schemeGenerator.writeScheme();

    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
    Document scheme = dBuilder.parse(projectFilesystem.newFileInputStream(schemePath));

    XPathFactory xpathFactory = XPathFactory.newInstance();

    XPath buildActionXpath = xpathFactory.newXPath();
    XPathExpression buildActionExpr = buildActionXpath.compile("//BuildAction");
    NodeList buildActionNodes = (NodeList) buildActionExpr.evaluate(scheme, XPathConstants.NODESET);

    assertThat(buildActionNodes.getLength(), is(1));

    Node buildActionNode = buildActionNodes.item(0);

    assertThat(
        buildActionNode.getAttributes().getNamedItem("buildImplicitDependencies").getNodeValue(),
        equalTo("YES"));
    assertThat(
        buildActionNode.getAttributes().getNamedItem("parallelizeBuildables").getNodeValue(),
        equalTo("YES"));
  }

  @Test
  public void serializesEnvironmentVariables() throws Exception {
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder = ImmutableMap.builder();

    PBXTarget rootTarget =
        new PBXNativeTarget("rootRule", AbstractPBXObjectFactory.DefaultFactory());
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductReference(
        new PBXFileReference(
            "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR, Optional.empty()));
    rootTarget.setProductType(ProductTypes.STATIC_LIBRARY);

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(rootTarget, pbxprojectPath);

    ImmutableMap<SchemeActionType, ImmutableMap<String, String>> environmentVariables =
        ImmutableMap.of(SchemeActionType.LAUNCH, ImmutableMap.of("ENV_VARIABLE", "IS_SET"));

    SchemeGenerator schemeGenerator =
        new SchemeGenerator(
            projectFilesystem,
            Optional.of(rootTarget),
            ImmutableSet.of(rootTarget),
            ImmutableSet.of(),
            ImmutableSet.of(),
            "TestScheme",
            Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
            true /* parallelizeBuild */,
            Optional.empty() /* wasCreatedForAppExtension */,
            Optional.empty() /* runnablePath */,
            Optional.empty() /* remoteRunnablePath */,
            SchemeActionType.DEFAULT_CONFIG_NAMES,
            targetToProjectPathMapBuilder.build(),
            Optional.of(environmentVariables),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            XCScheme.LaunchAction.LaunchStyle.AUTO,
            Optional.empty(), /* watchAdapter */
            Optional.empty(), /* notificationPayloadFile */
            Optional.empty(), /* applicationRegion */
            Optional.empty() /* applicationLanguage */);

    Path schemePath = schemeGenerator.writeScheme();

    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
    Document scheme = dBuilder.parse(projectFilesystem.newFileInputStream(schemePath));

    XPathFactory xpathFactory = XPathFactory.newInstance();
    XPath buildActionXpath = xpathFactory.newXPath();
    XPathExpression buildActionExpr =
        buildActionXpath.compile("//LaunchAction/EnvironmentVariables/EnvironmentVariable");
    NodeList envVariableList = (NodeList) buildActionExpr.evaluate(scheme, XPathConstants.NODESET);

    assertThat(envVariableList.getLength(), is(1));
    Node envVar = envVariableList.item(0);
    assertThat(envVar.getAttributes().getNamedItem("key").getNodeValue(), equalTo("ENV_VARIABLE"));
    assertThat(envVar.getAttributes().getNamedItem("value").getNodeValue(), equalTo("IS_SET"));
  }

  @Test
  public void serializesCommandLineArguments() throws Exception {
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder = ImmutableMap.builder();

    PBXTarget rootTarget =
      new PBXNativeTarget("rootRule", AbstractPBXObjectFactory.DefaultFactory());
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductReference(
      new PBXFileReference(
        "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR, Optional.empty()));
    rootTarget.setProductType(ProductTypes.STATIC_LIBRARY);

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(rootTarget, pbxprojectPath);

    ImmutableMap<SchemeActionType, ImmutableMap<String, String>> commandLineArguments =
      ImmutableMap.of(SchemeActionType.LAUNCH, ImmutableMap.of("COMMAND_ARG", "YES"));

    SchemeGenerator schemeGenerator =
      new SchemeGenerator(
        projectFilesystem,
        Optional.of(rootTarget),
        ImmutableSet.of(rootTarget),
        ImmutableSet.of(),
        ImmutableSet.of(),
        "TestScheme",
        Paths.get("_gen/Foo.xcworkspace/xcshareddata/xcshemes"),
        true /* parallelizeBuild */,
        Optional.of(true) /* wasCreatedForAppExtension */,
        Optional.empty() /* runnablePath */,
        Optional.empty() /* remoteRunnablePath */,
        SchemeActionType.DEFAULT_CONFIG_NAMES,
        targetToProjectPathMapBuilder.build(),
        Optional.empty(),
        Optional.empty(),
        Optional.of(commandLineArguments), /* commandLineArguments */
        Optional.empty(),
        XCScheme.LaunchAction.LaunchStyle.AUTO,
        Optional.empty(), /* watchAdapter */
        Optional.empty(), /* notificationPayloadFile */
        Optional.empty(), /* applicationRegion */
        Optional.empty() /* applicationLanguage */);

    Path schemePath = schemeGenerator.writeScheme();

    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
    Document scheme = dBuilder.parse(projectFilesystem.newFileInputStream(schemePath));

    XPathFactory xpathFactory = XPathFactory.newInstance();
    XPath buildActionXpath = xpathFactory.newXPath();
    XPathExpression buildActionExpr =
      buildActionXpath.compile("//LaunchAction/CommandLineArguments/CommandLineArgument");
    NodeList commandLineArgsList = (NodeList) buildActionExpr.evaluate(scheme, XPathConstants.NODESET);

    assertThat(commandLineArgsList.getLength(), is(1));
    Node commandLineArgument = commandLineArgsList.item(0);
    assertThat(commandLineArgument.getAttributes().getNamedItem("argument").getNodeValue(), equalTo("COMMAND_ARG"));
    assertThat(commandLineArgument.getAttributes().getNamedItem("isEnabled").getNodeValue(), equalTo("YES"));
  }

  @Test
  public void serializesRegionAndLanguage() throws Exception {
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder = ImmutableMap.builder();

    PBXTarget rootTarget =
      new PBXNativeTarget("rootRule", AbstractPBXObjectFactory.DefaultFactory());
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductReference(
      new PBXFileReference(
        "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR, Optional.empty()));
    rootTarget.setProductType(ProductTypes.STATIC_LIBRARY);

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(rootTarget, pbxprojectPath);

    String region = "pt-BR";
    String language = "pt";

    SchemeGenerator schemeGenerator =
      new SchemeGenerator(
        projectFilesystem,
        Optional.of(rootTarget),
        ImmutableSet.of(rootTarget),
        ImmutableSet.of(),
        ImmutableSet.of(),
        "TestScheme",
        Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"),
        true /* parallelizeBuild */,
        Optional.empty() /* wasCreatedForAppExtension */,
        Optional.empty() /* runnablePath */,
        Optional.empty() /* remoteRunnablePath */,
        SchemeActionType.DEFAULT_CONFIG_NAMES,
        targetToProjectPathMapBuilder.build(),
        Optional.empty() /* environmentVariables */,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        XCScheme.LaunchAction.LaunchStyle.AUTO,
        Optional.empty(), /* watchAdapter */
        Optional.empty(), /* notificationPayloadFile */
        Optional.of(region),
        Optional.of(language));

    Path schemePath = schemeGenerator.writeScheme();

    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
    Document scheme = dBuilder.parse(projectFilesystem.newFileInputStream(schemePath));

    XPathFactory xpathFactory = XPathFactory.newInstance();
    XPath buildActionXpath = xpathFactory.newXPath();

    XPathExpression launchActionExpr = buildActionXpath.compile("//LaunchAction");
    XPathExpression testActionExpr = buildActionXpath.compile("//TestAction");

    Node launchActionNode = (Node) launchActionExpr.evaluate(scheme, XPathConstants.NODE);
    Node testActionNode = (Node) testActionExpr.evaluate(scheme, XPathConstants.NODE);

    assertThat(launchActionNode.getAttributes().getNamedItem("language").getNodeValue(), equalTo("pt-BR"));
    assertThat(launchActionNode.getAttributes().getNamedItem("region").getNodeValue(), equalTo("pt"));

    assertThat(testActionNode.getAttributes().getNamedItem("language").getNodeValue(), equalTo("pt-BR"));
    assertThat(testActionNode.getAttributes().getNamedItem("region").getNodeValue(), equalTo("pt"));
  }

  /**
   * Include `wasCreatedForAppExtension` when true.
   *
   * @throws Exception
   */
  @Test
  public void serializesWasCreatedForAppExtension() throws Exception {
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder = ImmutableMap.builder();

    PBXTarget rootTarget =
        new PBXNativeTarget("rootRule", AbstractPBXObjectFactory.DefaultFactory());
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductReference(
        new PBXFileReference(
            "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR, Optional.empty()));
    rootTarget.setProductType(ProductTypes.STATIC_LIBRARY);

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(rootTarget, pbxprojectPath);

    SchemeGenerator schemeGenerator =
        new SchemeGenerator(
            projectFilesystem,
            Optional.of(rootTarget),
            ImmutableSet.of(rootTarget),
            ImmutableSet.of(),
            ImmutableSet.of(),
            "TestScheme",
            Paths.get("_gen/Foo.xcworkspace/xcshareddata/xcshemes"),
            true /* parallelizeBuild */,
            Optional.of(true) /* wasCreatedForAppExtension */,
            Optional.empty() /* runnablePath */,
            Optional.empty() /* remoteRunnablePath */,
            SchemeActionType.DEFAULT_CONFIG_NAMES,
            targetToProjectPathMapBuilder.build(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            XCScheme.LaunchAction.LaunchStyle.AUTO,
            Optional.empty(), /* watchAdapter */
            Optional.empty(), /* notificationPayloadFile */
            Optional.empty(), /* applicationRegion */
            Optional.empty() /* applicationLanguage */);

    Path schemePath = schemeGenerator.writeScheme();

    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
    Document scheme = dBuilder.parse(projectFilesystem.newFileInputStream(schemePath));

    XPathFactory xpathFactory = XPathFactory.newInstance();
    XPath buildActionXpath = xpathFactory.newXPath();
    XPathExpression buildActionExpr = buildActionXpath.compile("//Scheme");
    NodeList schemeElements = (NodeList) buildActionExpr.evaluate(scheme, XPathConstants.NODESET);

    assertThat(schemeElements.getLength(), is(1));
    Node schemeNode = schemeElements.item(0);
    assertThat(
        schemeNode.getAttributes().getNamedItem("wasCreatedForAppExtension").getNodeValue(),
        equalTo("YES"));
  }

  /**
   * Exclude `wasCreatedForAppExtension` when null or false.
   *
   * @throws Exception
   */
  @Test
  public void excludesWasCreatedForAppExtension() throws Exception {
    ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder = ImmutableMap.builder();

    PBXTarget rootTarget =
        new PBXNativeTarget("rootRule", AbstractPBXObjectFactory.DefaultFactory());
    rootTarget.setGlobalID("rootGID");
    rootTarget.setProductReference(
        new PBXFileReference(
            "root.a", "root.a", PBXReference.SourceTree.BUILT_PRODUCTS_DIR, Optional.empty()));
    rootTarget.setProductType(ProductTypes.STATIC_LIBRARY);

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    targetToProjectPathMapBuilder.put(rootTarget, pbxprojectPath);

    ImmutableList<Optional<Boolean>> testValues =
        ImmutableList.of(Optional.empty(), Optional.of(false));

    for (Optional<Boolean> wasCreatedForAppExtension : testValues) {
      SchemeGenerator schemeGenerator =
          new SchemeGenerator(
              projectFilesystem,
              Optional.of(rootTarget),
              ImmutableSet.of(rootTarget),
              ImmutableSet.of(),
              ImmutableSet.of(),
              "TestScheme",
              Paths.get("_gen/Foo.xcworkspace/xcshareddata/xcshemes"),
              true /* parallelizeBuild */,
              wasCreatedForAppExtension,
              Optional.empty() /* runnablePath */,
              Optional.empty() /* remoteRunnablePath */,
              SchemeActionType.DEFAULT_CONFIG_NAMES,
              targetToProjectPathMapBuilder.build(),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              XCScheme.LaunchAction.LaunchStyle.AUTO,
              Optional.empty(), /* watchAdapter */
              Optional.empty(), /* notificationPayloadFile */
              Optional.empty(), /* applicationRegion */
              Optional.empty() /* applicationLanguage */);

      Path schemePath = schemeGenerator.writeScheme();

      DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
      DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
      Document scheme = dBuilder.parse(projectFilesystem.newFileInputStream(schemePath));

      XPathFactory xpathFactory = XPathFactory.newInstance();
      XPath buildActionXpath = xpathFactory.newXPath();
      XPathExpression buildActionExpr = buildActionXpath.compile("//Scheme");
      NodeList schemeElements = (NodeList) buildActionExpr.evaluate(scheme, XPathConstants.NODESET);

      assertThat(schemeElements.getLength(), is(1));
      Node schemeNode = schemeElements.item(0);
      assertNull(schemeNode.getAttributes().getNamedItem("wasCreatedForAppExtension"));
    }
  }
}
