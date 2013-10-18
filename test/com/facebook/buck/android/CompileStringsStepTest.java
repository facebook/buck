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

import static org.easymock.EasyMock.expect;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.XmlDomParser;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.io.Files;

import org.easymock.EasyMockSupport;
import org.junit.Test;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
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
  public void testRDotTxtContentsPattern() {
    testContentRegex("  int string r_name 0xdeadbeef", false, null, null, null);
    testContentRegex("int string r_name 0xdeadbeef  ", false, null, null, null);
    testContentRegex("int string r_name 0xdeadbeef", true, "string", "r_name", "deadbeef");
    testContentRegex("int string r_name 0x", false, null, null, null);
    testContentRegex("int array r_name 0xdead", true, "array", "r_name", "dead");
    testContentRegex("int plurals r_name 0xdead", true, "plurals", "r_name", "dead");
    testContentRegex("int plural r_name 0xdead", false, null, null, null);
    testContentRegex("int plurals r name 0xdead", false, null, null, null);
    testContentRegex("int[] string r_name 0xdead", false, null, null, null);
  }

  private void testContentRegex(
      String input,
      boolean matches,
      String resourceType,
      String resourceName,
      String resourceId) {

    Matcher matcher = CompileStringsStep.R_DOT_TXT_STRING_RESOURCE_PATTERN.matcher(input);
    assertEquals(matches, matcher.matches());
    if (!matches) {
      return;
    }
    assertEquals("Resource type does not match.", resourceType, matcher.group(1));
    assertEquals("Resource name does not match.", resourceName, matcher.group(2));
    assertEquals("Resource id does not match.", resourceId, matcher.group(3));
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

    assertEquals("Incorrect grouping of files by locale.", expectedMap, groupedByLocale);
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

    Map<Integer, String> stringsMap = Maps.newHashMap();
    CompileStringsStep step = createNonExecutingStep();
    step.addResourceNameToIdMap(ImmutableMap.of(
        "name1", 1,
        "name2", 2,
        "name3", 3,
        "name4", 4,
        "name5", 5));
    step.scrapeStringNodes(stringNodes, stringsMap);

    assertEquals(
        "Incorrect map of resource id to string values.",
        ImmutableMap.of(
            1, "Value1",
            2, "Value with space",
            3, "Value with \"quotes\"",
            4, "",
            5, "Value with %1$s"),
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

    Map<Integer, ImmutableMap<String, String>> pluralsMap = Maps.newHashMap();
    CompileStringsStep step = createNonExecutingStep();
    step.addResourceNameToIdMap(ImmutableMap.of(
        "name1", 1,
        "name2", 2,
        "name3", 3));
    step.scrapePluralsNodes(pluralsNodes, pluralsMap);

    assertEquals(
        "Incorrect map of resource id to plural values.",
        ImmutableMap.of(
            1, ImmutableMap.of(
                "zero", "%d people saw this",
                "one", "%d person saw this",
                "many", "%d people saw this"),
            2, ImmutableMap.of(
                "zero", "%d people ate this",
                "many", "%d people ate this"),
            3, ImmutableMap.of()
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

    Multimap<Integer, String> arraysMap = ArrayListMultimap.create();
    CompileStringsStep step = createNonExecutingStep();
    step.addResourceNameToIdMap(ImmutableMap.of(
        "name1", 1,
        "name2", 2,
        "name3", 3));
    step.scrapeStringArrayNodes(arrayNodes, arraysMap);

    assertEquals(
        "Incorrect map of resource id to string arrays.",
        ImmutableMultimap.builder()
            .put(1, "Value11")
            .put(1, "Value12")
            .put(2, "Value21")
            .build(),
        arraysMap
    );
  }

  private CompileStringsStep createNonExecutingStep() {
    return new CompileStringsStep(
        createMock(FilterResourcesStep.class),
        createMock(Path.class),
        createMock(Path.class));
  }

  private String createResourcesXml(String contents) {
    return XML_HEADER + "<resources>" + contents + "</resources>";
  }

  @Test
  public void testSuccessfulStepExecution() throws IOException {
    Path destinationDir = Paths.get("");
    Path rDotJavaSrcDir = Paths.get("");

    ExecutionContext context = createMock(ExecutionContext.class);
    FakeProjectFileSystem fileSystem = new FakeProjectFileSystem();
    expect(context.getProjectFilesystem()).andStubReturn(fileSystem);

    FilterResourcesStep filterResourcesStep = createMock(FilterResourcesStep.class);
    expect(filterResourcesStep.getNonEnglishStringFiles()).andReturn(ImmutableSet.of(
        FIRST_FILE,
        SECOND_FILE,
        THIRD_FILE,
        FOURTH_FILE));

    replayAll();
    CompileStringsStep step = new CompileStringsStep(
        filterResourcesStep,
        rDotJavaSrcDir,
        destinationDir);
    assertEquals(0, step.execute(context));
    Map<String, byte[]> fileContentsMap = fileSystem.getFileContents();
    assertEquals("Incorrect number of string files written.", 3, fileContentsMap.size());
    for (Map.Entry<String, byte[]> entry : fileContentsMap.entrySet()) {
      File expectedFile = Paths.get(TESTDATA_DIR + entry.getKey()).toFile();
      assertArrayEquals(Files.toByteArray(expectedFile), fileContentsMap.get(entry.getKey()));
    }

    verifyAll();
  }


  private static class FakeProjectFileSystem extends ProjectFilesystem {

    private ImmutableMap.Builder<String, byte[]> fileContentsMapBuilder = ImmutableMap.builder();

    public FakeProjectFileSystem() {
      super(new File("."));
    }

    @Override
    public File getFileForRelativePath(Path path) {
      return path.toFile();
    }

    @Override
    public List<String> readLines(Path path) throws IOException {
      Path fullPath = Paths.get(TESTDATA_DIR).resolve(path);
      return Files.readLines(fullPath.toFile(), Charset.defaultCharset());
    }

    @Override
    public void writeBytesToPath(byte[] content, Path path) {
      fileContentsMapBuilder.put(path.getFileName().toString(), content);
    }

    public Map<String, byte[]> getFileContents() {
      return fileContentsMapBuilder.build();
    }
  }
}
