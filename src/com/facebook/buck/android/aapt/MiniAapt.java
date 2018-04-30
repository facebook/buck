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

import com.facebook.buck.android.AaptStep;
import com.facebook.buck.android.aapt.RDotTxtEntry.IdType;
import com.facebook.buck.android.aapt.RDotTxtEntry.RType;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.ThrowingPrintWriter;
import com.facebook.buck.util.json.ObjectMappers;
import com.facebook.buck.util.xml.DocumentLocation;
import com.facebook.buck.util.xml.PositionalXmlHandler;
import com.facebook.buck.util.xml.XmlDomParserWithLineNumbers;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Step which parses resources in an android {@code res} directory and compiles them into a {@code
 * R.txt} file, following the exact same format as the Android build tool {@code aapt}.
 *
 * <p>
 */
public class MiniAapt implements Step {

  /** See {@link com.facebook.buck.android.AaptStep} for a list of files that we ignore. */
  public static final ImmutableList<String> IGNORED_FILE_EXTENSIONS = ImmutableList.of("orig");

  private static final String ID_DEFINITION_PREFIX = "@+id/";
  private static final String ITEM_TAG = "item";
  private static final String CUSTOM_DRAWABLE_PREFIX = "app-";

  private static final XPathExpression ANDROID_ID_USAGE =
      createExpression(
          "//@*[starts-with(., '@') and "
              + "not(starts-with(., '@+')) and "
              + "not(starts-with(., '@android:')) and "
              + "not(starts-with(., '@null'))]");

  private static final XPathExpression ANDROID_ID_DEFINITION =
      createExpression("//@*[starts-with(., '@+') and " + "not(starts-with(., '@+android:id'))]");

  private static final ImmutableMap<String, RType> RESOURCE_TYPES = getResourceTypes();
  private static final ImmutableSet<String> IGNORED_TAGS = ImmutableSet.of("eat-comment", "skip");

  public enum ResourceCollectionType {
    R_DOT_TXT,
    ANDROID_RESOURCE_INDEX,
  }

  private final SourcePathResolver resolver;
  private final ProjectFilesystem filesystem;
  private final SourcePath resDirectory;
  private final Path pathToOutputFile;
  private final ImmutableSet<Path> pathsToSymbolsOfDeps;
  private final ResourceCollector resourceCollector;
  private final boolean resourceUnion;
  private final boolean isGrayscaleImageProcessingEnabled;
  private final ResourceCollectionType resourceCollectionType;

  public MiniAapt(
      SourcePathResolver resolver,
      ProjectFilesystem filesystem,
      SourcePath resDirectory,
      Path pathToTextSymbolsFile,
      ImmutableSet<Path> pathsToSymbolsOfDeps) {
    this(
        resolver,
        filesystem,
        resDirectory,
        pathToTextSymbolsFile,
        pathsToSymbolsOfDeps,
        /* resourceUnion */ false,
        /* isGrayscaleImageProcessingEnabled */ false,
        ResourceCollectionType.R_DOT_TXT);
  }

  public MiniAapt(
      SourcePathResolver resolver,
      ProjectFilesystem filesystem,
      SourcePath resDirectory,
      Path pathToOutputFile,
      ImmutableSet<Path> pathsToSymbolsOfDeps,
      boolean resourceUnion,
      boolean isGrayscaleImageProcessingEnabled,
      ResourceCollectionType resourceCollectionType) {
    this.resolver = resolver;
    this.filesystem = filesystem;
    this.resDirectory = resDirectory;
    this.pathToOutputFile = pathToOutputFile;
    this.pathsToSymbolsOfDeps = pathsToSymbolsOfDeps;
    this.resourceUnion = resourceUnion;
    this.isGrayscaleImageProcessingEnabled = isGrayscaleImageProcessingEnabled;
    this.resourceCollectionType = resourceCollectionType;

    switch (resourceCollectionType) {
      case R_DOT_TXT:
        this.resourceCollector = new RDotTxtResourceCollector();
        break;
      case ANDROID_RESOURCE_INDEX:
        this.resourceCollector = new AndroidResourceIndexCollector(filesystem);
        break;
      default:
        throw new IllegalArgumentException(
            "Invalid resource collector type: " + resourceCollectionType);
    }
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
  ResourceCollector getResourceCollector() {
    return resourceCollector;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    ImmutableSet.Builder<RDotTxtEntry> references = ImmutableSet.builder();

    try {
      collectResources(filesystem, context.getBuckEventBus());
      processXmlFilesForIds(filesystem, references);
    } catch (XPathExpressionException | ResourceParseException e) {
      context.logError(e, "Error parsing resources to generate resource IDs for %s.", resDirectory);
      return StepExecutionResults.ERROR;
    }

    Set<RDotTxtEntry> missing = verifyReferences(filesystem, references.build());
    if (!missing.isEmpty()) {
      context
          .getBuckEventBus()
          .post(
              ConsoleEvent.severe(
                  "The following resources were not found when processing %s: \n%s\n",
                  resDirectory, Joiner.on('\n').join(missing)));
      return StepExecutionResults.ERROR;
    }

    if (resourceUnion) {
      resourceUnion();
    }

    if (resourceCollectionType == ResourceCollectionType.R_DOT_TXT) {
      RDotTxtResourceCollector rDotTxtResourceCollector =
          (RDotTxtResourceCollector) resourceCollector;
      try (ThrowingPrintWriter writer =
          new ThrowingPrintWriter(filesystem.newFileOutputStream(pathToOutputFile))) {
        Set<RDotTxtEntry> sortedResources =
            ImmutableSortedSet.copyOf(Ordering.natural(), rDotTxtResourceCollector.getResources());
        for (RDotTxtEntry entry : sortedResources) {
          writer.printf("%s %s %s %s\n", entry.idType, entry.type, entry.name, entry.idValue);
        }
      }
    }
    if (resourceCollectionType == ResourceCollectionType.ANDROID_RESOURCE_INDEX) {
      AndroidResourceIndexCollector androidResourceIndexCollector =
          (AndroidResourceIndexCollector) resourceCollector;

      ObjectMappers.WRITER.writeValue(
          filesystem.newFileOutputStream(pathToOutputFile),
          androidResourceIndexCollector.getResourceIndex());
    }

    return StepExecutionResults.SUCCESS;
  }

  /**
   * Collect resource information from R.txt for each dep and perform a resource union.
   *
   * @throws IOException
   */
  public void resourceUnion() throws IOException {
    for (Path depRTxt : pathsToSymbolsOfDeps) {
      Iterable<String> lines =
          filesystem
              .readLines(depRTxt)
              .stream()
              .filter(input -> !Strings.isNullOrEmpty(input))
              .collect(Collectors.toList());
      for (String line : lines) {
        Optional<RDotTxtEntry> entry = RDotTxtEntry.parse(line);
        Preconditions.checkState(entry.isPresent());
        resourceCollector.addResourceIfNotPresent(entry.get());
      }
    }
  }

  /**
   * Collects file names under the {@code res} directory, except those under directories starting
   * with {@code values}, as resources based on their parent directory.
   *
   * <p>So for instance, if the directory structure is something like:
   *
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
   *
   * the resulting resources would contain:
   *
   * <ul>
   *   <li>R.drawable.image
   *   <li>R.drawable.nine_patch
   *   <li>R.layout.my_view
   *   <li>R.layout.another_view
   * </ul>
   *
   * <p>For files under the {@code values*} directories, see {@link
   * #processValuesFile(ProjectFilesystem, Path)}
   */
  private void collectResources(ProjectFilesystem filesystem, BuckEventBus eventBus)
      throws IOException, ResourceParseException {
    Collection<Path> contents =
        filesystem.getDirectoryContents(resolver.getAbsolutePath(resDirectory));
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

      RType rType = Preconditions.checkNotNull(RESOURCE_TYPES.get(dirname));
      if (rType == RType.DRAWABLE) {
        processDrawables(filesystem, resourceFile);
      } else {
        resourceCollector.addIntResourceIfNotPresent(
            rType, resourceName, resourceFile, DocumentLocation.of(0, 0));
      }
    }
  }

  void processDrawables(ProjectFilesystem filesystem, Path resourceFile)
      throws IOException, ResourceParseException {
    String filename = resourceFile.getFileName().toString();
    int dotIndex = filename.indexOf('.');
    String resourceName = dotIndex != -1 ? filename.substring(0, dotIndex) : filename;

    // Look into the XML file.
    boolean isGrayscaleImage = false;
    boolean isCustomDrawable = false;
    if (filename.endsWith(".xml")) {
      try (InputStream stream = filesystem.newFileInputStream(resourceFile)) {
        Document dom = parseXml(resourceFile, stream);
        Element root = dom.getDocumentElement();
        isCustomDrawable = root.getNodeName().startsWith(CUSTOM_DRAWABLE_PREFIX);
      }
    } else if (isGrayscaleImageProcessingEnabled) {
      isGrayscaleImage = filename.endsWith(".g.png");
    }

    DocumentLocation location = DocumentLocation.of(0, 0);
    if (isCustomDrawable) {
      resourceCollector.addCustomDrawableResourceIfNotPresent(
          RType.DRAWABLE, resourceName, resourceFile, location);
    } else if (isGrayscaleImage) {
      resourceCollector.addGrayscaleImageResourceIfNotPresent(
          RType.DRAWABLE, resourceName, resourceFile, location);
    } else {
      resourceCollector.addIntResourceIfNotPresent(
          RType.DRAWABLE, resourceName, resourceFile, location);
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
   * Processes an {@code xml} file immediately under a {@code values} directory. See <a
   * href="http://developer.android.com/guide/topics/resources/more-resources.html>More Resource
   * Types</a> to find out more about how resources are defined.
   *
   * <p>For an input file with contents like:
   *
   * <pre>
   *   <?xml version="1.0" encoding="utf-8"?>
   *   <resources>
   *     <integer name="number">42</integer>
   *     <dimen name="dimension">10px</dimen>
   *     <string name="hello">World</string>
   *     <item name="my_fraction" type="fraction">1.5</item>
   *   </resources>
   * </pre>
   *
   * the resulting resources would be:
   *
   * <ul>
   *   <li>R.integer.number
   *   <li>R.dimen.dimension
   *   <li>R.string.hello
   *   <li>R.fraction.my_fraction
   * </ul>
   */
  @VisibleForTesting
  void processValuesFile(ProjectFilesystem filesystem, Path valuesFile)
      throws IOException, ResourceParseException {
    try (InputStream stream = filesystem.newFileInputStream(valuesFile)) {
      Document dom = parseXml(valuesFile, stream);
      Element root = dom.getDocumentElement();

      // Exclude resources annotated with the attribute {@code exclude-from-resource-map}.
      // This is useful to exclude using generated strings to build the
      // resource map, which ensures a build break will show up at build time
      // rather than being hidden until generated resources are updated.
      if (root.getAttribute("exclude-from-buck-resource-map").equals("true")) {
        return;
      }

      for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
        if (node.getNodeType() != Node.ELEMENT_NODE) {
          continue;
        }

        String resourceType = node.getNodeName();
        if (resourceType.equals(ITEM_TAG)) {
          Node typeNode = node.getAttributes().getNamedItem("type");
          if (typeNode == null) {
            throw new ResourceParseException(
                "Error parsing file '%s', expected a 'type' attribute in: \n'%s'\n",
                valuesFile, node.toString());
          }
          resourceType = typeNode.getNodeValue();
        }

        if (IGNORED_TAGS.contains(resourceType)) {
          continue;
        }

        if (!RESOURCE_TYPES.containsKey(resourceType)) {
          throw new ResourceParseException(
              "Invalid resource type '<%s>' in '%s'.", resourceType, valuesFile);
        }

        RType rType = Preconditions.checkNotNull(RESOURCE_TYPES.get(resourceType));
        addToResourceCollector(node, rType, valuesFile);
      }
    }
  }

  private void addToResourceCollector(Node node, RType rType, Path file)
      throws ResourceParseException {
    String resourceName = sanitizeName(extractNameAttribute(node));
    DocumentLocation location = extractDocumentLocation(node);

    if (rType.equals(RType.STYLEABLE)) {
      int count = 0;
      for (Node attrNode = node.getFirstChild();
          attrNode != null;
          attrNode = attrNode.getNextSibling()) {
        if (attrNode.getNodeType() != Node.ELEMENT_NODE || !attrNode.getNodeName().equals("attr")) {
          continue;
        }

        String rawAttrName = extractNameAttribute(attrNode);
        String attrName = sanitizeName(rawAttrName);
        resourceCollector.addResource(
            RType.STYLEABLE,
            IdType.INT,
            String.format("%s_%s", resourceName, attrName),
            Integer.toString(count++),
            resourceName,
            file,
            extractDocumentLocation(node));

        if (!rawAttrName.startsWith("android:")) {
          resourceCollector.addIntResourceIfNotPresent(
              RType.ATTR, attrName, file, extractDocumentLocation(node));
        }
      }

      resourceCollector.addIntArrayResourceIfNotPresent(rType, resourceName, count, file, location);
    } else {
      resourceCollector.addIntResourceIfNotPresent(rType, resourceName, file, location);
    }
  }

  void processXmlFilesForIds(
      ProjectFilesystem filesystem, ImmutableSet.Builder<RDotTxtEntry> references)
      throws IOException, XPathExpressionException, ResourceParseException {
    Path absoluteResDir = resolver.getAbsolutePath(resDirectory);
    Path relativeResDir = resolver.getRelativePath(resDirectory);
    for (Path path :
        filesystem.getFilesUnderPath(absoluteResDir, input -> input.toString().endsWith(".xml"))) {
      String dirname = relativeResDir.relativize(path).getName(0).toString();
      if (isAValuesDir(dirname)) {
        // Ignore files under values* directories.
        continue;
      }
      processXmlFile(filesystem, path, references);
    }
  }

  @VisibleForTesting
  void processXmlFile(
      ProjectFilesystem filesystem, Path xmlFile, ImmutableSet.Builder<RDotTxtEntry> references)
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

        Element ownerElement = ((Attr) nodesWithIds.item(i)).getOwnerElement();
        DocumentLocation location = extractDocumentLocation(ownerElement);
        resourceCollector.addIntResourceIfNotPresent(
            RType.ID, resourceName.substring(ID_DEFINITION_PREFIX.length()), xmlFile, location);
      }

      NodeList nodesUsingIds = (NodeList) ANDROID_ID_USAGE.evaluate(dom, XPathConstants.NODESET);
      for (int i = 0; i < nodesUsingIds.getLength(); i++) {
        String resourceName = nodesUsingIds.item(i).getNodeValue();
        int slashPosition = resourceName.indexOf('/');
        if (resourceName.charAt(0) != '@' || slashPosition == -1) {
          throw new ResourceParseException("Invalid definition of a resource: '%s'", resourceName);
        }

        String rawRType = resourceName.substring(1, slashPosition);
        String name = resourceName.substring(slashPosition + 1);

        String nodeName = nodesUsingIds.item(i).getNodeName();
        if (name.startsWith("android:") || nodeName.startsWith("tools:")) {
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
      return XmlDomParserWithLineNumbers.parse(inputStream);
    } catch (SAXException e) {
      throw new ResourceParseException(
          "Error parsing xml file '%s': %s.", filepath, e.getMessage());
    }
  }

  private static String extractNameAttribute(Node node) throws ResourceParseException {
    Node attribute = node.getAttributes().getNamedItem("name");
    if (attribute == null) {
      throw new ResourceParseException(
          "Error: expected a 'name' attribute in node '%s' with value '%s'",
          node.getNodeName(), node.getTextContent());
    }
    return attribute.getNodeValue();
  }

  private static String sanitizeName(String rawName) {
    return rawName.replaceAll("[.:]", "_");
  }

  private static boolean isAValuesDir(String dirname) {
    return dirname.equals("values") || dirname.startsWith("values-");
  }

  private static boolean shouldIgnoreFile(Path path, ProjectFilesystem filesystem)
      throws IOException {
    return filesystem.isHidden(path)
        || IGNORED_FILE_EXTENSIONS.contains(
            com.google.common.io.Files.getFileExtension(path.getFileName().toString()))
        || AaptStep.isSilentlyIgnored(path);
  }

  /**
   * Extracts document location saved by XmlDomParserWithLineNumbers
   *
   * @param node a DOM node
   * @return the document location stored in node
   */
  private DocumentLocation extractDocumentLocation(Node node) {
    return (DocumentLocation) node.getUserData(PositionalXmlHandler.LOCATION_USER_DATA_KEY);
  }

  @VisibleForTesting
  ImmutableSet<RDotTxtEntry> verifyReferences(
      ProjectFilesystem filesystem, ImmutableSet<RDotTxtEntry> references) throws IOException {
    if (resourceCollectionType == ResourceCollectionType.ANDROID_RESOURCE_INDEX) {
      return ImmutableSet.of(); // we don't check dependencies to generate this index
    }

    RDotTxtResourceCollector castResourceCollector = (RDotTxtResourceCollector) resourceCollector;
    ImmutableSet.Builder<RDotTxtEntry> unresolved = ImmutableSet.builder();
    ImmutableSet.Builder<RDotTxtEntry> definitionsBuilder = ImmutableSet.builder();
    definitionsBuilder.addAll(castResourceCollector.getResources());
    for (Path depRTxt : pathsToSymbolsOfDeps) {
      Iterable<String> lines =
          filesystem
              .readLines(depRTxt)
              .stream()
              .filter(input -> !Strings.isNullOrEmpty(input))
              .collect(Collectors.toList());
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

  @VisibleForTesting
  static class ResourceParseException extends Exception {

    ResourceParseException(String messageFormat, Object... args) {
      super(String.format(messageFormat, args));
    }
  }
}
