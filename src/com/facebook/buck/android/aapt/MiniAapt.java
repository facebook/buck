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

package com.facebook.buck.android.aapt;

import com.facebook.buck.android.aapt.RDotTxtEntry.IdType;
import com.facebook.buck.android.aapt.RDotTxtEntry.RType;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.MoreStrings;
import com.facebook.buck.util.XmlDomParser;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Ordering;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;

import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

/**
 * Step which parses resources in an android {@code res} directory and compiles them into a
 * {@code R.txt} file, following the exact same format as the Android build tool {@code aapt}.
 * <p>
 */
public class MiniAapt implements Step {

  /**
   * See {@link com.facebook.buck.android.AaptStep} for a list of files that we ignore.
   */
  public static final ImmutableList<String> IGNORED_FILE_EXTENSIONS = ImmutableList.of("orig");

  private static final String ID_DEFINITION_PREFIX = "@+id/";
  private static final String ITEM_TAG = "item";

  private static final XPathExpression ANDROID_ID_USAGE =
      createExpression("//@*[starts-with(., '@') and " +
              "not(starts-with(., '@+')) and " +
              "not(starts-with(., '@android:')) and " +
              "not(starts-with(., '@null'))]");

  private static final XPathExpression ANDROID_ID_DEFINITION =
      createExpression("//@*[starts-with(., '@+') and " +
              "not(starts-with(., '@+android:id'))]");

  private static final ImmutableMap<String, RType> RESOURCE_TYPES = getResourceTypes();
  private static final ImmutableSet<String> IGNORED_TAGS = ImmutableSet.of(
      "eat-comment",
      "skip");

  private static final Predicate<Path> ENDS_WITH_XML = new Predicate<Path>() {
    @Override
    public boolean apply(Path input) {
      return input.toString().endsWith(".xml");
    }
  };

  private final Path resDirectory;
  private final Path pathToTextSymbolsFile;
  private final ImmutableSet<Path> pathsToSymblolsOfDeps;
  private final AaptResourceCollector resourceCollector;

  public MiniAapt(
      Path resDirectory,
      Path pathToTextSymbolsFile,
      ImmutableSet<Path> pathsToSymblolsOfDeps) {
    this.resDirectory = resDirectory;
    this.pathToTextSymbolsFile = pathToTextSymbolsFile;
    this.pathsToSymblolsOfDeps = pathsToSymblolsOfDeps;
    this.resourceCollector = new AaptResourceCollector();
  }

  private static XPathExpression createExpression(String expressionStr) {
    try {
      return XPathFactory.newInstance().newXPath().compile(expressionStr);
    } catch (XPathExpressionException e) {
      throw new RuntimeException(e);
    }
  }

  private static ImmutableMap<String, RType> getResourceTypes() {
    ImmutableMap.Builder<String, RType> types = ImmutableMap.builder();
    for (RType rType : RType.values()) {
      types.put(rType.toString(), rType);
    }
    types.put("string-array", RType.ARRAY);
    types.put("integer-array", RType.ARRAY);
    types.put("declare-styleable", RType.STYLEABLE);
    return types.build();
  }

  @VisibleForTesting
  AaptResourceCollector getResourceCollector() {
    return resourceCollector;
  }

  @Override
  public int execute(ExecutionContext context) throws InterruptedException {
    ProjectFilesystem filesystem = context.getProjectFilesystem();
    ImmutableSet.Builder<RDotTxtEntry> references = ImmutableSet.builder();

    try {
      collectResources(filesystem, context.getBuckEventBus());
      processXmlFilesForIds(filesystem, references);
    } catch (IOException | XPathExpressionException | ResourceParseException e) {
      context.logError(e, "Error parsing resources to generate resource IDs for %s.", resDirectory);
      return 1;
    }

    try {
      Set<RDotTxtEntry> missing = verifyReferences(filesystem, references.build());
      if (!missing.isEmpty()) {
        context.logError(
            new RuntimeException(),
            "The following resources were not found when processing %s: \n%s\n",
            resDirectory,
            Joiner.on('\n').join(missing));
        return 1;
      }
    } catch (IOException e) {
      context.logError(e, "Error verifying resources for %s.", resDirectory);
      return 1;
    }

    try (PrintWriter writer =
             new PrintWriter(filesystem.newFileOutputStream(pathToTextSymbolsFile))) {
      Set<RDotTxtEntry> sortedResources =
          FluentIterable.from(resourceCollector.getResources()).toSortedSet(Ordering.natural());
      for (RDotTxtEntry entry : sortedResources) {
        writer.printf("%s %s %s %s\n", entry.idType, entry.type, entry.name, entry.idValue);
      }
    } catch (IOException e) {
      context.logError(e, "Error writing file: %s", pathToTextSymbolsFile);
      return 1;
    }

    return 0;
  }

  /**
   * Collects file names under the {@code res} directory, except those under directories starting
   * with {@code values}, as resources based on their parent directory.
   * <p>So for instance, if the directory structure is something like:</p>
   * <pre>
   *   res/
   *       values/ ...
   *       values-es/ ...
   *       drawable/
   *                image.png
   *                nine_patch.9.png
   *       layout/
   *              my_view.xml
   *              another_view.xml
   * </pre>
   * the resulting resources would contain:
   * <ul>
   *   <li>R.drawable.image</li>
   *   <li>R.drawable.nine_patch</li>
   *   <li>R.layout.my_view</li>
   *   <li>R.layout.another_view</li>
   * </ul>
   * <p>
   * For files under the {@code values*} directories, see
   * {@link #processValuesFile(ProjectFilesystem, Path)}
   */
  private void collectResources(ProjectFilesystem filesystem, BuckEventBus eventBus)
      throws IOException, ResourceParseException {
    Collection<Path> contents = filesystem.getDirectoryContents(resDirectory);
    for (Path dir : contents) {
      if (!filesystem.isDirectory(dir) && !filesystem.isIgnored(dir)) {
        if (!shouldIgnoreFile(dir, filesystem)) {
          eventBus.post(ConsoleEvent.warning("MiniAapt [warning]: ignoring file '%s'.", dir));
        }
        continue;
      }

      String dirname = dir.getFileName().toString();
      if (dirname.startsWith("values")) {
        if (!isAValuesDir(dirname)) {
          throw new ResourceParseException("'%s' is not a valid values directory.", dir);
        }
        processValues(filesystem, eventBus, dir);
      } else {
        processFileNamesInDirectory(filesystem, dir);
      }

    }
  }

  void processFileNamesInDirectory(ProjectFilesystem filesystem, Path dir)
      throws IOException, ResourceParseException {
    String dirname = dir.getFileName().toString();
    int dashIndex = dirname.indexOf('-');
    if (dashIndex != -1) {
      dirname = dirname.substring(0, dashIndex);
    }

    if (!RESOURCE_TYPES.containsKey(dirname)) {
      throw new ResourceParseException("'%s' is not a valid resource sub-directory.", dir);
    }

    for (Path resourceFile : filesystem.getDirectoryContents(dir)) {
      if (shouldIgnoreFile(resourceFile, filesystem)) {
        continue;
      }

      String filename = resourceFile.getFileName().toString();
      int dotIndex = filename.indexOf('.');
      String resourceName = dotIndex != -1 ? filename.substring(0, dotIndex) : filename;

      resourceCollector.addIntResourceIfNotPresent(
          Preconditions.checkNotNull(RESOURCE_TYPES.get(dirname)),
          resourceName);
    }
  }

  void processValues(ProjectFilesystem filesystem, BuckEventBus eventBus, Path valuesDir)
      throws IOException, ResourceParseException {
    for (Path path : filesystem.getFilesUnderPath(valuesDir)) {
      if (shouldIgnoreFile(path, filesystem)) {
        continue;
      }
      if (!filesystem.isFile(path) && !filesystem.isIgnored(path)) {
        eventBus.post(ConsoleEvent.warning("MiniAapt [warning]: ignoring non-file '%s'.", path));
        continue;
      }
      processValuesFile(filesystem, path);
    }
  }

  /**
   * Processes an {@code xml} file immediately under a {@code values} directory. See
   * <a href="http://developer.android.com/guide/topics/resources/more-resources.html>More Resource
   * Types</a> to find out more about how resources are defined.
   * <p>
   * For an input file with contents like:
   * <pre>
   *   <?xml version="1.0" encoding="utf-8"?>
   *   <resources>
   *     <integer name="number">42</integer>
   *     <dimen name="dimension">10px</dimen>
   *     <string name="hello">World</string>
   *     <item name="my_fraction" type="fraction">1.5</item>
   *   </resources>
   * </pre>
   * the resulting resources would be:
   * <ul>
   *   <li>R.integer.number</li>
   *   <li>R.dimen.dimension</li>
   *   <li>R.string.hello</li>
   *   <li>R.fraction.my_fraction</li>
   * </ul>
   */
  @VisibleForTesting
  void processValuesFile(ProjectFilesystem filesystem, Path valuesFile)
      throws IOException, ResourceParseException {
    try (InputStream stream = filesystem.newFileInputStream(valuesFile)) {
      Document dom = parseXml(valuesFile, stream);
      Element root = dom.getDocumentElement();

      for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
        if (node.getNodeType() != Node.ELEMENT_NODE) {
          continue;
        }

        String resourceType = node.getNodeName();
        if (resourceType.equals(ITEM_TAG)) {
          resourceType = node.getAttributes().getNamedItem("type").getNodeValue();
        }

        if (IGNORED_TAGS.contains(resourceType)) {
          continue;
        }

        if (!RESOURCE_TYPES.containsKey(resourceType)) {
          throw new ResourceParseException(
              "Invalid resource type '<%s>' in '%s'.",
              resourceType,
              valuesFile);
        }

        RType rType = Preconditions.checkNotNull(RESOURCE_TYPES.get(resourceType));
        addToResourceCollector(node, rType);
      }
    }
  }

  private void addToResourceCollector(Node node, RType rType) {
    String resourceName = sanitizeName(extractNameAttribute(node));
    if (rType.equals(RType.STYLEABLE)) {

      int count = 0;
      for (Node attrNode = node.getFirstChild();
           attrNode != null;
           attrNode = attrNode.getNextSibling()) {
        if (attrNode.getNodeType() != Node.ELEMENT_NODE ||
            !attrNode.getNodeName().equals("attr")) {
          continue;
        }

        String rawAttrName = extractNameAttribute(attrNode);
        String attrName = sanitizeName(rawAttrName);
        resourceCollector.addResource(
            RType.STYLEABLE,
            IdType.INT,
            String.format(
                "%s_%s",
                resourceName,
                attrName),
            Integer.toString(count++));

        if (!rawAttrName.startsWith("android:")) {
          resourceCollector.addIntResourceIfNotPresent(RType.ATTR, attrName);
        }
      }

      resourceCollector.addIntArrayResourceIfNotPresent(rType, resourceName, count);
    } else {
      resourceCollector.addIntResourceIfNotPresent(rType, resourceName);
    }
  }

  void processXmlFilesForIds(
      ProjectFilesystem filesystem,
      ImmutableSet.Builder<RDotTxtEntry> references)
      throws IOException, XPathExpressionException, ResourceParseException {
    for (Path path : filesystem.getFilesUnderPath(resDirectory, ENDS_WITH_XML)) {
      String dirname = resDirectory.relativize(path).getName(0).toString();
      if (isAValuesDir(dirname)) {
        // Ignore files under values* directories.
        continue;
      }
      processXmlFile(filesystem, path, references);
    }
  }

  @VisibleForTesting
  void processXmlFile(
      ProjectFilesystem filesystem,
      Path xmlFile,
      ImmutableSet.Builder<RDotTxtEntry> references)
      throws IOException, XPathExpressionException, ResourceParseException {
    try (InputStream stream = filesystem.newFileInputStream(xmlFile)) {
      Document dom = parseXml(xmlFile, stream);
      NodeList nodesWithIds =
          (NodeList) ANDROID_ID_DEFINITION.evaluate(dom, XPathConstants.NODESET);
      for (int i = 0; i < nodesWithIds.getLength(); i++) {
        String resourceName = nodesWithIds.item(i).getNodeValue();
        if (!resourceName.startsWith(ID_DEFINITION_PREFIX)) {
          throw new ResourceParseException("Invalid definition of a resource: '%s'", resourceName);
        }
        Preconditions.checkState(resourceName.startsWith(ID_DEFINITION_PREFIX));

        resourceCollector.addIntResourceIfNotPresent(
            RType.ID,
            resourceName.substring(ID_DEFINITION_PREFIX.length()));
      }

      NodeList nodesUsingIds =
          (NodeList) ANDROID_ID_USAGE.evaluate(dom, XPathConstants.NODESET);
      for (int i = 0; i < nodesUsingIds.getLength(); i++) {
        String resourceName = nodesUsingIds.item(i).getNodeValue();
        Preconditions.checkState(resourceName.charAt(0) == '@');
        int slashPosition = resourceName.indexOf('/');
        Preconditions.checkState(slashPosition != -1);

        String rawRType = resourceName.substring(1, slashPosition);
        String name = resourceName.substring(slashPosition + 1);

        if (name.startsWith("android:")) {
          continue;
        }
        if (!RESOURCE_TYPES.containsKey(rawRType)) {
          throw new ResourceParseException("Invalid reference '%s' in '%s'", resourceName, xmlFile);
        }
        RType rType = Preconditions.checkNotNull(RESOURCE_TYPES.get(rawRType));


        references.add(new FakeRDotTxtEntry(IdType.INT, rType, sanitizeName(name)));
      }
    }
  }

  private static Document parseXml(Path filepath, InputStream inputStream)
      throws IOException, ResourceParseException {
    try {
      return XmlDomParser.parse(inputStream);
    } catch (SAXException e) {
      throw new ResourceParseException(
          "Error parsing xml file '%s': %s.",
          filepath,
          e.getMessage());
    }
  }

  private static String extractNameAttribute(Node node) {
    return node.getAttributes().getNamedItem("name").getNodeValue();
  }

  private static String sanitizeName(String rawName) {
    return rawName.replaceAll("[.:]", "_");
  }

  private static boolean isAValuesDir(String dirname) {
    return dirname.equals("values") || dirname.startsWith("values-");
  }

  private static boolean shouldIgnoreFile(Path path, ProjectFilesystem filesystem)
      throws IOException{
    return filesystem.isHidden(path) ||
        IGNORED_FILE_EXTENSIONS.contains(
            com.google.common.io.Files.getFileExtension(path.getFileName().toString()));
  }

  @VisibleForTesting
  ImmutableSet<RDotTxtEntry> verifyReferences(
      ProjectFilesystem filesystem,
      ImmutableSet<RDotTxtEntry> references) throws IOException {
    ImmutableSet.Builder<RDotTxtEntry> unresolved = ImmutableSet.builder();
    ImmutableSet.Builder<RDotTxtEntry> definitionsBuilder = ImmutableSet.builder();
    definitionsBuilder.addAll(resourceCollector.getResources());
    for (Path depRTxt : pathsToSymblolsOfDeps) {
      Iterable<String> lines = FluentIterable.from(filesystem.readLines(depRTxt))
          .filter(MoreStrings.NON_EMPTY)
          .toList();
      for (String line : lines) {
        Optional<RDotTxtEntry> entry = RDotTxtEntry.parse(line);
        Preconditions.checkState(entry.isPresent());
        definitionsBuilder.add(entry.get());
      }
    }

    Set<RDotTxtEntry> definitions = definitionsBuilder.build();
    for (RDotTxtEntry reference : references) {
      if (!definitions.contains(reference)) {
        unresolved.add(reference);
      }
    }
    return unresolved.build();
  }

  @Override
  public String getShortName() {
    return "generate_resource_ids";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return getShortName() + " " + resDirectory;
  }

  @SuppressWarnings("serial")
  @VisibleForTesting
  static class ResourceParseException extends Exception {

    ResourceParseException(String messageFormat, Object... args) {
      super(String.format(messageFormat, args));
    }
  }
}
