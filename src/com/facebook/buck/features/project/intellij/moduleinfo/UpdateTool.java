/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** A command line tool to update IntelliJ binary index for changed IML files */
public class UpdateTool {

  @Option(name = "--idea-dir", required = true)
  private String ideaDir;

  // File with a list of module iml files to update the index with
  @Option(
      name = "--update-list",
      forbids = {"--generate"})
  private String updateImlFileList;

  // Write a complete binary index from modules in the given .idea directory
  @Option(
      name = "--generate",
      forbids = {"--update-list"})
  private boolean generate;

  private static final String IML_SUFFIX = ".iml";
  private static final String EXCLUDE_KEY = "excludeFolder";
  private static final String INCLUDE_KEY = "sourceFolder";

  /** Entry point */
  public static void main(String[] args) throws IOException {
    final UpdateTool main = new UpdateTool();
    final CmdLineParser parser = new CmdLineParser(main);
    try {
      parser.parseArgument(args);
    } catch (CmdLineException e) {
      e.printStackTrace(System.err);
      System.exit(1);
    }
    main.run();
  }

  public void run() throws IOException {
    final Path ideaConfigDir = Paths.get(ideaDir);
    final ModuleInfoBinaryIndex index = new ModuleInfoBinaryIndex(ideaConfigDir);
    final Path projectRoot = ideaConfigDir.getParent();

    if (updateImlFileList != null) {
      index.update(moduleInfosFromListOfImlFiles(projectRoot, Paths.get(updateImlFileList)));
    } else if (generate) {
      index.write(moduleInfosFromIdeaDir(projectRoot, ideaConfigDir));
    } else {
      System.err.println("Must specify either --update-list or --generate");
      System.exit(1);
    }
  }

  private static ImmutableSet<ModuleInfo> moduleInfosFromListOfImlFiles(
      @Nonnull Path projectRoot, @Nonnull Path path) {
    try (final Stream<String> lineStream = Files.lines(path, StandardCharsets.UTF_8)) {
      return moduleInfosFromPaths(projectRoot, lineStream.map(Paths::get));
    } catch (IOException e) {
      throw new RuntimeException("Could not read from path: " + path, e);
    }
  }

  private static ImmutableSet<ModuleInfo> moduleInfosFromIdeaDir(
      @Nonnull Path projectRoot, @Nonnull Path ideaDir) throws IOException {
    Path modulesDir = ideaDir.resolve("modules");
    try (final Stream<Path> moduleFileStream = Files.list(modulesDir)) {
      return moduleInfosFromPaths(projectRoot, moduleFileStream);
    }
  }

  private static ImmutableSet<ModuleInfo> moduleInfosFromPaths(
      @Nonnull Path projectRoot, @Nonnull Stream<Path> stream) {
    return stream
        .parallel()
        .map(filePath -> moduleInfoFromFile(projectRoot, filePath))
        .collect(ImmutableSet.toImmutableSet());
  }

  static ModuleInfo moduleInfoFromFile(@Nonnull Path projectRoot, @Nonnull Path imlFile) {
    final String fileName = imlFile.getFileName().toString();
    Preconditions.checkArgument(fileName.endsWith(IML_SUFFIX), "Only .iml files can be processed");
    Preconditions.checkArgument(
        imlFile.startsWith(projectRoot),
        "Module file " + imlFile + " is not under the project root " + projectRoot);
    final String moduleName = fileName.substring(0, fileName.length() - IML_SUFFIX.length());
    final Document IMLDocument = getIMLDocument(imlFile.toFile());

    return ModuleInfo.of(
        moduleName,
        projectRoot.relativize(imlFile.getParent()).toString(), // modulePath
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
        .map(UpdateTool::contentInfoFromNode)
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
        .filter(UpdateTool::isModule)
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
