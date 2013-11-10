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

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.getCurrentArguments;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.XmlDomParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;

import org.easymock.EasyMockSupport;
import org.easymock.IAnswer;
import org.junit.Test;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;

public class CompileStringsStepTest extends EasyMockSupport {

  private static final String XML_HEADER = "<?xml version='1.0' encoding='utf-8'?>";

  private static final String TESTDATA_DIR = "testdata/com/facebook/buck/android/";
  private static final String FIRST_FILE = TESTDATA_DIR + "first/res/values-es/strings.xml";
  private static final String SECOND_FILE = TESTDATA_DIR + "second/res/values-es/strings.xml";
  private static final String THIRD_FILE = TESTDATA_DIR + "third/res/values-pt/strings.xml";
  private static final String FOURTH_FILE = TESTDATA_DIR + "third/res/values-pt-rBR/strings.xml";

  @Test
  public void testStringFilePattern() {
    testStringPathRegex("res/values-es/strings.xml", true, "es", null);
    testStringPathRegex("/one/res/values-es/strings.xml", true, "es", null);
    testStringPathRegex("/two/res/values-es-rUS/strings.xml", true, "es", "US");
    // Not matching strings.
    testStringPathRegex("/one/res/values/strings.xml", false, null, null);
    testStringPathRegex("/one/res/values-/strings.xml", false, null, null);
    testStringPathRegex("/one/res/values-e/strings.xml", false, null, null);
    testStringPathRegex("/one/res/values-esc/strings.xml", false, null, null);
    testStringPathRegex("/one/res/values-es-rU/strings.xml", false, null, null);
    testStringPathRegex("/one/res/values-es-rUSA/strings.xml", false, null, null);
    testStringPathRegex("/one/res/values-es-RUS/strings.xml", false, null, null);
    testStringPathRegex("/one/res/values-rUS/strings.xml", false, null, null);
  }

  private void testStringPathRegex(String input, boolean matches, String locale, String country) {
    Matcher matcher = CompileStringsStep.STRING_FILE_PATTERN.matcher(input);
    assertEquals(matches, matcher.matches());
    if (!matches) {
      return;
    }
    assertEquals(locale, matcher.group(1));
    assertEquals(country, matcher.group(2));
  }

  @Test
  public void testGroupFilesByLocale() {
    ImmutableSet<String> files = ImmutableSet.of(
        "/project/dir/res/values-da/strings.xml",
        "/project/dir/res/values-da-rAB/strings.xml",
        "/project/dir/dontmatch/res/values/strings.xml",
        "/project/groupme/res/values-da/strings.xml",
        "/project/groupmetoo/res/values-da-rAB/strings.xml",
        "/project/foreveralone/res/values-es/strings.xml"
    );

    ImmutableMultimap<String, String> groupedByLocale =
        createNonExecutingStep().groupFilesByLocale(files);

    ImmutableMultimap<String, String> expectedMap =
        ImmutableMultimap.<String, String>builder()
          .putAll("da", ImmutableSet.<String>of(
              "/project/dir/res/values-da/strings.xml",
              "/project/groupme/res/values-da/strings.xml"))
          .putAll("da_AB", ImmutableSet.<String>of(
              "/project/dir/res/values-da-rAB/strings.xml",
              "/project/groupmetoo/res/values-da-rAB/strings.xml"))
          .putAll("es", ImmutableSet.<String>of("/project/foreveralone/res/values-es/strings.xml"))
          .build();

    assertEquals(expectedMap, groupedByLocale);
  }

  @Test
  public void testScrapeStringNodes() throws IOException {
    String xmlInput =
          "<string name='name1'>Value1</string>" +
          "<string name='name2'>Value with space</string>" +
          "<string name='name3'>Value with \"quotes\"</string>" +
          "<string name='name4'></string>" +
          "<string name='name3'>IGNORE</string>" + // ignored because "name3" already found
          "<string name='name5'>Value with %1$s</string>";
    NodeList stringNodes = XmlDomParser.parse(createResourcesXml(xmlInput))
        .getElementsByTagName("string");

    Map<String, String> stringsMap = new HashMap<>();
    createNonExecutingStep().scrapeStringNodes(stringNodes, stringsMap);

    assertEquals(
        ImmutableMap.of(
            "name1", "Value1",
            "name2", "Value with space",
            "name3", "Value with \"quotes\"",
            "name4", "",
            "name5", "Value with %1$s"),
        stringsMap
    );
  }

  @Test
  public void testScrapePluralsNodes() throws IOException {
    String xmlInput =
          "<plurals name='name1'>" +
            "<item quantity='zero'>%d people saw this</item>" +
            "<item quantity='one'>%d person saw this</item>" +
            "<item quantity='many'>%d people saw this</item>" +
          "</plurals>" +
          "<plurals name='name2'>" +
            "<item quantity='zero'>%d people ate this</item>" +
            "<item quantity='many'>%d people ate this</item>" +
          "</plurals>" +
          "<plurals name='name3'></plurals>" + // Test empty array.
          "<plurals name='name2'></plurals>"; // Ignored since "name2" already found.
    NodeList pluralsNodes = XmlDomParser.parse(createResourcesXml(xmlInput))
        .getElementsByTagName("plurals");

    Map<String, ImmutableMap<String, String>> pluralsMap = new HashMap<>();
    createNonExecutingStep().scrapePluralsNodes(pluralsNodes, pluralsMap);

    assertEquals(
        ImmutableMap.of(
            "name1", ImmutableMap.of(
                "zero", "%d people saw this",
                "one", "%d person saw this",
                "many", "%d people saw this"),
            "name2", ImmutableMap.of(
                "zero", "%d people ate this",
                "many", "%d people ate this"),
            "name3", ImmutableMap.of()
        ),
        pluralsMap
    );
  }

  @Test
  public void testScrapeStringArrayNodes() throws IOException {
    String xmlInput =
          "<string-array name='name1'>" +
            "<item>Value11</item>" +
            "<item>Value12</item>" +
          "</string-array>" +
          "<string-array name='name2'>" +
            "<item>Value21</item>" +
          "</string-array>" +
          "<string-array name='name3'></string-array>" +
          "<string-array name='name2'>" +
            "<item>ignored</item>" + // Ignored because "name2" already found above.
          "</string-array>";

    NodeList arrayNodes = XmlDomParser.parse(createResourcesXml(xmlInput))
        .getElementsByTagName("string-array");

    Multimap<String, String> arraysMap = ArrayListMultimap.create();
    createNonExecutingStep().scrapeStringArrayNodes(arrayNodes, arraysMap);

    assertEquals(
        ImmutableMultimap.builder()
            .put("name1", "Value11")
            .put("name1", "Value12")
            .put("name2", "Value21")
            .build(),
        arraysMap
    );
  }

  private CompileStringsStep createNonExecutingStep() {
    return new CompileStringsStep(
        createMock(FilterResourcesStep.class),
        createMock(Path.class),
        createMock(ObjectMapper.class));
  }

  private String createResourcesXml(String contents) {
    return XML_HEADER + "<resources>" + contents + "</resources>";
  }

  @Test
  public void testSuccessfulStepExecution() throws IOException {
    Path destinationDir = Paths.get("");

    ExecutionContext context = createMock(ExecutionContext.class);
    ProjectFilesystem filesystem = createMock(ProjectFilesystem.class);
    expect(filesystem.getFileForRelativePath(anyObject(Path.class))).andAnswer(new IAnswer<File>() {
      @Override
      public File answer() throws Throwable {
        return ((Path)getCurrentArguments()[0]).toFile();
      }
    }).times(3);
    expect(context.getProjectFilesystem()).andReturn(filesystem);

    ObjectMapper mapper = createMock(ObjectMapper.class);
    mapper.writeValue(anyObject(File.class), anyObject());

    final ImmutableMap.Builder<String, ImmutableMap<String, Object>> capturedResourcesBuilder =
        ImmutableMap.builder();
    expectLastCall().andAnswer(new IAnswer<Object>() {
      @Override
      public Object answer() throws Throwable {
        File file = (File)getCurrentArguments()[0];
        capturedResourcesBuilder.put(file.getName(), getMapFromArgs());
        return null;
      }

      @SuppressWarnings("unchecked")
      private ImmutableMap<String, Object> getMapFromArgs() {
        return (ImmutableMap<String, Object>) getCurrentArguments()[1];
      }
    }).times(3);

    FilterResourcesStep filterResourcesStep = createMock(FilterResourcesStep.class);
    expect(filterResourcesStep.getNonEnglishStringFiles()).andReturn(ImmutableSet.of(
        FIRST_FILE,
        SECOND_FILE,
        THIRD_FILE,
        FOURTH_FILE));

    replayAll();
    CompileStringsStep step = new CompileStringsStep(filterResourcesStep, destinationDir, mapper);
    assertEquals(0, step.execute(context));
    assertEquals(
        ImmutableMap.of(
            "es.json", ImmutableMap.of(
                "name1_1", "Value11",
                "name1_2", "Value12",
                "name1_3", "Value13",
                "name2_1", "Value21",
                "name2_2", "Value22"),
            "pt.json", ImmutableMap.of(
                "name3_1", "Value31",
                "name3_2", "Value32",
                "name3_3", "Value33"),
            "pt_BR.json", ImmutableMap.of(
                "name3_1", "Value311",
                "name3_2", "Value32",
                "name3_3", "Value33")
        ),
        capturedResourcesBuilder.build());

    verifyAll();
  }
}
