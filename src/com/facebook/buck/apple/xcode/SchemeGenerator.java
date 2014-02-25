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

import com.facebook.buck.apple.xcode.xcodeproj.PBXTarget;
import com.facebook.buck.graph.TopologicalSort;
import com.facebook.buck.parser.PartialGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Predicate;

import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
 * Functions for creating and writing XCSchemes.
 */
final class SchemeGenerator {

  /**
   * Utility class should not be instantiated.
   */
  private SchemeGenerator() {}

  /**
   * Serialize and write a scheme into a project bundle.
   *
   * @param projectFilesystem Filesystem abstraction that will handle the writes.
   * @param scheme            Scheme to write.
   * @param projectPath       Path to the {@code .xcodeproj} bundle to write the scheme.
   * @throws IOException
   */
  public static void writeScheme(
      ProjectFilesystem projectFilesystem,
      XCScheme scheme,
      Path projectPath) throws IOException {
    Path schemeDirectory = projectPath.resolve("xcshareddata/xcschemes");
    projectFilesystem.mkdirs(schemeDirectory);
    Path schemePath = schemeDirectory.resolve(scheme.getName() + ".xcscheme");
    try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
      serializeScheme(scheme, outputStream);
      projectFilesystem.writeContentsToPath(outputStream.toString(), schemePath);
    }
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
