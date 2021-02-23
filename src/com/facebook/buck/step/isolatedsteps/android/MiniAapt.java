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

package com.facebook.buck.step.isolatedsteps.android;

import com.facebook.buck.android.AaptStep;
import com.facebook.buck.android.aapt.FakeRDotTxtEntry;
import com.facebook.buck.android.aapt.RDotTxtEntry;
import com.facebook.buck.android.aapt.RDotTxtEntry.CustomDrawableType;
import com.facebook.buck.android.aapt.RDotTxtEntry.IdType;
import com.facebook.buck.android.aapt.RDotTxtEntry.RType;
import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.IsolatedEventBus;
import com.facebook.buck.io.filesystem.impl.ProjectFilesystemUtils;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.facebook.buck.util.ThrowingPrintWriter;
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
import java.nio.file.FileVisitOption;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
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
public class MiniAapt extends IsolatedStep {

  private static final String GRAYSCALE_SUFFIX = "_g.png";

  /** See {@link AaptStep} for a list of files that we ignore. */
  public static final ImmutableList<String> IGNORED_FILE_EXTENSIONS = ImmutableList.of("orig");

  private static final String ID_DEFINITION_PREFIX = "@+id/";
  private static final String ITEM_TAG = "item";
  private static final String PUBLIC_TAG = "public";
  private static final String PUBLIC_FILENAME = "public.xml";
  private static final String CUSTOM_DRAWABLE_PREFIX = "app-";

  private static final XPathExpression ANDROID_ID_AND_ATTR_USAGE =
      createExpression(
          "//@*[(starts-with(., '@') and "
              + "not(starts-with(., '@+')) and "
              + "not(starts-with(., '@android:')) and "
              + "not(starts-with(., '@null'))) or "
              + "starts-with(., '?attr')]");

  private static final XPathExpression ANDROID_ATTR_USAGE_FOR_STYLES =
      createExpression(
          "//item/@name[not(starts-with(., 'android'))] | //item[@name]/text()[starts-with(., '?attr')]");

  private static final XPathExpression ANDROID_ID_DEFINITION =
      createExpression("//@*[starts-with(., '@+') and " + "not(starts-with(., '@+android:id'))]");

  private static final XPathExpression ALL_ATTRS = createExpression("//@*");

  private static final ImmutableMap<String, RType> RESOURCE_TYPES = getResourceTypes();

  /**
   * {@code <public>} is a special type of resource that is not be handled by aapt, but can be
   * analyzed by Android Lint.
   *
   * @see <a
   *     href="https://developer.android.com/studio/projects/android-library#PrivateResources">Private
   *     resources</a>
   */
  private static final ImmutableSet<String> IGNORED_TAGS =
      ImmutableSet.of("eat-comment", "skip", PUBLIC_TAG);

  private final RelPath resDirectory;
  private final RelPath pathToOutputFile;
  private final ImmutableSet<RelPath> pathsToSymbolsOfDeps;
  private final RDotTxtResourceCollector resourceCollector;
  private final boolean isVerifyingXmlAttrsEnabled;

  public MiniAapt(
      RelPath resDirectory,
      RelPath pathToTextSymbolsFile,
      ImmutableSet<RelPath> pathsToSymbolsOfDeps) {
    this(
        resDirectory,
        pathToTextSymbolsFile,
        pathsToSymbolsOfDeps,
        /* isVerifyingXmlAttrsEnabled */ false);
  }

  public MiniAapt(
      RelPath resDirectory,
      RelPath pathToOutputFile,
      ImmutableSet<RelPath> pathsToSymbolsOfDeps,
      boolean isVerifyingXmlAttrsEnabled) {
    this.resDirectory = resDirectory;
    this.pathToOutputFile = pathToOutputFile;
    this.pathsToSymbolsOfDeps = pathsToSymbolsOfDeps;
    this.isVerifyingXmlAttrsEnabled = isVerifyingXmlAttrsEnabled;
    this.resourceCollector = new RDotTxtResourceCollector();
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
  RDotTxtResourceCollector getResourceCollector() {
    return resourceCollector;
  }

  @Override
  public StepExecutionResult executeIsolatedStep(IsolatedExecutionContext context)
      throws IOException, InterruptedException {
    ImmutableSet.Builder<RDotTxtEntry> references = ImmutableSet.builder();

    try {
      collectResources(context.getRuleCellRoot(), context.getIsolatedEventBus());
      processXmlFilesForIds(context.getRuleCellRoot(), references);
    } catch (XPathExpressionException | ResourceParseException e) {
      context.logError(e, "Error parsing resources to generate resource IDs for %s.", resDirectory);
      return StepExecutionResults.ERROR;
    }

    Set<RDotTxtEntry> missing = verifyReferences(context.getRuleCellRoot(), references.build());
    if (!missing.isEmpty()) {
      context
          .getIsolatedEventBus()
          .post(
              ConsoleEvent.severe(
                  "The following resources were not found when processing %s: \n%s\n",
                  resDirectory, Joiner.on('\n').join(missing)));
      return StepExecutionResults.ERROR;
    }

    try (ThrowingPrintWriter writer =
        new ThrowingPrintWriter(
            ProjectFilesystemUtils.newFileOutputStream(
                context.getRuleCellRoot(), pathToOutputFile.getPath()))) {
      Set<RDotTxtEntry> sortedResources =
          ImmutableSortedSet.copyOf(Ordering.natural(), resourceCollector.getResources());
      for (RDotTxtEntry entry : sortedResources) {
        writer.printf("%s %s %s %s\n", entry.idType, entry.type, entry.name, entry.idValue);
      }
    }

    return StepExecutionResults.SUCCESS;
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
   * <p>For files under the {@code values*} directories, see {@link #processValuesFile(AbsPath,
   * Path)}
   */
  private void collectResources(AbsPath root, IsolatedEventBus eventBus)
      throws IOException, ResourceParseException {
    Collection<Path> contents =
        ProjectFilesystemUtils.getDirectoryContents(
            root, ImmutableSet.of(), resDirectory.getPath());
    for (Path dir : contents) {
      if (!ProjectFilesystemUtils.isDirectory(root, dir)) {
        if (!shouldIgnoreFile(root, dir)) {
          eventBus.post(ConsoleEvent.warning("MiniAapt [warning]: ignoring file '%s'.", dir));
        }
        continue;
      }

      String dirname = dir.getFileName().toString();
      if (dirname.startsWith("values")) {
        if (!isAValuesDir(dirname)) {
          throw new ResourceParseException("'%s' is not a valid values directory.", dir);
        }
        processValues(root, eventBus, dir);
      } else {
        processFileNamesInDirectory(root, dir);
      }
    }
  }

  void processFileNamesInDirectory(AbsPath root, Path dir)
      throws IOException, ResourceParseException {
    String dirname = dir.getFileName().toString();
    int dashIndex = dirname.indexOf('-');
    if (dashIndex != -1) {
      dirname = dirname.substring(0, dashIndex);
    }

    if (!RESOURCE_TYPES.containsKey(dirname)) {
      throw new ResourceParseException("'%s' is not a valid resource sub-directory.", dir);
    }

    for (Path resourceFile :
        ProjectFilesystemUtils.getDirectoryContents(root, ImmutableSet.of(), dir)) {
      if (shouldIgnoreFile(root, resourceFile)) {
        continue;
      }

      String filename = resourceFile.getFileName().toString();
      int dotIndex = filename.indexOf('.');
      String resourceName = dotIndex != -1 ? filename.substring(0, dotIndex) : filename;

      RType rType = Objects.requireNonNull(RESOURCE_TYPES.get(dirname));
      if (rType == RType.DRAWABLE) {
        processDrawables(root, resourceFile);
      } else {
        resourceCollector.addIntResourceIfNotPresent(rType, resourceName);
      }
    }
  }

  void processDrawables(AbsPath projectRoot, Path resourceFile)
      throws IOException, ResourceParseException {
    String filename = resourceFile.getFileName().toString();
    int dotIndex = filename.indexOf('.');
    String resourceName = dotIndex != -1 ? filename.substring(0, dotIndex) : filename;

    // Look into the XML file.
    boolean isGrayscaleImage = false;
    boolean isCustomDrawable = false;
    if (filename.endsWith(".xml")) {
      try (InputStream stream =
          ProjectFilesystemUtils.newFileInputStream(projectRoot, resourceFile)) {
        Document dom = parseXml(resourceFile, stream);
        Element root = dom.getDocumentElement();
        isCustomDrawable = root.getNodeName().startsWith(CUSTOM_DRAWABLE_PREFIX);
      }
    } else {
      // .g.png is no longer an allowed filename in newer versions of aapt2.
      isGrayscaleImage = filename.endsWith(".g.png") || filename.endsWith(GRAYSCALE_SUFFIX);
      if (isGrayscaleImage) {
        // Trim _g or .g from the resource name
        resourceName = filename.substring(0, filename.length() - GRAYSCALE_SUFFIX.length());
      }
    }

    if (isCustomDrawable) {
      resourceCollector.addCustomDrawableResourceIfNotPresent(
          RType.DRAWABLE, resourceName, CustomDrawableType.CUSTOM);
    } else if (isGrayscaleImage) {
      resourceCollector.addCustomDrawableResourceIfNotPresent(
          RType.DRAWABLE, resourceName, CustomDrawableType.GRAYSCALE_IMAGE);
    } else {
      resourceCollector.addIntResourceIfNotPresent(RType.DRAWABLE, resourceName);
    }
  }

  void processValues(AbsPath root, IsolatedEventBus eventBus, Path valuesDir)
      throws IOException, ResourceParseException {
    for (Path path :
        ProjectFilesystemUtils.getFilesUnderPath(
            root,
            valuesDir,
            EnumSet.of(FileVisitOption.FOLLOW_LINKS),
            ProjectFilesystemUtils.getIgnoreFilter(root, false, ImmutableSet.of()))) {
      if (shouldIgnoreFile(root, path)) {
        continue;
      }
      if (!ProjectFilesystemUtils.isFile(root, path)) {
        eventBus.post(ConsoleEvent.warning("MiniAapt [warning]: ignoring non-file '%s'.", path));
        continue;
      }
      processValuesFile(root, path);
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
  void processValuesFile(AbsPath projectRoot, Path valuesFile)
      throws IOException, ResourceParseException {
    try (InputStream stream = ProjectFilesystemUtils.newFileInputStream(projectRoot, valuesFile)) {
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
          Node typeNode = verifyNodeHasTypeAttribute(valuesFile, node);
          resourceType = typeNode.getNodeValue();
        } else if (resourceType.equals(PUBLIC_TAG)) {
          Node nameAttribute = node.getAttributes().getNamedItem("name");
          if (nameAttribute == null || nameAttribute.getNodeValue().isEmpty()) {
            throw new ResourceParseException(
                "Error parsing file '%s', expected a 'name' attribute in \n'%s'\n",
                valuesFile, node.toString());
          }
          String type = verifyNodeHasTypeAttribute(valuesFile, node).getNodeValue();

          if (!RESOURCE_TYPES.containsKey(type)) {
            throw new ResourceParseException(
                "Invalid resource type '%s' in <public> resource '%s' in file '%s'.",
                type, nameAttribute.getNodeValue(), valuesFile);
          }

          if (!PUBLIC_FILENAME.equals(valuesFile.getFileName().toString())) {
            throw new ResourceParseException(
                "<public> resource '%s' must be declared in res/values/public.xml, but was declared in '%s'",
                nameAttribute.getNodeValue(), valuesFile);
          }
        }

        if (IGNORED_TAGS.contains(resourceType)) {
          continue;
        }

        if (!RESOURCE_TYPES.containsKey(resourceType)) {
          throw new ResourceParseException(
              "Invalid resource type '<%s>' in '%s'.", resourceType, valuesFile);
        }

        RType rType = Objects.requireNonNull(RESOURCE_TYPES.get(resourceType));
        addToResourceCollector(node, rType);
      }
    }
  }

  private Node verifyNodeHasTypeAttribute(Path valuesFile, Node node)
      throws ResourceParseException {
    Node typeNode = node.getAttributes().getNamedItem("type");
    if (typeNode == null || typeNode.getNodeValue().isEmpty()) {
      throw new ResourceParseException(
          "Error parsing file '%s', expected a 'type' attribute in: \n'%s'\n",
          valuesFile, node.toString());
    }
    return typeNode;
  }

  private void addToResourceCollector(Node node, RType rType) throws ResourceParseException {
    String resourceName = sanitizeName(extractNameAttribute(node));

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
            resourceName);

        if (!rawAttrName.startsWith("android:")) {
          resourceCollector.addIntResourceIfNotPresent(RType.ATTR, attrName);
        }
      }

      resourceCollector.addIntArrayResourceIfNotPresent(rType, resourceName, count);
    } else {
      resourceCollector.addIntResourceIfNotPresent(rType, resourceName);
    }
  }

  void processXmlFilesForIds(AbsPath root, ImmutableSet.Builder<RDotTxtEntry> references)
      throws IOException, XPathExpressionException, ResourceParseException {
    for (Path path :
        ProjectFilesystemUtils.getFilesUnderPath(
            root,
            resDirectory.getPath(),
            input -> input.toString().endsWith(".xml"),
            EnumSet.of(FileVisitOption.FOLLOW_LINKS),
            ProjectFilesystemUtils.getIgnoreFilter(root, false, ImmutableSet.of()))) {
      String dirname = resDirectory.relativize(path).getName(0).toString();
      if (path.endsWith("styles.xml")) {
        processStyleFile(root, path, references);
      } else if (!isAValuesDir(dirname)) {
        // Ignore files under values* directories.
        processXmlFile(root, path, references);
      }
    }
  }

  @VisibleForTesting
  void processStyleFile(
      AbsPath projectRoot, Path xmlFile, ImmutableSet.Builder<RDotTxtEntry> references)
      throws IOException, XPathExpressionException, ResourceParseException {
    try (InputStream stream = ProjectFilesystemUtils.newFileInputStream(projectRoot, xmlFile)) {
      Document dom = parseXml(xmlFile, stream);

      XPathExpression expression = ANDROID_ATTR_USAGE_FOR_STYLES;
      NodeList nodesUsingIds = (NodeList) expression.evaluate(dom, XPathConstants.NODESET);
      for (int i = 0; i < nodesUsingIds.getLength(); i++) {
        String resourceName = nodesUsingIds.item(i).getNodeValue();
        if (resourceName.startsWith("?attr")) {
          resourceName = resourceName.substring("?attr/".length());
        } else {
          int colonPosition = resourceName.indexOf(":");
          if (colonPosition > 0) {
            resourceName = resourceName.substring(colonPosition + 1);
          }
        }
        references.add(new FakeRDotTxtEntry(IdType.INT, RType.ATTR, sanitizeName(resourceName)));
      }
    }
  }

  @VisibleForTesting
  void processXmlFile(
      AbsPath projectRoot, Path xmlFile, ImmutableSet.Builder<RDotTxtEntry> references)
      throws IOException, XPathExpressionException, ResourceParseException {
    try (InputStream stream = ProjectFilesystemUtils.newFileInputStream(projectRoot, xmlFile)) {
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
            RType.ID, resourceName.substring(ID_DEFINITION_PREFIX.length()));
      }

      NodeList nodesUsingIds =
          (NodeList) ANDROID_ID_AND_ATTR_USAGE.evaluate(dom, XPathConstants.NODESET);
      for (int i = 0; i < nodesUsingIds.getLength(); i++) {
        String resourceName = nodesUsingIds.item(i).getNodeValue();
        int slashPosition = resourceName.indexOf('/');
        if ((resourceName.charAt(0) != '@' && resourceName.charAt(0) != '?')
            || slashPosition == -1) {
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
        RType rType = Objects.requireNonNull(RESOURCE_TYPES.get(rawRType));

        references.add(new FakeRDotTxtEntry(IdType.INT, rType, sanitizeName(name)));
      }

      if (isVerifyingXmlAttrsEnabled) {
        NodeList allNodes = (NodeList) ALL_ATTRS.evaluate(dom, XPathConstants.NODESET);
        for (int i = 0; i < allNodes.getLength(); i++) {
          String nodeName = allNodes.item(i).getNodeName();
          int colonPosition = nodeName.indexOf(':');
          if (colonPosition == -1) {
            continue;
          }

          String namespace = nodeName.substring(0, colonPosition);
          if (namespace.equals("xmlns")
              || namespace.equals("android")
              || namespace.equals("tools")) {
            continue;
          }

          String attrName = nodeName.substring(colonPosition + 1);
          references.add(new FakeRDotTxtEntry(IdType.INT, RType.ATTR, sanitizeName(attrName)));
        }
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

  private static boolean shouldIgnoreFile(AbsPath root, Path path) throws IOException {
    return ProjectFilesystemUtils.isHidden(root, path)
        || IGNORED_FILE_EXTENSIONS.contains(
            com.google.common.io.Files.getFileExtension(path.getFileName().toString()))
        || AaptStep.isSilentlyIgnored(path);
  }

  @VisibleForTesting
  ImmutableSet<RDotTxtEntry> verifyReferences(AbsPath root, ImmutableSet<RDotTxtEntry> references)
      throws IOException {
    ImmutableSet.Builder<RDotTxtEntry> unresolved = ImmutableSet.builder();
    ImmutableSet.Builder<RDotTxtEntry> definitionsBuilder = ImmutableSet.builder();
    definitionsBuilder.addAll(resourceCollector.getResources());
    for (RelPath depRTxt : pathsToSymbolsOfDeps) {
      Iterable<String> lines =
          ProjectFilesystemUtils.readLines(root, depRTxt.getPath()).stream()
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
  public String getIsolatedStepDescription(IsolatedExecutionContext context) {
    return getShortName() + " " + resDirectory;
  }

  @VisibleForTesting
  static class ResourceParseException extends Exception {

    ResourceParseException(String messageFormat, Object... args) {
      super(String.format(messageFormat, args));
    }
  }

  /**
   * Responsible for collecting resources parsed by {@link MiniAapt} and assigning unique integer
   * ids to those resources. Resource ids are of the type {@code 0x7fxxyyyy}, where {@code xx}
   * represents the resource type, and {@code yyyy} represents the id within that resource type.
   */
  static class RDotTxtResourceCollector {

    private int currentTypeId;
    private final Map<RType, ResourceIdEnumerator> enumerators;
    private final Set<RDotTxtEntry> resources;

    public RDotTxtResourceCollector() {
      this.enumerators = new HashMap<>();
      this.resources = new HashSet<>();
      this.currentTypeId = 1;
    }

    public void addIntResourceIfNotPresent(RType rType, String name) {
      RDotTxtEntry entry = new FakeRDotTxtEntry(IdType.INT, rType, name);
      if (!resources.contains(entry)) {
        addResource(rType, IdType.INT, name, getNextIdValue(rType), null);
      }
    }

    public void addCustomDrawableResourceIfNotPresent(
        RType rType, String name, CustomDrawableType drawableType) {
      RDotTxtEntry entry = new FakeRDotTxtEntry(IdType.INT, rType, name);
      if (!resources.contains(entry)) {
        String idValue = getNextCustomIdValue(rType, drawableType);
        resources.add(new RDotTxtEntry(IdType.INT, rType, name, idValue, drawableType));
      }
    }

    public void addIntArrayResourceIfNotPresent(RType rType, String name, int numValues) {
      addResource(rType, IdType.INT_ARRAY, name, getNextArrayIdValue(rType, numValues), null);
    }

    public void addResource(
        RType rType, IdType idType, String name, String idValue, @Nullable String parent) {
      resources.add(new RDotTxtEntry(idType, rType, name, idValue, parent));
    }

    public Set<RDotTxtEntry> getResources() {
      return Collections.unmodifiableSet(resources);
    }

    ResourceIdEnumerator getEnumerator(RType rType) {
      if (!enumerators.containsKey(rType)) {
        enumerators.put(rType, new ResourceIdEnumerator(currentTypeId++));
      }
      return Objects.requireNonNull(enumerators.get(rType));
    }

    String getNextIdValue(RType rType) {
      return String.format("0x%08x", getEnumerator(rType).next());
    }

    String getNextCustomIdValue(RType rType, CustomDrawableType drawableType) {
      return String.format("0x%08x %s", getEnumerator(rType).next(), drawableType.getIdentifier());
    }

    String getNextArrayIdValue(RType rType, int numValues) {
      // Robolectric expects the array to be populated with the right number of values, irrespective
      // of what the values are.
      ImmutableList.Builder<String> values = ImmutableList.builder();
      for (int id = 0; id < numValues; id++) {
        values.add(String.format("0x%x", getEnumerator(rType).next()));
      }

      return String.format(
          "{ %s }", Joiner.on(RDotTxtEntry.INT_ARRAY_SEPARATOR).join(values.build()));
    }

    private static class ResourceIdEnumerator {

      private int currentId;

      ResourceIdEnumerator(int typeId) {
        this.currentId = 0x7f000000 + 0x10000 * typeId + 1;
      }

      int next() {
        return currentId++;
      }
    }
  }
}
