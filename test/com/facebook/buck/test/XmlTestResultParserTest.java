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

package com.facebook.buck.test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.testutil.TemporaryPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

public class XmlTestResultParserTest {

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testParseMalformedXml() throws IOException {
    String xml =
        "<?xml version='1.1' encoding='UTF-8' standalone='no'?>\n"
            + "<testcase name='com.facebook.buck.test.XmlTestResultParserTest'>\n"
            + "  <test name='testParseMalformedXml' success='true' time='too meta'/>\n"
            + "</testcase>\n";
    Path xmlFile = tmp.newFile("result.xml");
    Files.write(xmlFile, xml.getBytes(UTF_8));

    try {
      XmlTestResultParser.parse(xmlFile);
      fail("Should throw RuntimeException.");
    } catch (RuntimeException e) {
      assertTrue(
          "The RuntimeException should wrap the NumberFormatException.",
          e.getCause() instanceof NumberFormatException);

      assertEquals(
          "Exception should include the path to the file as well as its contents.",
          "Error parsing test result data in "
              + xmlFile.toAbsolutePath()
              + ".\n"
              + "File contents:\n"
              + xml,
          e.getMessage());
    }
  }

  @Test
  public void testParsingAndroidSeparatesClassesInResults() throws Throwable {
    String xml =
        "<?xml version='1.1' encoding='UTF-8' standalone='no'?>\n"
            + "<testsuite name='com.facebook.foo.bar'>\n"
            + "  <testcase name='a' classname='Bar' time='0.0'/>\n"
            + "  <testcase name='b' classname='Bar' time='1.2'/>\n"
            + "  <testcase name='c' classname='Foo' time='3.2'/>\n"
            + "</testsuite>\n";

    Path xmlFile = tmp.newFile("result.xml");
    Files.write(xmlFile, xml.getBytes(UTF_8));

    List<TestCaseSummary> summary = XmlTestResultParser.parseAndroid(xmlFile, "android-5554");

    assertEquals(2, summary.size());

    assertEquals("Bar (android-5554)", summary.get(0).getTestCaseName());
    assertEquals("Foo (android-5554)", summary.get(1).getTestCaseName());

    assertEquals(2, summary.get(0).getTestResults().size());
    assertEquals(1, summary.get(1).getTestResults().size());
  }

  @Test
  public void testParsesMessageFromFailure() throws Throwable {
    String xml =
        "<?xml version='1.1' encoding='UTF-8' standalone='no'?>\n"
            + "<testsuite name='com.facebook.foo.bar'>\n"
            + "  <testcase name='a' classname='Bar' time='0.0'>\n"
            + "    <failure>com.foo: Error Message\nblehbleh\n</failure>\n"
            + "  </testcase>\n"
            + "</testsuite>\n";

    Path xmlFile = tmp.newFile("result.xml");
    Files.write(xmlFile, xml.getBytes(UTF_8));

    List<TestCaseSummary> summary = XmlTestResultParser.parseAndroid(xmlFile, "android-5554");

    assertEquals("Error Message", summary.get(0).getTestResults().get(0).getMessage());
  }

  @Test
  public void testParsesEmptyMessageFromFailure() throws Throwable {
    String xml =
        "<?xml version='1.1' encoding='UTF-8' standalone='no'?>\n"
            + "<testsuite name='com.facebook.foo.bar'>\n"
            + "  <testcase name='a' classname='Bar' time='0.0'>\n"
            + "    <failure>com.foo\nblehbleh\n</failure>\n"
            + "  </testcase>\n"
            + "</testsuite>\n";

    Path xmlFile = tmp.newFile("result.xml");
    Files.write(xmlFile, xml.getBytes(UTF_8));

    List<TestCaseSummary> summary = XmlTestResultParser.parseAndroid(xmlFile, "android-5554");

    assertEquals("", summary.get(0).getTestResults().get(0).getMessage());
  }

  @Test
  public void testColonInMEssage() throws Throwable {
    String xml =
        "<?xml version='1.1' encoding='UTF-8' standalone='no'?>\n"
            + "<testsuite name='com.facebook.foo.bar'>\n"
            + "  <testcase name='a' classname='Bar' time='0.0'>\n"
            + "    <failure>com.foo: Error: Message\nblehbleh\n</failure>\n"
            + "  </testcase>\n"
            + "</testsuite>\n";

    Path xmlFile = tmp.newFile("result.xml");
    Files.write(xmlFile, xml.getBytes(UTF_8));

    List<TestCaseSummary> summary = XmlTestResultParser.parseAndroid(xmlFile, "android-5554");

    assertEquals("Error: Message", summary.get(0).getTestResults().get(0).getMessage());
  }

  @Test
  public void testThrowsIfTheresAFailureNodeOnTestSuite() throws Throwable {
    String xml =
        "<?xml version='1.1' encoding='UTF-8' standalone='no'?>\n"
            + "<testsuite name='com.facebook.foo.bar'>\n"
            + "  <testcase name='a' classname='Bar' time='0.0' />\n"
            + "  <failure>Instrumentation failed with RuntimeException</failure>\n"
            + "</testsuite>\n";

    Path xmlFile = tmp.newFile("result.xml");
    Files.write(xmlFile, xml.getBytes(UTF_8));

    try {
      XmlTestResultParser.parseAndroid(xmlFile, "android-5554");
      fail("expected exception");
    } catch (TestProcessCrashed e) {
      assertThat(e.getMessage(), containsString("Instrumentation failed with RuntimeException"));
    }
  }
}
