/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.util.XmlDomParser;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.io.Files;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;

public class XmlTestResultParser {

  /** Utility Class:  Do not instantiate. */
  private XmlTestResultParser() {}

  public static TestCaseSummary parse(File xmlFile) throws IOException {
    String xmlFileContents = Files.toString(xmlFile, Charsets.UTF_8);

    try {
      return doParse(xmlFileContents);
    } catch (NumberFormatException | SAXException e) {
      // This is an attempt to track down an inexplicable error that we have observed in the wild.
      String message = createDetailedExceptionMessage(xmlFile, xmlFileContents);
      throw new RuntimeException(message, e);
    }
  }

  private static TestCaseSummary doParse(String xml) throws IOException, SAXException {
    Document doc = XmlDomParser.parse(new InputSource(new StringReader(xml)),
        /* namespaceAware */ true);
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
        message = node.getAttribute("message");
        stacktrace = node.getAttribute("stacktrace");
      }

      NodeList stdoutElements = node.getElementsByTagName("stdout");
      String stdOut;
      if (stdoutElements.getLength() == 1) {
        stdOut = stdoutElements.item(0).getTextContent();
      } else {
        stdOut = null;
      }

      NodeList stderrElements = node.getElementsByTagName("stderr");
      String stdErr;
      if (stderrElements.getLength() == 1) {
        stdErr = stderrElements.item(0).getTextContent();
      } else {
        stdErr = null;
      }

      TestResultSummary testResult = new TestResultSummary(
          testCaseName,
          testName,
          type,
          time,
          message,
          stacktrace,
          stdOut,
          stdErr);
      testResults.add(testResult);
    }

    return new TestCaseSummary(testCaseName, testResults);
  }

  private static String createDetailedExceptionMessage(File xmlFile, String xmlFileContents) {
    String message = "Error parsing test result data in " + xmlFile.getAbsolutePath() + ".\n" +
        "File contents:\n" + xmlFileContents;
    return message;
  }
}
