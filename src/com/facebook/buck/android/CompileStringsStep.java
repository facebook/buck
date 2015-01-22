/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.XmlDomParser;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This {@link Step} takes in a {@link FilterResourcesStep} that provides a list of string resource
 * files (strings.xml), groups them by locales, and for each locale generates a file with all the
 * string resources for that locale.
 *
 * <p>A typical strings.xml file looks like:
 * <pre>
 *   {@code
 *   <?xml version="1.0" encoding="utf-8"?>
 *   <resources>
 *     <string name="resource_name1">I am a string.</string>
 *     <string name="resource_name2">I am another string.</string>
 *     <plurals name="time_hours_ago">
 *       <item quantity="one">1 minute ago</item>
 *       <item quantity="other">%d minutes ago</item>
 *     </plurals>
 *     <string-array name="logging_levels">
 *       <item>Default</item>
 *       <item>Verbose</item>
 *       <item>Debug</item>
 *     </string-array>
 *   </resources>
 *   }
 * </pre></p>
 *
 * <p>For more information on the xml file format, refer to:
 * <a href="http://developer.android.com/guide/topics/resources/string-resource.html">
 *   String Resources - Android Developers
 * </a></p>
 *
 * <p>So for each supported locale in a project, this step goes through all such xml files for that
 * locale, and builds a map of resource name to resource value, where resource value is either:
 * <ol>
 *   <li> a string </li>
 *   <li> a map of plurals </li>
 *   <li> a list of strings </li>
 * </ol>
 * and dumps this map into the output file. See {@link StringResources} for the file format.</p>
 */
public class CompileStringsStep implements Step {

  @VisibleForTesting
  static final Pattern STRING_FILE_PATTERN = Pattern.compile(
      ".*res/values-([a-z]{2})(?:-r([A-Z]{2}))*/strings.xml");

  @VisibleForTesting
  static final Pattern R_DOT_TXT_STRING_RESOURCE_PATTERN = Pattern.compile(
      "^int (string|plurals|array) (\\w+) 0x([0-9a-f]+)$");

  private final ImmutableSet<Path> filteredStringFiles;
  private final Path rDotTxtDir;
  private final Path destinationDir;
  private final Map<String, String> regionSpecificToBaseLocaleMap;
  private final Map<String, Integer> resourceNameToIdMap;

  /**
   * Note: The ordering of files in the input list determines which resource value ends up in the
   * output json file, in the event of multiple xml files of a locale sharing the same string
   * resource name - file that appears first in the list wins.
   *
   * @param filteredStringFiles Set containing paths to non-english
   *     string files, matching {@link FilterResourcesStep#NON_ENGLISH_STRING_PATH} regex.
   * @param rDotTxtDir Path to the directory where aapt generates R.txt file along with the
   *     final R.java files per package.
   * @param destinationDir Output directory for the generated json files.
   */
  public CompileStringsStep(
      ImmutableSet<Path> filteredStringFiles,
      Path rDotTxtDir,
      Path destinationDir) {
    this.filteredStringFiles = filteredStringFiles;
    this.rDotTxtDir = rDotTxtDir;
    this.destinationDir = destinationDir;
    this.regionSpecificToBaseLocaleMap = Maps.newHashMap();
    this.resourceNameToIdMap = Maps.newHashMap();
  }

  @Override
  public int execute(ExecutionContext context) {
    ProjectFilesystem filesystem = context.getProjectFilesystem();
    try {
      buildResourceNameToIdMap(filesystem, rDotTxtDir.resolve("R.txt"), resourceNameToIdMap);
    } catch (IOException e) {
      context.logError(e, "Failure parsing R.txt file.");
      return 1;
    }

    ImmutableMultimap<String, Path> filesByLocale = groupFilesByLocale(filteredStringFiles);
    Map<String, StringResources> resourcesByLocale = Maps.newHashMap();
    for (String locale : filesByLocale.keySet()) {
      try {
        resourcesByLocale.put(locale, compileStringFiles(filesystem, filesByLocale.get(locale)));
      } catch (IOException | SAXException e) {
        context.logError(e, "Error parsing string file for locale: %s", locale);
        return 1;
      }
    }

    // Merge region specific locale resources with the corresponding base locale resources.
    //
    // For example, if there are separate string resources in an android project for locale
    // "es" and "es_US", when an application running on a device with locale set to "Spanish
    // (United States)" requests for a string, the Android runtime first looks for the string in
    // "es_US" set of resources, and if not found, returns the resource from the "es" set.
    // We merge these because we want the individual string json files to be self contained for
    // simplicity.
    for (String regionSpecificLocale : regionSpecificToBaseLocaleMap.keySet()) {
      String baseLocale = regionSpecificToBaseLocaleMap.get(regionSpecificLocale);
      if (!resourcesByLocale.containsKey(baseLocale)) {
        continue;
      }

      resourcesByLocale.put(regionSpecificLocale,
          resourcesByLocale.get(regionSpecificLocale)
              .getMergedResources(resourcesByLocale.get(baseLocale)));
    }

    for (String locale : filesByLocale.keySet()) {
      try {
        filesystem.writeBytesToPath(resourcesByLocale.get(locale).getBinaryFileContent(),
            destinationDir.resolve(locale + ".fbstr"));
      } catch (IOException e) {
        context.logError(e, "Error creating binary file for locale: %s", locale);
        return 1;
      }
    }

    return 0;
  }

  /**
   * Groups a list of file paths matching STRING_FILE_PATTERN by the locale.
   *
   * eg. given the following list:
   *
   * ImmutableSet.of(
   *   Paths.get("one/res/values-es/strings.xml"),
   *   Paths.get("two/res/values-es/strings.xml"),
   *   Paths.get("three/res/values-pt-rBR/strings.xml"),
   *   Paths.get("four/res/values/-pt-rPT/strings.xml"));
   *
   * returns:
   *
   * ImmutableMap.of(
   *   "es", ImmutableSet.of(Paths.get("one/res/values-es/strings.xml"),
   *        Paths.get("two/res/values-es/strings.xml")),
   *   "pt_BR", ImmutableSet.of(Paths.get("three/res/values-pt-rBR/strings.xml'),
   *   "pt_PT", ImmutableSet.of(Paths.get("four/res/values/-pt-rPT/strings.xml")));
   */
  @VisibleForTesting
  ImmutableMultimap<String, Path> groupFilesByLocale(ImmutableSet<Path> files) {
    ImmutableMultimap.Builder<String, Path> localeToFiles = ImmutableMultimap.builder();

    for (Path filepath : files) {
      Matcher matcher = STRING_FILE_PATTERN.matcher(filepath.toString());
      if (!matcher.matches()) {
        continue;
      }

      String baseLocale = matcher.group(1);
      String country = matcher.group(2);
      String locale = country == null ? baseLocale : baseLocale + "_" + country;
      if (country != null && !regionSpecificToBaseLocaleMap.containsKey(locale)) {
        regionSpecificToBaseLocaleMap.put(locale, baseLocale);
      }

      localeToFiles.put(locale, filepath);
    }

    return localeToFiles.build();
  }

  /**
   * Parses the R.txt file generated by aapt, looks for resources of type {@code string},
   * {@code plurals} and {@code array}, and builds a map of resource names to their corresponding
   * ids.
   */
  public static void buildResourceNameToIdMap(
      ProjectFilesystem filesystem,
      Path pathToRDotTxtFile,
      Map<String, Integer> resultMap
  ) throws IOException {
    List<String> fileLines = filesystem.readLines(pathToRDotTxtFile);
    for (String line : fileLines) {
      Matcher matcher = R_DOT_TXT_STRING_RESOURCE_PATTERN.matcher(line);
      if (!matcher.matches()) {
        continue;
      }
      resultMap.put(matcher.group(2), Integer.parseInt(matcher.group(3), 16));
    }
  }

  private StringResources compileStringFiles(
      ProjectFilesystem filesystem,
      Collection<Path> filepaths) throws IOException, SAXException {
    TreeMap<Integer, String> stringsMap = Maps.newTreeMap();
    TreeMap<Integer, ImmutableMap<String, String>> pluralsMap = Maps.newTreeMap();
    TreeMap<Integer, ImmutableList<String>> arraysMap = Maps.newTreeMap();

    for (Path stringFilePath : filepaths) {
      Document dom = XmlDomParser.parse(filesystem.getFileForRelativePath(stringFilePath));

      NodeList stringNodes = dom.getElementsByTagName("string");
      scrapeStringNodes(stringNodes, stringsMap);

      NodeList pluralNodes = dom.getElementsByTagName("plurals");
      scrapePluralsNodes(pluralNodes, pluralsMap);

      NodeList arrayNodes = dom.getElementsByTagName("string-array");
      scrapeStringArrayNodes(arrayNodes, arraysMap);
    }

    return new StringResources(stringsMap, pluralsMap, arraysMap);
  }


  /**
   * Scrapes string resource names and values from the list of xml nodes passed and populates
   * {@code stringsMap}, ignoring resource names that are already present in the map.
   *
   * @param stringNodes A list of {@code <string></string>} nodes.
   * @param stringsMap Map from string resource name to its value.
   */
  @VisibleForTesting
  void scrapeStringNodes(NodeList stringNodes, Map<Integer, String> stringsMap) {
    for (int i = 0; i < stringNodes.getLength(); ++i) {
      Node node = stringNodes.item(i);
      String resourceName = node.getAttributes().getNamedItem("name").getNodeValue();
      if (!resourceNameToIdMap.containsKey(resourceName)) {
        continue;
      }
      int resourceId = Preconditions.checkNotNull(resourceNameToIdMap.get(resourceName));
      // Ignore a resource if it has already been found.
      if (!stringsMap.containsKey(resourceId)) {
        stringsMap.put(resourceId, node.getTextContent());
      }
    }
  }

  /**
   * Similar to {@code scrapeStringNodes}, but for plurals nodes.
   */
  @VisibleForTesting
  void scrapePluralsNodes(
      NodeList pluralNodes,
      Map<Integer, ImmutableMap<String, String>> pluralsMap) {

    for (int i = 0; i < pluralNodes.getLength(); ++i) {
      Node node = pluralNodes.item(i);
      String resourceName = node.getAttributes().getNamedItem("name").getNodeValue();
      if (!resourceNameToIdMap.containsKey(resourceName)) {
        continue;
      }
      int resourceId = Preconditions.checkNotNull(resourceNameToIdMap.get(resourceName));

      // Ignore a resource if it has already been found.
      if (pluralsMap.containsKey(resourceId)) {
        continue;
      }
      ImmutableMap.Builder<String, String> quantityToStringBuilder = ImmutableMap.builder();

      NodeList itemNodes = ((Element) node).getElementsByTagName("item");
      for (int j = 0; j < itemNodes.getLength(); ++j) {
        Node itemNode = itemNodes.item(j);
        String quantity = itemNode.getAttributes().getNamedItem("quantity").getNodeValue();
        quantityToStringBuilder.put(quantity, itemNode.getTextContent());
      }
      pluralsMap.put(resourceId, quantityToStringBuilder.build());
    }
  }

  /**
   * Similar to {@code scrapeStringNodes}, but for string array nodes.
   */
  @VisibleForTesting
  void scrapeStringArrayNodes(NodeList arrayNodes, Map<Integer, ImmutableList<String>> arraysMap) {
    for (int i = 0; i < arrayNodes.getLength(); ++i) {
      Node node = arrayNodes.item(i);
      String resourceName = node.getAttributes().getNamedItem("name").getNodeValue();
      // Ignore a resource if R.txt does not contain an entry for it.
      if (!resourceNameToIdMap.containsKey(resourceName)) {
        continue;
      }

      int resourceId = Preconditions.checkNotNull(resourceNameToIdMap.get(resourceName));
      // Ignore a resource if it has already been found.
      if (arraysMap.containsKey(resourceId)) {
        continue;
      }

      ImmutableList.Builder<String> arrayValues = ImmutableList.builder();

      NodeList itemNodes = ((Element) node).getElementsByTagName("item");
      if (itemNodes.getLength() == 0) {
        continue;
      }
      for (int j = 0; j < itemNodes.getLength(); ++j) {
        arrayValues.add(itemNodes.item(j).getTextContent());
      }
      arraysMap.put(resourceId, arrayValues.build());
    }
  }

  /**
   * Used in unit tests to inject the resource name to id map.
   */
  @VisibleForTesting
  void addResourceNameToIdMap(Map<String, Integer> nameToIdMap) {
    resourceNameToIdMap.putAll(nameToIdMap);
  }

  @Override
  public String getShortName() {
    return "compile_strings";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "Combine, parse string resource xml files into one binary file per locale.";
  }
}
