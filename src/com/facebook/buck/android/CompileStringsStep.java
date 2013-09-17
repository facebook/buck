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

import com.facebook.buck.event.ThrowableLogEvent;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.XmlDomParser;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This {@link Step} takes in a {@link FilterResourcesStep} that provides a list of string resource
 * files (strings.xml), groups them by locales, and for each locale generates a json file with all
 * the string resources for that locale.
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
 * and dumps this map into the json file.</p>
 */
public class CompileStringsStep implements Step {

  @VisibleForTesting
  static final Pattern STRING_FILE_PATTERN = Pattern.compile(
      ".*res/values-([a-z]{2})(?:-r([A-Z]{2}))*/strings.xml");

  private final FilterResourcesStep filterResourcesStep;
  private final Path destinationDir;
  private final ObjectMapper objectMapper;

  @VisibleForTesting
  CompileStringsStep(
      FilterResourcesStep filterResourcesStep,
      Path destinationDir,
      ObjectMapper mapper) {
    this.filterResourcesStep = Preconditions.checkNotNull(filterResourcesStep);
    this.destinationDir = Preconditions.checkNotNull(destinationDir);
    this.objectMapper = Preconditions.checkNotNull(mapper);
  }

  /**
   * Note: The ordering of files in the input list determines which resource value ends up in the
   * output json file, in the event of multiple xml files of a locale sharing the same string
   * resource name - file that appears first in the list wins.
   *
   * @param filterResourcesStep {@link FilterResourcesStep} that filters non english string files.
   * @param destinationDir Output directory for the generated json files.
   */
  public CompileStringsStep(FilterResourcesStep filterResourcesStep, Path destinationDir) {
    this(filterResourcesStep, destinationDir, new ObjectMapper(new JsonFactory()));
  }

  @Override
  public int execute(ExecutionContext context) {
    ImmutableSet<String> filteredStringFiles = filterResourcesStep.getNonEnglishStringFiles();
    ImmutableMultimap<String, String> filesByLocale = groupFilesByLocale(filteredStringFiles);
    ProjectFilesystem filesystem = context.getProjectFilesystem();

    for (String locale : filesByLocale.keySet()) {
      // TODO: Merge base locale resource with the country specific ones
      // eg. es_ES = es_ES + es;
      try {
        ImmutableMap<String, Object> resources = compileStringFiles(filesByLocale.get(locale));
        File jsonFile = filesystem.getFileForRelativePath(destinationDir.resolve(locale + ".json"));
        objectMapper.writeValue(jsonFile, resources);
      } catch (IOException e) {
        context.getBuckEventBus().post(ThrowableLogEvent.create(e,
            "Error creating json string file for locale: %s",
            locale));
        return 1;
      }
    }

    return 0;
  }

  /**
   * Groups a list of file paths matching STRING_FILE_PATTERN by the locale.
   * eg. given the following list:
   *
   * ImmutableSet.of(
   *   '/one/res/values-es/strings.xml',
   *   '/two/res/values-es/strings.xml',
   *   '/three/res/values-pt-rBR/strings.xml',
   *   '/four/res/values/-pt-rPT/strings.xml');
   *
   * returns:
   *
   * ImmutableMap.of(
   *   'es', ImmutableSet.of('/one/res/values-es/strings.xml', '/two/res/values-es/strings.xml'),
   *   'pt_BR', ImmutableSet.of('/three/res/values-pt-rBR/strings.xml'),
   *   'pt_PT', ImmutableSet.of('/four/res/values/-pt-rPT/strings.xml'));
   */
  @VisibleForTesting
  ImmutableMultimap<String, String> groupFilesByLocale(ImmutableSet<String> files) {
    ImmutableMultimap.Builder<String, String> localeToFiles = ImmutableMultimap.builder();

    for (String filepath : files) {
      Matcher matcher = STRING_FILE_PATTERN.matcher(filepath);
      if (!matcher.matches()) {
        continue;
      }
      String baseLocale = matcher.group(1);
      String country = matcher.group(2);
      String locale = country == null ? baseLocale : baseLocale + "_" + country;

      localeToFiles.put(locale, filepath);
    }

    return localeToFiles.build();
  }

  private ImmutableMap<String, Object> compileStringFiles(Collection<String> filepaths)
      throws IOException {

    Map<String, String> stringsMap = Maps.newHashMap();
    Map<String, Map<String, String>> pluralsMap = Maps.newHashMap();
    Map<String, List<String>> arraysMap = Maps.newHashMap();

    for (String stringFilePath : filepaths) {
      File stringFile = (Paths.get(stringFilePath)).toFile();
      Document dom = XmlDomParser.parse(stringFile);

      NodeList stringNodes = dom.getElementsByTagName("string");
      scrapeStringNodes(stringNodes, stringsMap);

      NodeList pluralNodes = dom.getElementsByTagName("plurals");
      scrapePluralsNodes(pluralNodes, pluralsMap);

      NodeList arrayNodes = dom.getElementsByTagName("string-array");
      scrapeStringArrayNodes(arrayNodes, arraysMap);
    }

    ImmutableMap.Builder<String, Object> resourcesBuilder = ImmutableMap.builder();
    resourcesBuilder.putAll(stringsMap);
    resourcesBuilder.putAll(pluralsMap);
    resourcesBuilder.putAll(arraysMap);
    return resourcesBuilder.build();
  }


  /**
   * Scrapes string resource names and values from the list of xml nodes passed and populates
   * {@code stringsMap}, ignoring resource names that are already present in the map.
   *
   * @param stringNodes A list of {@code <string></string>} nodes.
   * @param stringsMap Map from string resource name to its value.
   */
  @VisibleForTesting
  void scrapeStringNodes(NodeList stringNodes, Map<String, String> stringsMap) {
    for (int i = 0; i < stringNodes.getLength(); ++i) {
      Node node = stringNodes.item(i);
      String resourceName = node.getAttributes().getNamedItem("name").getNodeValue();
      // Ignore a resource if it has already been found.
      if (!stringsMap.containsKey(resourceName)) {
        stringsMap.put(resourceName, node.getTextContent());
      }
    }
  }

  /**
   * Similar to {@code scrapeStringNodes}, but for plurals nodes.
   */
  @VisibleForTesting
  void scrapePluralsNodes(
      NodeList pluralNodes,
      Map<String, Map<String, String>> pluralsMap) {

    for (int i = 0; i < pluralNodes.getLength(); ++i) {
      Node node = pluralNodes.item(i);
      String resourceName = node.getAttributes().getNamedItem("name").getNodeValue();
      // Ignore a resource if it has already been found.
      if (pluralsMap.containsKey(resourceName)) {
        continue;
      }
      ImmutableMap.Builder<String, String> quantityToStringBuilder = ImmutableMap.builder();

      NodeList itemNodes = ((Element) node).getElementsByTagName("item");
      for (int j = 0; j < itemNodes.getLength(); ++j) {
        Node itemNode = itemNodes.item(j);
        String quantity = itemNode.getAttributes().getNamedItem("quantity").getNodeValue();
        quantityToStringBuilder.put(quantity, itemNode.getTextContent());
      }
      pluralsMap.put(resourceName, quantityToStringBuilder.build());
    }
  }

  /**
   * Similar to {@code scrapeStringNodes}, but for string array nodes.
   */
  @VisibleForTesting
  void scrapeStringArrayNodes(NodeList arrayNodes, Map<String, List<String>> arraysMap) {
    for (int i = 0; i < arrayNodes.getLength(); ++i) {
      Node node = arrayNodes.item(i);
      String resourceName = node.getAttributes().getNamedItem("name").getNodeValue();
      // Ignore a resource if it has already been found.
      if (arraysMap.containsKey(resourceName)) {
        continue;
      }
      ImmutableList.Builder<String> arrayItemsBuilder = ImmutableList.builder();

      NodeList itemNodes = ((Element)node).getElementsByTagName("item");
      for (int j = 0; j < itemNodes.getLength(); ++j) {
        arrayItemsBuilder.add(itemNodes.item(j).getTextContent());
      }
      arraysMap.put(resourceName, arrayItemsBuilder.build());
    }
  }

  @Override
  public String getShortName() {
    return "compile_strings";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return "Combine, parse string resource xml files into one json file per locale.";
  }
}
