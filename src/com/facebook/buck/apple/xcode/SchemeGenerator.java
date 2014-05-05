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
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
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
  private final String schemeName;
  private final Path outputDirectory;
  private final ImmutableMap.Builder<BuildRule, PBXTarget> buildRuleToTargetMapBuilder;
  private final ImmutableMap.Builder<PBXTarget, Path> targetToProjectPathMapBuilder;

  public SchemeGenerator(
      ProjectFilesystem projectFilesystem,
      PartialGraph partialGraph,
      String schemeName,
      Path outputDirectory) {
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
    this.partialGraph = Preconditions.checkNotNull(partialGraph);
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
    XCScheme scheme = new XCScheme(schemeName);

    final ImmutableMap<BuildRule, PBXTarget> buildRuleToTargetMap =
        buildRuleToTargetMapBuilder.build();
    ImmutableMap<PBXTarget, Path> targetToProjectPathMap =
        targetToProjectPathMapBuilder.build();

    List<BuildRule> orderedBuildRules = TopologicalSort.sort(
        partialGraph.getDependencyGraph(),
        new Predicate<BuildRule>() {
          @Override
          public boolean apply(BuildRule input) {
            return buildRuleToTargetMap.containsKey(input);
          }
        });

    Set<BuildRule> nonTestRules = Sets.newLinkedHashSet();
    Set<BuildRule> testRules = Sets.newLinkedHashSet();

    for (BuildRule rule : orderedBuildRules) {
      if (AppleBuildRules.isXcodeTargetTestBuildRuleType(rule.getType())) {
        testRules.add(rule);
      } else {
        nonTestRules.add(rule);
      }
    }

    // For aesthetic reasons put all non-test build actions before all test build actions.
    for (BuildRule rule : Iterables.concat(nonTestRules, testRules)) {
      scheme.addBuildAction(
          outputDirectory.getParent().relativize(
              targetToProjectPathMap.get(
                  buildRuleToTargetMap.get(rule))
          ).toString(),
          buildRuleToTargetMap.get(rule).getGlobalID());
    }

    Path schemeDirectory = outputDirectory.resolve("xcshareddata/xcschemes");
    projectFilesystem.mkdirs(schemeDirectory);
    Path schemePath = schemeDirectory.resolve(schemeName + ".xcscheme");
    try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
      serializeScheme(scheme, outputStream);
      projectFilesystem.writeContentsToPath(outputStream.toString(), schemePath);
    }
    return schemePath;
  }

  /**
   * Generate a scheme from the given rules.
   *
   * The rules are topologically sorted based on the dependencies in the BuildRule keys.
   */
  public static XCScheme createScheme(
      PartialGraph partialGraph,
      Path projectPath,
      final Map<BuildRule, PBXTarget> ruleToTargetMap) {

    List<BuildRule> orderedBuildRules = TopologicalSort.sort(
        partialGraph.getDependencyGraph(),
        new Predicate<BuildRule>() {
          @Override
          public boolean apply(@Nullable BuildRule input) {
            return ruleToTargetMap.containsKey(input);
          }
        });

    XCScheme scheme = new XCScheme("Scheme");
    for (BuildRule rule : orderedBuildRules) {
      scheme.addBuildAction(
          projectPath.getFileName().toString(),
          ruleToTargetMap.get(rule).getGlobalID());
    }

    return scheme;
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

    // serialize the scheme
    Element buildActionElem = doc.createElement("BuildAction");
    rootElem.appendChild(buildActionElem);
    buildActionElem.setAttribute("parallelizeBuildables", "NO");
    buildActionElem.setAttribute("buildImplicitDependencies", "NO");

    Element buildActionEntriesElem = doc.createElement("BuildActionEntries");
    buildActionElem.appendChild(buildActionEntriesElem);

    for (XCScheme.BuildActionEntry entry : scheme.getBuildAction()) {
      Element entryElem = doc.createElement("BuildActionEntry");
      buildActionEntriesElem.appendChild(entryElem);
      entryElem.setAttribute("buildForRunning", "YES");
      entryElem.setAttribute("buildForTesting", "YES");
      entryElem.setAttribute("buildForProfiling", "YES");
      entryElem.setAttribute("buildForArchiving", "YES");
      entryElem.setAttribute("buildForAnalyzing", "YES");
      Element refElem = doc.createElement("BuildableReference");
      entryElem.appendChild(refElem);
      refElem.setAttribute("BuildableIdentifier", "primary");
      refElem.setAttribute("BlueprintIdentifier", entry.getBlueprintIdentifier());
      refElem.setAttribute("referencedContainer", "container:" + entry.getContainerRelativePath());
    }

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
