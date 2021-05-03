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

package com.facebook.buck.features.project.intellij.moduleinfo;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** A command line tool to update IntelliJ binary index for changed IML files */
public class UpdateBinaryIndexFromIMLListTool {

  private static final String IML_SUFFIX = ".iml";
  private static final String EXCLUDE_KEY = "excludeFolder";
  private static final String INCLUDE_KEY = "includeFolder";

  /** Entry point */
  public static void main(String[] args) throws IOException {
    if (args.length != 2) {
      System.err.println(
          "Usage: update-binary-index-from-iml-list-tool <path/to/iml-file-list> <path/to/.idea>");
      System.exit(1);
    }

    final ModuleInfoBinaryIndex index = new ModuleInfoBinaryIndex(Paths.get(args[1]));
    index.update(moduleInfosFromPath(Paths.get(args[0])));
  }

  private static ImmutableSet<ModuleInfo> moduleInfosFromPath(@Nonnull Path path) {
    try (final Stream<String> lineStream = Files.lines(path, StandardCharsets.UTF_8)) {
      return lineStream
          .parallel()
          .map(File::new)
          .map(UpdateBinaryIndexFromIMLListTool::moduleInfoFromFile)
          .collect(ImmutableSet.toImmutableSet());
    } catch (IOException e) {
      throw new RuntimeException("Could not read from path: " + path, e);
    }
  }

  private static ModuleInfo moduleInfoFromFile(@Nonnull File imlFile) {
    final String fileName = imlFile.getName();
    Preconditions.checkArgument(fileName.endsWith(IML_SUFFIX), "Only .iml files can be processed");
    final String moduleName = fileName.substring(0, fileName.length() - IML_SUFFIX.length());
    final Document IMLDocument = getIMLDocument(imlFile);

    return ModuleInfo.of(
        moduleName,
        imlFile.getParent(), // modulePath
        dependenciesFromDocument(IMLDocument),
        contentInfosFromDocument(IMLDocument));
  }

  private static Document getIMLDocument(@Nonnull File imlFile) {
    try {
      return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(imlFile);
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new RuntimeException("Could not parse IML file: " + imlFile.getName(), e);
    }
  }

  private static ImmutableList<ContentRootInfo> contentInfosFromDocument(
      @Nonnull Document document) {
    return streamFromNodeList(document.getElementsByTagName("content"))
        .map(UpdateBinaryIndexFromIMLListTool::contentInfoFromNode)
        .collect(ImmutableList.toImmutableList());
  }

  private static ContentRootInfo contentInfoFromNode(@Nonnull Node node) {
    final String contentUrl = extractAttributeNamed(node, "url");
    final ImmutableMap<String, ImmutableList.Builder<String>> builderMap =
        ImmutableMap.of(EXCLUDE_KEY, ImmutableList.builder(), INCLUDE_KEY, ImmutableList.builder());
    streamFromNodeList(node.getChildNodes())
        .forEach(child -> processContentNode(child, contentUrl, builderMap));

    return ContentRootInfo.of(
        contentUrl, builderMap.get(INCLUDE_KEY).build(), builderMap.get(EXCLUDE_KEY).build());
  }

  private static void processContentNode(
      @Nonnull Node node,
      @Nonnull String contentUrl,
      @Nonnull Map<String, ImmutableList.Builder<String>> builders) {
    Optional.ofNullable(builders.get(node.getNodeName()))
        .ifPresent(builder -> builder.add(normalizeContentPath(contentUrl, node)));
  }

  private static String normalizeContentPath(@Nonnull String contentUrl, @Nonnull Node childNode) {
    final String childUrl = extractAttributeNamed(childNode, "url");
    return ModuleInfoBinaryIndex.extractRelativeFolderUrl(contentUrl, childUrl);
  }

  private static ImmutableList<String> dependenciesFromDocument(@Nonnull Document document) {
    return streamFromNodeList(document.getElementsByTagName("orderEntry"))
        .filter(UpdateBinaryIndexFromIMLListTool::isModule)
        .map(node -> extractAttributeNamed(node, "module-name"))
        .collect(ImmutableList.toImmutableList());
  }

  private static String extractAttributeNamed(@Nonnull Node node, @Nonnull String name) {
    return Optional.of(node)
        .map(Node::getAttributes)
        .map(attributes -> attributes.getNamedItem(name))
        .map(Node::getNodeValue)
        .orElse("");
  }

  private static boolean isModule(@Nonnull Node node) {
    return "module".equals(extractAttributeNamed(node, "type"));
  }

  private static Stream<Node> streamFromNodeList(@Nonnull NodeList list) {
    return IntStream.range(0, list.getLength()).mapToObj(list::item);
  }
}
