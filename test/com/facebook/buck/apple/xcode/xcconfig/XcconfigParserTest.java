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

package com.facebook.buck.apple.xcode.xcconfig;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.testutil.FakeReadonlyProjectFilesystem;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.nio.file.Paths;

// This class depends on javacc-generated source code.
// Please run "ant compile-tests" if you hit undefined symbols in IntelliJ.

public class XcconfigParserTest {

  private static XcconfigParser parser(String s) {
    return new XcconfigParser(new StringReader(s));
  }

  private static void assertBuildSettingHasValue(String content,
                                                 PredicatedConfigValue value)
      throws ParseException {
    assertEquals(value, parser(content).buildSetting());
  }

  private static void assertFileHasValue(String content,
                                         ImmutableList<PredicatedConfigValue> value)
      throws ParseException, IOException {
    assertEquals(value, parser(content).file());
  }

  private static void assertFileHasValue(ProjectFilesystem filesystem,
                                         String inputPath,
                                         ImmutableList<PredicatedConfigValue> value)
      throws ParseException, IOException {
    assertFileHasValue(filesystem, inputPath, ImmutableList.<Path>of(), value);
  }

  private static void assertFileHasValue(ProjectFilesystem filesystem,
                                         String inputPath,
                                         ImmutableList<Path> searchPaths,
                                         ImmutableList<PredicatedConfigValue> value)
      throws ParseException, IOException {
    assertEquals(
        value,
        XcconfigParser.parse(filesystem, Paths.get(inputPath), searchPaths));
  }

  /**
   * Parse and toString a string, test that they are identical.
   */
  private static void assertParseAndToStringIsIdentity(String content) throws ParseException {
    assertEquals(content, parser(content).buildSetting().toString());
  }

  @Test(expected = TokenMgrError.class)
  // will throw TokenMgrError but the compiler does not know ;)
  public void testTokenMgrError() throws ParseException {
    parser("#include \"toto").file();
  }

  @Test(expected = ParseException.class)
  public void testParseException() throws ParseException {
    parser("= 345").buildSetting();
  }

  String s1 = "X = /my/path/to/file//then/comment";
  PredicatedConfigValue value1 = new PredicatedConfigValue(
      "X",
      ImmutableSortedSet.<Condition>of(),
      ImmutableList.<TokenValue>of(
          TokenValue.literal("/my"),
          TokenValue.literal("/path"),
          TokenValue.literal("/to"),
          TokenValue.literal("/file")
      )
  );

  String s2 = "X = \"ACSV\" 1";
  PredicatedConfigValue value2 = new PredicatedConfigValue(
      "X",
      ImmutableSortedSet.<Condition>of(),
      ImmutableList.<TokenValue>of(
          TokenValue.literal("\"ACSV\" 1")
      ));

  String s3 = "X [a=1*] [x=3,y=4] = 1 /$(Y $(Z)) 2 SDF ///comment $(X)";
  PredicatedConfigValue value3 = new PredicatedConfigValue(
      "X",
      ImmutableSortedSet.<Condition>of(
          new Condition("a", "1", true),
          new Condition("x", "3", false),
          new Condition("y", "4", false)),
      ImmutableList.<TokenValue>of(
          TokenValue.literal("1 "),
          TokenValue.literal("/"),
          TokenValue.interpolation(ImmutableList.<TokenValue>of(
              TokenValue.literal("Y "),
              TokenValue.interpolation(ImmutableList.<TokenValue>of(TokenValue.literal("Z")))
          )),
          TokenValue.literal(" 2 SDF ")
      ));

  ImmutableList<PredicatedConfigValue> values = ImmutableList
      .<PredicatedConfigValue>builder()
      .add(value1)
      .add(value2)
      .add(value3)
      .build();

  @Test
  public void testBuildSettingParsing() throws ParseException {
    assertBuildSettingHasValue(s1, value1);
    assertBuildSettingHasValue(s2, value2);
    assertBuildSettingHasValue(s3, value3);
  }

  @Test
  public void testFileParsing() throws ParseException, IOException {
    assertFileHasValue(
        s1 + "\n" + s2 + "\n" + s3,
        values);

  }

  @Test
  public void testMultiFileParsing() throws ParseException, IOException {

    ImmutableMap<String, String> files = ImmutableMap.of(
        "file1.xcconfig", s2 + "\n" + s3,
        "folder/file2.xcconfig", s1 + "\n" + "#include \"../file1.xcconfig\""
    );

    ProjectFilesystem fileSystem = new FakeReadonlyProjectFilesystem(files);
    assertFileHasValue(fileSystem, "folder/file2.xcconfig", values);
  }

  @Test
  public void testAdditionalSearchPaths() throws ParseException, IOException {
    ImmutableMap<String, String> files = ImmutableMap.of(
        "some_other_search_path/file1.xcconfig", s2 + "\n" + s3,
        "folder/file2.xcconfig", s1 + "\n" + "#include \"file1.xcconfig\""
    );

    ProjectFilesystem filesystem = new FakeReadonlyProjectFilesystem(files);
    assertFileHasValue(
        filesystem,
        "folder/file2.xcconfig",
        ImmutableList.of(Paths.get("some_other_search_path")),
        values);
  }

  @Test
  public void testToString() {
    assertEquals("X = /my/path/to/file", value1.toString());
    assertEquals(s2, value2.toString());
    assertEquals("X[a=1*,x=3,y=4] = 1 /$(Y $(Z)) 2 SDF ", value3.toString());
  }

  @Test
  public void valueWithUnbalancedParensAndBraces() throws ParseException {
    assertParseAndToStringIsIdentity("X = ABC ) DEF)GHI} JKL");
    assertParseAndToStringIsIdentity("X = $(ABC$(DEF)) DEF)GHI} JKL");
  }

  @Test(expected = ParseException.class)
  public void braceInterpWithParenShouldThrow() throws ParseException {
    assertParseAndToStringIsIdentity("X = ${SOME)THING}");
  }

  @Test(expected = ParseException.class)
  public void parenInterpWithBraceShouldThrow() throws ParseException {
    assertParseAndToStringIsIdentity("X = $(SOME}THING)");
  }

  @Test
  public void conditionWithOnlyStarAllowed() throws ParseException {
    assertParseAndToStringIsIdentity("X[arch=*] = FOO");
  }

  @Test
  public void simpleDollarLiteral() throws ParseException {
    assertBuildSettingHasValue(
        "X = $FOO",
        new PredicatedConfigValue(
            "X",
            ImmutableSortedSet.<Condition>of(),
            ImmutableList.of(TokenValue.interpolation("FOO"))));
  }
}
