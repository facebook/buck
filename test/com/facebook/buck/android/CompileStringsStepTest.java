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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.android.StringResources.Gender;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemDelegate;
import com.facebook.buck.io.filesystem.impl.DefaultProjectFilesystemFactory;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TestExecutionContext;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.xml.XmlDomParser;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class CompileStringsStepTest {

  private static final String XML_HEADER = "<?xml version='1.0' encoding='utf-8'?>";

  private Path testdataDir;
  private Path firstFile;
  private Path secondFile;
  private Path thirdFile;
  private Path fourthFile;
  private Path fifthFile;

  @Before
  public void findTestData() {
    testdataDir = TestDataHelper.getTestDataDirectory(this).resolve("compile_strings");

    firstFile = testdataDir.resolve("first/res/values-es/strings.xml");
    secondFile = testdataDir.resolve("second/res/values-es/strings.xml");
    thirdFile = testdataDir.resolve("third/res/values-pt/strings.xml");
    fourthFile = testdataDir.resolve("third/res/values-pt-rBR/strings.xml");
    fifthFile = testdataDir.resolve("third/res/values/strings.xml");
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
    Matcher matcher = CompileStringsStep.NON_ENGLISH_STRING_FILE_PATTERN.matcher(input);
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
      String input, boolean matches, String resourceType, String resourceName, String resourceId) {

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
    Path path2 = Paths.get("project/dir/res/values/strings.xml");
    Path path3 = Paths.get("project/groupme/res/values-da/strings.xml");
    Path path4 = Paths.get("project/groupmetoo/res/values-da-rAB/strings.xml");
    Path path5 = Paths.get("project/foreveralone/res/values-es/strings.xml");
    ImmutableList<Path> files = ImmutableList.of(path0, path1, path2, path3, path4, path5);

    ImmutableMultimap<String, Path> groupedByLocale =
        createNonExecutingStep().groupFilesByLocale(ImmutableList.copyOf(files));

    ImmutableMultimap<String, Path> expectedMap =
        ImmutableMultimap.<String, Path>builder()
            .putAll("da", ImmutableSet.of(path0, path3))
            .putAll("da_AB", ImmutableSet.of(path1, path4))
            .putAll("es", ImmutableSet.of(path5))
            .putAll("en", ImmutableSet.of(path2))
            .build();

    assertEquals(
        "Result of CompileStringsStep.groupFilesByLocale() should match the expected value.",
        expectedMap,
        groupedByLocale);
  }

  @Test
  public void testScrapeStringNodes() throws IOException, SAXException {
    String xmlInput =
        "<string name='name1' gender='unknown'>Value1</string>"
            + "<string name='name1_f1gender' gender='female'>Value1_f1</string>"
            + "<string name='name2' gender='unknown'>Value with space</string>"
            + "<string name='name2_m2gender' gender='male'>Value with space m2</string>"
            + "<string name='name3' gender='unknown'>Value with \"quotes\"</string>"
            + "<string name='name4' gender='unknown'></string>"
            +
            // ignored because "name3" already found
            "<string name='name3' gender='unknown'>IGNORE</string>"
            + "<string name='name5' gender='unknown'>Value with %1$s</string>";
    NodeList stringNodes =
        XmlDomParser.parse(createResourcesXml(xmlInput)).getElementsByTagName("string");

    EnumMap<Gender, String> map1 = Maps.newEnumMap(Gender.class);
    map1.put(Gender.unknown, "Value1");
    map1.put(Gender.female, "Value1_f1");
    EnumMap<Gender, String> map2 = Maps.newEnumMap(Gender.class);
    map2.put(Gender.unknown, "Value with space");
    map2.put(Gender.male, "Value with space m2");
    EnumMap<Gender, String> map3 = Maps.newEnumMap(Gender.class);
    map3.put(Gender.unknown, "Value with \"quotes\"");
    EnumMap<Gender, String> map4 = Maps.newEnumMap(Gender.class);
    map4.put(Gender.unknown, "");
    EnumMap<Gender, String> map5 = Maps.newEnumMap(Gender.class);
    map5.put(Gender.unknown, "Value with %1$s");

    Map<Integer, EnumMap<Gender, String>> stringsMap = new HashMap<>();
    CompileStringsStep step = createNonExecutingStep();
    step.addStringResourceNameToIdMap(
        ImmutableMap.of(
            "name1", 1,
            "name2", 2,
            "name3", 3,
            "name4", 4,
            "name5", 5));
    step.scrapeStringNodes(stringNodes, stringsMap);

    assertEquals(
        "Incorrect map of resource id to string values.",
        ImmutableMap.of(
            1, map1,
            2, map2,
            3, map3,
            4, map4,
            5, map5),
        stringsMap);
  }

  @Test
  public void testScrapePluralsNodes() throws IOException, SAXException {
    String xmlInput =
        "<plurals name='name1' gender='unknown'>"
            + "<item quantity='zero'>%d people saw this</item>"
            + "<item quantity='one'>%d person saw this</item>"
            + "<item quantity='many'>%d people saw this</item>"
            + "</plurals>"
            + "<plurals name='name1_f1gender' gender='female'>"
            + "<item quantity='zero'>%d people saw this f1</item>"
            + "<item quantity='one'>%d person saw this f1</item>"
            + "<item quantity='many'>%d people saw this f1</item>"
            + "</plurals>"
            + "<plurals name='name2' gender='unknown'>"
            + "<item quantity='zero'>%d people ate this</item>"
            + "<item quantity='many'>%d people ate this</item>"
            + "</plurals>"
            + "<plurals name='name2_m2gender' gender='male'>"
            + "<item quantity='zero'>%d people ate this m2</item>"
            + "<item quantity='many'>%d people ate this m2</item>"
            + "</plurals>"
            + "<plurals name='name3' gender='unknown'></plurals>"
            + // Test empty array.
            // Ignored since "name2" already found.
            "<plurals name='name2' gender='unknown'></plurals>";
    NodeList pluralsNodes =
        XmlDomParser.parse(createResourcesXml(xmlInput)).getElementsByTagName("plurals");

    EnumMap<Gender, ImmutableMap<String, String>> map1 = Maps.newEnumMap(Gender.class);
    map1.put(
        Gender.unknown,
        ImmutableMap.of(
            "zero", "%d people saw this",
            "one", "%d person saw this",
            "many", "%d people saw this"));
    map1.put(
        Gender.female,
        ImmutableMap.of(
            "zero", "%d people saw this f1",
            "one", "%d person saw this f1",
            "many", "%d people saw this f1"));
    EnumMap<Gender, ImmutableMap<String, String>> map2 = Maps.newEnumMap(Gender.class);
    map2.put(
        Gender.unknown,
        ImmutableMap.of(
            "zero", "%d people ate this",
            "many", "%d people ate this"));
    map2.put(
        Gender.male,
        ImmutableMap.of(
            "zero", "%d people ate this m2",
            "many", "%d people ate this m2"));
    EnumMap<Gender, ImmutableMap<String, String>> map3 = Maps.newEnumMap(Gender.class);
    map3.put(Gender.unknown, ImmutableMap.of());

    Map<Integer, EnumMap<Gender, ImmutableMap<String, String>>> pluralsMap = new HashMap<>();
    CompileStringsStep step = createNonExecutingStep();
    step.addPluralsResourceNameToIdMap(
        ImmutableMap.of(
            "name1", 1,
            "name2", 2,
            "name3", 3));
    step.scrapePluralsNodes(pluralsNodes, pluralsMap);

    assertEquals(
        "Incorrect map of resource id to plural values.",
        ImmutableMap.of(
            1, map1,
            2, map2,
            3, map3),
        pluralsMap);
  }

  @Test
  public void testScrapeStringArrayNodes() throws IOException, SAXException {
    String xmlInput =
        "<string-array name='name1' gender='unknown'>"
            + "<item>Value12</item>"
            + "<item>Value11</item>"
            + "</string-array>"
            + "<string-array name='name1_f1gender' gender='female'>"
            + "<item>Value12 f1</item>"
            + "<item>Value11 f1</item>"
            + "</string-array>"
            + "<string-array name='name2' gender='unknown'>"
            + "<item>Value21</item>"
            + "</string-array>"
            + "<string-array name='name2_m2gender' gender='male'>"
            + "<item>Value21 m2</item>"
            + "</string-array>"
            + "<string-array name='name3' gender='unknown'></string-array>"
            + "<string-array name='name2' gender='unknown'>"
            + "<item>ignored</item>"
            + // Ignored because "name2" already found above.
            "</string-array>";

    EnumMap<Gender, List<String>> map1 = Maps.newEnumMap(Gender.class);
    map1.put(Gender.unknown, ImmutableList.of("Value12", "Value11"));
    map1.put(Gender.female, ImmutableList.of("Value12 f1", "Value11 f1"));
    EnumMap<Gender, List<String>> map2 = Maps.newEnumMap(Gender.class);
    map2.put(Gender.unknown, ImmutableList.of("Value21"));
    map2.put(Gender.male, ImmutableList.of("Value21 m2"));
    NodeList arrayNodes =
        XmlDomParser.parse(createResourcesXml(xmlInput)).getElementsByTagName("string-array");

    Map<Integer, EnumMap<Gender, ImmutableList<String>>> arraysMap = new TreeMap<>();
    CompileStringsStep step = createNonExecutingStep();
    step.addArrayResourceNameToIdMap(
        ImmutableMap.of(
            "name1", 1,
            "name2", 2,
            "name3", 3));
    step.scrapeStringArrayNodes(arrayNodes, arraysMap);

    assertEquals(
        "Incorrect map of resource id to string arrays.",
        ImmutableMap.of(1, map1, 2, map2),
        arraysMap);
  }

  @Test
  public void testScrapeNodesWithSameName() throws IOException, SAXException {
    String xmlInput =
        "<string name='name1' gender='unknown'>1</string>"
            + "<string name='name1_f1gender' gender='female'>1 f1</string>"
            + "<plurals name='name1' gender='unknown'>"
            + "<item quantity='one'>2</item>"
            + "<item quantity='other'>3</item>"
            + "</plurals>"
            + "<plurals name='name1_f1gender' gender='female'>"
            + "<item quantity='one'>2 f1</item>"
            + "<item quantity='other'>3 f1</item>"
            + "</plurals>"
            + "<string-array name='name1' gender='unknown'>"
            + "<item>4</item>"
            + "<item>5</item>"
            + "</string-array>"
            + "<string-array name='name1_f1gender' gender='female'>"
            + "<item>4 f1</item>"
            + "<item>5 f1</item>"
            + "</string-array>";

    NodeList stringNodes =
        XmlDomParser.parse(createResourcesXml(xmlInput)).getElementsByTagName("string");
    NodeList pluralsNodes =
        XmlDomParser.parse(createResourcesXml(xmlInput)).getElementsByTagName("plurals");
    NodeList arrayNodes =
        XmlDomParser.parse(createResourcesXml(xmlInput)).getElementsByTagName("string-array");

    Map<Integer, EnumMap<Gender, String>> stringMap = new TreeMap<>();
    Map<Integer, EnumMap<Gender, ImmutableMap<String, String>>> pluralsMap = new TreeMap<>();
    Map<Integer, EnumMap<Gender, ImmutableList<String>>> arraysMap = new TreeMap<>();

    EnumMap<Gender, String> map1 = Maps.newEnumMap(Gender.class);
    map1.put(Gender.unknown, "1");
    map1.put(Gender.female, "1 f1");
    EnumMap<Gender, Map<String, String>> map2 = Maps.newEnumMap(Gender.class);
    map2.put(Gender.unknown, ImmutableMap.of("one", "2", "other", "3"));
    map2.put(Gender.female, ImmutableMap.of("one", "2 f1", "other", "3 f1"));
    EnumMap<Gender, ImmutableList<String>> map3 = Maps.newEnumMap(Gender.class);
    map3.put(Gender.unknown, ImmutableList.of("4", "5"));
    map3.put(Gender.female, ImmutableList.of("4 f1", "5 f1"));

    CompileStringsStep step = createNonExecutingStep();
    step.addStringResourceNameToIdMap(ImmutableMap.of("name1", 1));
    step.addPluralsResourceNameToIdMap(ImmutableMap.of("name1", 2));
    step.addArrayResourceNameToIdMap(ImmutableMap.of("name1", 3));

    step.scrapeStringNodes(stringNodes, stringMap);
    step.scrapePluralsNodes(pluralsNodes, pluralsMap);
    step.scrapeStringArrayNodes(arrayNodes, arraysMap);

    assertEquals("Incorrect map of resource id to string.", ImmutableMap.of(1, map1), stringMap);
    assertEquals("Incorrect map of resource id to plurals.", ImmutableMap.of(2, map2), pluralsMap);
    assertEquals(
        "Incorrect map of resource id to string arrays.", ImmutableMap.of(3, map3), arraysMap);
  }

  private CompileStringsStep createNonExecutingStep() {
    return new CompileStringsStep(
        new FakeProjectFilesystem(),
        ImmutableList.of(),
        Paths.get(""),
        locale -> {
          throw new UnsupportedOperationException();
        });
  }

  private String createResourcesXml(String contents) {
    return XML_HEADER + "<resources>" + contents + "</resources>";
  }

  @Test
  public void testSuccessfulStepExecution() throws InterruptedException, IOException {
    Path destinationDir = Paths.get("");
    Path rDotJavaSrcDir = Paths.get("");

    ExecutionContext context = TestExecutionContext.newInstance();
    FakeProjectFileSystem fileSystem = new FakeProjectFileSystem();

    ImmutableList<Path> stringFiles =
        ImmutableList.of(firstFile, secondFile, thirdFile, fourthFile, fifthFile);

    CompileStringsStep step =
        new CompileStringsStep(
            fileSystem,
            stringFiles,
            rDotJavaSrcDir.resolve("R.txt"),
            input ->
                destinationDir.resolve(input + PackageStringAssets.STRING_ASSET_FILE_EXTENSION));
    assertEquals(0, step.execute(context).getExitCode());
    Map<String, byte[]> fileContentsMap = fileSystem.getFileContents();
    assertEquals("Incorrect number of string files written.", 4, fileContentsMap.size());
    for (Map.Entry<String, byte[]> entry : fileContentsMap.entrySet()) {
      File expectedFile = testdataDir.resolve(entry.getKey()).toFile();
      assertArrayEquals(createBinaryStream(expectedFile), fileContentsMap.get(entry.getKey()));
    }
  }

  private byte[] createBinaryStream(File expectedFile) throws IOException {
    try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
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
              stream.write(value.getBytes(StandardCharsets.UTF_8));
              break;
            default:
              throw new RuntimeException("Unexpected data type in .fbstr file: " + dataType);
          }
        }
      }

      return bos.toByteArray();
    }
  }

  private class FakeProjectFileSystem extends DefaultProjectFilesystem {

    private ImmutableMap.Builder<String, byte[]> fileContentsMapBuilder = ImmutableMap.builder();

    public FakeProjectFileSystem() throws InterruptedException {
      this(Paths.get(".").toAbsolutePath());
    }

    private FakeProjectFileSystem(Path root) {
      super(
          root,
          new DefaultProjectFilesystemDelegate(root),
          DefaultProjectFilesystemFactory.getWindowsFSInstance());
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
