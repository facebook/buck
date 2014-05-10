/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.apple.AppleBuildRules;
import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.common.collect.Maps;

import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.EnumSet;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * Collects target references and generates an xcscheme.
 *
 * To register entries in the scheme, clients must add:
 * <ul>
 * <li>associations between buck rules and Xcode targets</li>
 * <li>associations between Xcode targets and the projects that contain them</li>
 * </ul>
 * <p>
 * Both of these values can be pulled out of {@class ProjectGenerator}.
 */
class SchemeGenerator {
  private final ProjectFilesystem projectFilesystem;
  private final PartialGraph partialGraph;
  private final BuildRule primaryRule;
  private final ImmutableSet<BuildRule> includedRules;
  private final String schemeName;
  private final Path outputDirectory;
  private final ImmutableMap.Builder<BuildRule, PBXTarget> buildRuleToTargetMapBuilder;
  private final ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder;

  public SchemeGenerator(
      ProjectFilesystem projectFilesystem,
      PartialGraph partialGraph,
      BuildRule primaryRule,
      ImmutableSet<BuildRule> includedRules,
      String schemeName,
      Path outputDirectory) {
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
    this.partialGraph = Preconditions.checkNotNull(partialGraph);
    this.primaryRule = Preconditions.checkNotNull(primaryRule);
    this.includedRules = Preconditions.checkNotNull(includedRules);
    this.schemeName = Preconditions.checkNotNull(schemeName);
    this.outputDirectory = Preconditions.checkNotNull(outputDirectory);
    buildRuleToTargetMapBuilder = ImmutableMap.builder();
    targetToProjectPathMapBuilder = ImmutableMap.builder();
  }

  public void addRuleToTargetMap(Map<BuildRule, PBXTarget> ruleToTargetMap) {
    buildRuleToTargetMapBuilder.putAll(ruleToTargetMap);
  }

  public void addRuleToTargetMap(BuildRule rule, PBXTarget target) {
    buildRuleToTargetMapBuilder.put(rule, target);
  }

  public void addTargetToProjectPathMap(Map<PBXTarget, Path> targetToProjectPathMap) {
    targetToProjectPathMapBuilder.putAll(targetToProjectPathMap);
  }

  public void addTargetToProjectPathMap(PBXTarget target, Path projectPath) {
    targetToProjectPathMapBuilder.put(target, projectPath);
  }

  public Path writeScheme() throws IOException {
    final ImmutableMap<BuildRule, PBXTarget> buildRuleToTargetMap =
        buildRuleToTargetMapBuilder.build();
    ImmutableMap<PBXTarget, Path> targetToProjectPathMap =
        targetToProjectPathMapBuilder.build();

    List<BuildRule> orderedBuildRules = TopologicalSort.sort(
        partialGraph.getActionGraph(),
        new Predicate<BuildRule>() {
          @Override
          public boolean apply(BuildRule input) {
            return buildRuleToTargetMap.containsKey(input) && includedRules.contains(input);
          }
        });

    Set<BuildRule> nonTestRules = Sets.newLinkedHashSet();
    Set<BuildRule> testRules = Sets.newLinkedHashSet();
    Map<BuildRule, XCScheme.BuildableReference>
        buildRuleToBuildableReferenceMap = Maps.newHashMap();

    for (BuildRule rule : orderedBuildRules) {
      if (AppleBuildRules.isXcodeTargetTestBuildRuleType(rule.getType())) {
        testRules.add(rule);
      } else {
        nonTestRules.add(rule);
      }

      XCScheme.BuildableReference buildableReference = new XCScheme.BuildableReference(
          outputDirectory.getParent().relativize(
              targetToProjectPathMap.get(
                  buildRuleToTargetMap.get(rule))
          ).toString(),
          buildRuleToTargetMap.get(rule).getGlobalID());
      buildRuleToBuildableReferenceMap.put(rule, buildableReference);
    }

    XCScheme.BuildAction buildAction = new XCScheme.BuildAction();

    // For aesthetic reasons put all non-test build actions before all test build actions.
    for (BuildRule rule : Iterables.concat(nonTestRules, testRules)) {
      EnumSet<XCScheme.BuildActionEntry.BuildFor> buildFor;
      if (AppleBuildRules.isXcodeTargetTestBuildRuleType(rule.getType())) {
        buildFor = XCScheme.BuildActionEntry.BuildFor.TEST_ONLY;
      } else {
        buildFor = XCScheme.BuildActionEntry.BuildFor.DEFAULT;
      }

      XCScheme.BuildableReference buildableReference = buildRuleToBuildableReferenceMap.get(rule);
      XCScheme.BuildActionEntry entry = new XCScheme.BuildActionEntry(
          buildableReference,
          buildFor);
      buildAction.addBuildAction(entry);
    }

    XCScheme.TestAction testAction = new XCScheme.TestAction();
    for (BuildRule rule : testRules) {
      XCScheme.BuildableReference buildableReference = buildRuleToBuildableReferenceMap.get(rule);
      XCScheme.TestableReference testableReference =
          new XCScheme.TestableReference(buildableReference);
      testAction.addTestableReference(testableReference);
    }

    XCScheme.BuildableReference primaryBuildableReference =
        buildRuleToBuildableReferenceMap.get(primaryRule);
    XCScheme.LaunchAction launchAction = new XCScheme.LaunchAction(primaryBuildableReference);
    XCScheme.ProfileAction profileAction = new XCScheme.ProfileAction(primaryBuildableReference);

    XCScheme scheme = new XCScheme(
        schemeName,
        buildAction,
        testAction,
        launchAction,
        profileAction);

    Path schemeDirectory = outputDirectory.resolve("xcshareddata/xcschemes");
    projectFilesystem.mkdirs(schemeDirectory);
    Path schemePath = schemeDirectory.resolve(schemeName + ".xcscheme");
    try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
      serializeScheme(scheme, outputStream);
      projectFilesystem.writeContentsToPath(outputStream.toString(), schemePath);
    }
    return schemePath;
  }

  public static Element serializeBuildableReference(
      Document doc,
      XCScheme.BuildableReference buildableReference) {
    Element refElem = doc.createElement("BuildableReference");
    refElem.setAttribute("BuildableIdentifier", "primary");
    refElem.setAttribute("BlueprintIdentifier", buildableReference.getBlueprintIdentifier());
    String referencedContainer = "container:" + buildableReference.getContainerRelativePath();
    refElem.setAttribute("referencedContainer", referencedContainer);
    return refElem;
  }

  public static Element serializeBuildAction(Document doc, XCScheme.BuildAction buildAction) {
    Element buildActionElem = doc.createElement("BuildAction");
    buildActionElem.setAttribute("parallelizeBuildables", "NO");
    buildActionElem.setAttribute("buildImplicitDependencies", "NO");

    Element buildActionEntriesElem = doc.createElement("BuildActionEntries");
    buildActionElem.appendChild(buildActionEntriesElem);

    for (XCScheme.BuildActionEntry entry : buildAction.getBuildActionEntries()) {
      Element entryElem = doc.createElement("BuildActionEntry");
      buildActionEntriesElem.appendChild(entryElem);

      EnumSet<XCScheme.BuildActionEntry.BuildFor> buildFor = entry.getBuildFor();
      boolean buildForRunning = buildFor.contains(XCScheme.BuildActionEntry.BuildFor.RUNNING);
      entryElem.setAttribute("buildForRunning", buildForRunning ? "YES" : "NO");
      boolean buildForTesting = buildFor.contains(XCScheme.BuildActionEntry.BuildFor.TESTING);
      entryElem.setAttribute("buildForTesting", buildForTesting ? "YES" : "NO");
      boolean buildForProfiling = buildFor.contains(XCScheme.BuildActionEntry.BuildFor.PROFILING);
      entryElem.setAttribute("buildForProfiling", buildForProfiling ? "YES" : "NO");
      boolean buildForArchiving = buildFor.contains(XCScheme.BuildActionEntry.BuildFor.ARCHIVING);
      entryElem.setAttribute("buildForArchiving", buildForArchiving ? "YES" : "NO");
      boolean buildForAnalyzing = buildFor.contains(XCScheme.BuildActionEntry.BuildFor.ANALYZING);
      entryElem.setAttribute("buildForAnalyzing", buildForAnalyzing ? "YES" : "NO");

      Element refElem = serializeBuildableReference(doc, entry.getBuildableReference());
      entryElem.appendChild(refElem);
    }

    return buildActionElem;
  }

  public static Element serializeTestAction(Document doc, XCScheme.TestAction testAction) {
    Element testActionElem = doc.createElement("TestAction");
    testActionElem.setAttribute("shouldUseLaunchSchemeArgsEnv", "YES");

    Element testablesElem = doc.createElement("Testables");
    testActionElem.appendChild(testablesElem);

    for (XCScheme.TestableReference testable : testAction.getTestables()) {
      Element testableElem = doc.createElement("TestableReference");
      testablesElem.appendChild(testableElem);
      testableElem.setAttribute("skipped", "NO");

      Element refElem = serializeBuildableReference(doc, testable.getBuildableReference());
      testableElem.appendChild(refElem);
    }

    return testActionElem;
  }

  public static Element serializeLaunchAction(Document doc, XCScheme.LaunchAction launchAction) {
    Element launchActionElem = doc.createElement("LaunchAction");

    Element productRunnableElem = doc.createElement("BuildableProductRunnable");
    launchActionElem.appendChild(productRunnableElem);

    Element refElem = serializeBuildableReference(doc, launchAction.getBuildableReference());
    productRunnableElem.appendChild(refElem);

    return launchActionElem;
  }

  public static Element serializeProfileAction(Document doc, XCScheme.ProfileAction profileAction) {
    Element profileActionElem = doc.createElement("ProfileAction");

    Element productRunnableElem = doc.createElement("BuildableProductRunnable");
    profileActionElem.appendChild(productRunnableElem);

    Element refElem = serializeBuildableReference(doc, profileAction.getBuildableReference());
    productRunnableElem.appendChild(refElem);

    return profileActionElem;
  }

  private static void serializeScheme(XCScheme scheme, OutputStream stream) {
    DocumentBuilder docBuilder;
    Transformer transformer;
    try {
      docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
      transformer = TransformerFactory.newInstance().newTransformer();
    } catch (ParserConfigurationException | TransformerConfigurationException e) {
      throw new RuntimeException(e);
    }

    DOMImplementation domImplementation = docBuilder.getDOMImplementation();
    Document doc = domImplementation.createDocument(null, "Scheme", null);
    doc.setXmlVersion("1.0");

    Element rootElem = doc.getDocumentElement();
    rootElem.setAttribute("LastUpgradeVersion", "0500");
    rootElem.setAttribute("version", "1.7");

    Element buildActionElem = serializeBuildAction(doc, scheme.getBuildAction());
    rootElem.appendChild(buildActionElem);

    Element testActionElem = serializeTestAction(doc, scheme.getTestAction());
    rootElem.appendChild(testActionElem);

    Element launchActionElem = serializeLaunchAction(doc, scheme.getLaunchAction());
    rootElem.appendChild(launchActionElem);

    Element profileActionElem = serializeProfileAction(doc, scheme.getProfileAction());
    rootElem.appendChild(profileActionElem);

    // write out

    DOMSource source = new DOMSource(doc);
    StreamResult result = new StreamResult(stream);

    try {
      transformer.transform(source, result);
    } catch (TransformerException e) {
      throw new RuntimeException(e);
    }
  }
}
