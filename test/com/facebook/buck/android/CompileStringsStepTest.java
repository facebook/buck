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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.XmlDomParser;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.io.Files;

import org.easymock.EasyMockSupport;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

public class CompileStringsStepTest extends EasyMockSupport {

  private static final String XML_HEADER = "<?xml version='1.0' encoding='utf-8'?>";

  private Path testdataDir;
  private Path firstFile;
  private Path secondFile;
  private Path thirdFile;
  private Path fourthFile;

  @Before
  public void findTestData() {
    testdataDir = TestDataHelper.getTestDataDirectory(this).resolve("compile_strings");

    firstFile = testdataDir.resolve("first/res/values-es/strings.xml");
    secondFile = testdataDir.resolve("second/res/values-es/strings.xml");
    thirdFile = testdataDir.resolve("third/res/values-pt/strings.xml");
    fourthFile = testdataDir.resolve("third/res/values-pt-rBR/strings.xml");
  }

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
    Path path0 = Paths.get("project/dir/res/values-da/strings.xml");
    Path path1 = Paths.get("project/dir/res/values-da-rAB/strings.xml");
    Path path2 = Paths.get("project/dir/dontmatch/res/values/strings.xml");
    Path path3 = Paths.get("project/groupme/res/values-da/strings.xml");
    Path path4 = Paths.get("project/groupmetoo/res/values-da-rAB/strings.xml");
    Path path5 = Paths.get("project/foreveralone/res/values-es/strings.xml");
    ImmutableSet<Path> files = ImmutableSet.of(path0, path1, path2, path3, path4, path5);

    ImmutableMultimap<String, Path> groupedByLocale =
        createNonExecutingStep().groupFilesByLocale(ImmutableSet.copyOf(files));

    ImmutableMultimap<String, Path> expectedMap =
        ImmutableMultimap.<String, Path>builder()
          .putAll("da", ImmutableSet.of(path0, path3))
          .putAll("da_AB", ImmutableSet.of(path1, path4))
          .putAll("es", ImmutableSet.of(path5))
          .build();

    assertEquals(
        "Result of CompileStringsStep.groupFilesByLocale() should match the expected value.",
        expectedMap,
        groupedByLocale);
  }

  @Test
  public void testScrapeStringNodes() throws IOException, SAXException {
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
        stringsMap);
  }

  @Test
  public void testScrapePluralsNodes() throws IOException, SAXException {
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
            3, ImmutableMap.of()),
        pluralsMap
    );
  }

  @Test
  public void testScrapeStringArrayNodes() throws IOException, SAXException {
    String xmlInput =
          "<string-array name='name1'>" +
            "<item>Value12</item>" +
            "<item>Value11</item>" +
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

    Map<Integer, ImmutableList<String>> arraysMap = Maps.newTreeMap();
    CompileStringsStep step = createNonExecutingStep();
    step.addResourceNameToIdMap(ImmutableMap.of(
        "name1", 1,
        "name2", 2,
        "name3", 3));
    step.scrapeStringArrayNodes(arrayNodes, arraysMap);

    assertEquals(
        "Incorrect map of resource id to string arrays.",
        ImmutableMap.of(
            1, ImmutableList.of("Value12", "Value11"),
            2, ImmutableList.of("Value21")),
        arraysMap);
  }

  private CompileStringsStep createNonExecutingStep() {
    return new CompileStringsStep(
        ImmutableSet.<Path>of(),
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

    ImmutableSet<Path> filteredStringFiles = ImmutableSet.of(
        firstFile,
        secondFile,
        thirdFile,
        fourthFile);

    replayAll();
    CompileStringsStep step = new CompileStringsStep(
        filteredStringFiles,
        rDotJavaSrcDir,
        destinationDir);
    assertEquals(0, step.execute(context));
    Map<String, byte[]> fileContentsMap = fileSystem.getFileContents();
    assertEquals("Incorrect number of string files written.", 3, fileContentsMap.size());
    for (Map.Entry<String, byte[]> entry : fileContentsMap.entrySet()) {
      File expectedFile = testdataDir.resolve(entry.getKey()).toFile();
      assertArrayEquals(createBinaryStream(expectedFile), fileContentsMap.get(entry.getKey()));
    }

    verifyAll();
  }

  private byte[] createBinaryStream(File expectedFile) throws IOException {
    try (
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      DataOutputStream stream = new DataOutputStream(bos)) {
      for (String line : Files.readLines(expectedFile, Charset.defaultCharset())) {
        for (String token : Splitter.on('|').split(line)) {
          char dataType = token.charAt(0);
          String value = token.substring(2);
          switch (dataType) {
            case 'i':
              stream.writeInt(Integer.parseInt(value));
              break;
            case 's':
              stream.writeShort(Integer.parseInt(value));
              break;
            case 'b':
              stream.writeByte(Integer.parseInt(value));
              break;
            case 't':
              stream.write(value.getBytes());
              break;
            default:
              throw new RuntimeException("Unexpected data type in .fbstr file: " + dataType);
          }
        }
      }

      return bos.toByteArray();
    }
  }


  private class FakeProjectFileSystem extends ProjectFilesystem {

    private ImmutableMap.Builder<String, byte[]> fileContentsMapBuilder = ImmutableMap.builder();

    public FakeProjectFileSystem() {
      super(Paths.get("."));
    }

    @Override
    public File getFileForRelativePath(Path path) {
      return path.toFile();
    }

    @Override
    public List<String> readLines(Path path) throws IOException {
      Path fullPath = testdataDir.resolve(path);
      return Files.readLines(fullPath.toFile(), Charset.defaultCharset());
    }

    @Override
    public void writeBytesToPath(byte[] content, Path path, FileAttribute<?>... attrs) {
      fileContentsMapBuilder.put(path.getFileName().toString(), content);
    }

    public Map<String, byte[]> getFileContents() {
      return fileContentsMapBuilder.build();
    }
  }
}
