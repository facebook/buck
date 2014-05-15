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
import static org.junit.Assert.assertThat;

import com.facebook.buck.apple.IosLibraryDescription;
import com.facebook.buck.apple.IosTestDescription;
import com.facebook.buck.apple.xcode.xcodeproj.PBXNativeTarget;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;

import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
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

  private ProjectFilesystem projectFilesystem;
  private IosLibraryDescription iosLibraryDescription;
  private IosTestDescription iosTestDescription;

  @Before
  public void setUp() throws IOException {
    projectFilesystem = new FakeProjectFilesystem();
    iosLibraryDescription = new IosLibraryDescription();
    iosTestDescription = new IosTestDescription();
  }

  @Test
  public void schemeWithMultipleTargetsBuildsInCorrectOrder() throws Exception {
    BuildRule rootRule = createBuildRuleWithDefaults(
        new BuildTarget("//foo", "root"),
        ImmutableSortedSet.<BuildRule>of(),
        iosLibraryDescription);
    BuildRule leftRule = createBuildRuleWithDefaults(
        new BuildTarget("//foo", "left"),
        ImmutableSortedSet.of(rootRule),
        iosLibraryDescription);
    BuildRule rightRule = createBuildRuleWithDefaults(
        new BuildTarget("//foo", "right"),
        ImmutableSortedSet.of(rootRule),
        iosLibraryDescription);
    BuildRule childRule = createBuildRuleWithDefaults(
        new BuildTarget("//foo", "child"),
        ImmutableSortedSet.of(leftRule, rightRule),
        iosLibraryDescription);

    PartialGraph partialGraph = createPartialGraphFromBuildRules(
        ImmutableSet.<BuildRule>of(
            rootRule,
            leftRule,
            rightRule,
            childRule));

    SchemeGenerator schemeGenerator = new SchemeGenerator(
        projectFilesystem,
        partialGraph,
        rootRule,
        ImmutableSet.of(rootRule, leftRule, rightRule, childRule),
        "TestScheme",
        Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"));

    PBXTarget rootTarget = new PBXNativeTarget("rootRule");
    rootTarget.setGlobalID("rootGID");
    schemeGenerator.addRuleToTargetMap(rootRule, rootTarget);
    PBXTarget leftTarget = new PBXNativeTarget("leftRule");
    leftTarget.setGlobalID("leftGID");
    schemeGenerator.addRuleToTargetMap(leftRule, leftTarget);
    PBXTarget rightTarget = new PBXNativeTarget("rightRule");
    rightTarget.setGlobalID("rightGID");
    schemeGenerator.addRuleToTargetMap(rightRule, rightTarget);
    PBXTarget childTarget = new PBXNativeTarget("childRule");
    childTarget.setGlobalID("childGID");
    schemeGenerator.addRuleToTargetMap(childRule, childTarget);

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    schemeGenerator.addTargetToProjectPathMap(rootTarget, pbxprojectPath);
    schemeGenerator.addTargetToProjectPathMap(leftTarget, pbxprojectPath);
    schemeGenerator.addTargetToProjectPathMap(rightTarget, pbxprojectPath);
    schemeGenerator.addTargetToProjectPathMap(childTarget, pbxprojectPath);

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

  @Test
  public void schemeIncludesAllExpectedActions() throws Exception {
    BuildRule rootRule = createBuildRuleWithDefaults(
        new BuildTarget("//foo", "root"),
        ImmutableSortedSet.<BuildRule>of(),
        iosLibraryDescription);
    BuildRule testRule = createBuildRuleWithDefaults(
        new BuildTarget("//foo", "test"),
        ImmutableSortedSet.of(rootRule),
        iosTestDescription);

    PartialGraph partialGraph = createPartialGraphFromBuildRules(
        ImmutableSet.<BuildRule>of(
            rootRule,
            testRule));

    SchemeGenerator schemeGenerator = new SchemeGenerator(
        projectFilesystem,
        partialGraph,
        rootRule,
        ImmutableSet.of(rootRule, testRule),
        "TestScheme",
        Paths.get("_gen/Foo.xcworkspace/scshareddata/xcshemes"));

    PBXTarget rootTarget = new PBXNativeTarget("rootRule");
    rootTarget.setGlobalID("rootGID");
    schemeGenerator.addRuleToTargetMap(rootRule, rootTarget);
    PBXTarget testTarget = new PBXNativeTarget("testRule");
    testTarget.setGlobalID("testGID");
    schemeGenerator.addRuleToTargetMap(testRule, testTarget);

    Path pbxprojectPath = Paths.get("foo/Foo.xcodeproj/project.pbxproj");
    schemeGenerator.addTargetToProjectPathMap(rootTarget, pbxprojectPath);
    schemeGenerator.addTargetToProjectPathMap(testTarget, pbxprojectPath);

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
}
