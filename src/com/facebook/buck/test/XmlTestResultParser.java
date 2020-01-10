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

package com.facebook.buck.test;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.util.xml.XmlDomParser;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public class XmlTestResultParser {

  /** Utility Class: Do not instantiate. */
  private XmlTestResultParser() {}

  public static List<TestCaseSummary> parseAndroid(Path xmlFile, String serialNumber)
      throws IOException, SAXException {
    String fileContents = new String(Files.readAllBytes(xmlFile), UTF_8);
    Document doc =
        XmlDomParser.parse(
            new InputSource(new StringReader(fileContents)), /* namespaceAware */ true);
    Element root = doc.getDocumentElement();
    Preconditions.checkState("testsuite".equals(root.getTagName()));

    NodeList testElements = doc.getElementsByTagName("testcase");

    throwIfProcessFailed(root);

    List<TestCaseSummary> results = new ArrayList<>();

    Map<String, List<TestResultSummary>> testResultsMap = new LinkedHashMap<>();

    for (int i = 0; i < testElements.getLength(); i++) {
      Element node = (Element) testElements.item(i);

      String className = node.getAttribute("classname");
      List<TestResultSummary> testResults = testResultsMap.get(className);
      if (testResults == null) {
        testResults = new ArrayList<>();
        testResultsMap.put(className, testResults);
      }

      testResults.add(getTestResultFromElement(node, getTestCaseName(className, serialNumber)));
    }

    for (Map.Entry<String, List<TestResultSummary>> entry : testResultsMap.entrySet()) {
      TestCaseSummary testCaseSummary =
          new TestCaseSummary(getTestCaseName(entry.getKey(), serialNumber), entry.getValue());
      results.add(testCaseSummary);
    }

    return results;
  }

  private static void throwIfProcessFailed(Element root) {
    NodeList children = root.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      if (!(children.item(i) instanceof Element)) {
        continue;
      }
      Element child = (Element) children.item(i);
      if (child.getTagName().equals("failure")) {
        throw new TestProcessCrashed(child.getTextContent() + "\nSee logcat for more details.");
      }
    }
  }

  private static String getTestCaseName(String className, String serialNumber) {
    return className + " (" + serialNumber + ")";
  }

  private static TestResultSummary getTestResultFromElement(Element node, String testCaseName) {
    double time = Float.parseFloat(node.getAttribute("time"));
    String testName = node.getAttribute("name");

    String message = null;
    String stacktrace = null;
    String stdOut = null;
    String stdErr = null;
    ResultType type = ResultType.SUCCESS;

    NodeList failure = node.getElementsByTagName("failure");
    if (failure.getLength() == 1) {

      stacktrace = failure.item(0).getTextContent();

      type = ResultType.FAILURE;

      String[] firstLineParts = stacktrace.split("\n")[0].split(":", 2);
      message = firstLineParts.length > 1 ? firstLineParts[1].trim() : "";
    }
    return new TestResultSummary(
        testCaseName, testName, type, Math.round(time * 1000), message, stacktrace, stdOut, stdErr);
  }

  public static TestCaseSummary parse(Path xmlFile) throws IOException {
    String xmlFileContents = new String(Files.readAllBytes(xmlFile), UTF_8);

    try {
      return doParse(xmlFileContents);
    } catch (NumberFormatException | SAXException e) {
      // This is an attempt to track down an inexplicable error that we have observed in the wild.
      String message = createDetailedExceptionMessage(xmlFile, xmlFileContents);
      throw new RuntimeException(message, e);
    }
  }

  private static TestCaseSummary doParse(String xml) throws IOException, SAXException {
    Document doc =
        XmlDomParser.parse(new InputSource(new StringReader(xml)), /* namespaceAware */ true);
    Element root = doc.getDocumentElement();
    Preconditions.checkState("testcase".equals(root.getTagName()));
    String testCaseName = root.getAttribute("name");

    NodeList testElements = doc.getElementsByTagName("test");
    List<TestResultSummary> testResults = Lists.newArrayListWithCapacity(testElements.getLength());
    for (int i = 0; i < testElements.getLength(); i++) {
      Element node = (Element) testElements.item(i);
      String testName = node.getAttribute("name");
      long time = Long.parseLong(node.getAttribute("time"));
      String typeString = node.getAttribute("type");
      ResultType type = ResultType.valueOf(typeString);
      String message;
      String stacktrace;
      if (type == ResultType.SUCCESS) {
        message = null;
        stacktrace = null;
      } else {
        message = TestXmlUnescaper.ATTRIBUTE_UNESCAPER.unescape(node.getAttribute("message"));
        stacktrace = TestXmlUnescaper.ATTRIBUTE_UNESCAPER.unescape(node.getAttribute("stacktrace"));
      }

      NodeList stdoutElements = node.getElementsByTagName("stdout");
      String stdOut;
      if (stdoutElements.getLength() == 1) {
        stdOut =
            TestXmlUnescaper.CONTENT_UNESCAPER.unescape(stdoutElements.item(0).getTextContent());
      } else {
        stdOut = null;
      }

      NodeList stderrElements = node.getElementsByTagName("stderr");
      String stdErr;
      if (stderrElements.getLength() == 1) {
        stdErr =
            TestXmlUnescaper.CONTENT_UNESCAPER.unescape(stderrElements.item(0).getTextContent());
      } else {
        stdErr = null;
      }

      TestResultSummary testResult =
          new TestResultSummary(
              testCaseName, testName, type, time, message, stacktrace, stdOut, stdErr);
      testResults.add(testResult);
    }

    return new TestCaseSummary(testCaseName, testResults);
  }

  private static String createDetailedExceptionMessage(Path xmlFile, String xmlFileContents) {
    return "Error parsing test result data in "
        + xmlFile.toAbsolutePath()
        + ".\n"
        + "File contents:\n"
        + xmlFileContents;
  }
}
