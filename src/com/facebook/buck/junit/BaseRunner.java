/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.junit;

import com.facebook.buck.test.selectors.TestDescription;
import com.facebook.buck.test.selectors.TestSelectorList;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * Base class for both the JUnit and TestNG runners.
 */
public abstract class BaseRunner {
  protected static final String FILTER_DESCRIPTION = "TestSelectorList-filter";
  protected static final String ENCODING = "UTF-8";

  protected File outputDirectory;
  protected List<String> testClassNames;
  protected long defaultTestTimeoutMillis;
  protected TestSelectorList testSelectorList;
  protected boolean isDryRun;
  protected Set<TestDescription> seenDescriptions = new HashSet<>();

  public abstract void run() throws Throwable;

  /**
   * The test result file is written as XML to avoid introducing a dependency on JSON (see class
   * overview).
   */
  protected void writeResult(String testClassName, List<TestResult> results)
      throws IOException, ParserConfigurationException, TransformerException {
    // XML writer logic taken from:
    // http://www.genedavis.com/library/xml/java_dom_xml_creation.jsp

    DocumentBuilder docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
    Document doc = docBuilder.newDocument();
    doc.setXmlVersion("1.1");

    Element root = doc.createElement("testcase");
    root.setAttribute("name", testClassName);
    doc.appendChild(root);

    for (TestResult result : results) {
      Element test = doc.createElement("test");

      // name attribute
      test.setAttribute("name", result.testMethodName);

      // success attribute
      boolean isSuccess = result.isSuccess();
      test.setAttribute("success", Boolean.toString(isSuccess));

      // type attribute
      test.setAttribute("type", result.type.toString());

      // time attribute
      long runTime = result.runTime;
      test.setAttribute("time", String.valueOf(runTime));

      // Include failure details, if appropriate.
      Throwable failure = result.failure;
      if (failure != null) {
        String message = failure.getMessage();
        test.setAttribute("message", message);

        String stacktrace = stackTraceToString(failure);
        test.setAttribute("stacktrace", stacktrace);
      }

      // stdout, if non-empty.
      if (result.stdOut != null) {
        Element stdOutEl = doc.createElement("stdout");
        stdOutEl.appendChild(doc.createTextNode(result.stdOut));
        test.appendChild(stdOutEl);
      }

      // stderr, if non-empty.
      if (result.stdErr != null) {
        Element stdErrEl = doc.createElement("stderr");
        stdErrEl.appendChild(doc.createTextNode(result.stdErr));
        test.appendChild(stdErrEl);
      }

      root.appendChild(test);
    }

    // Create an XML transformer that pretty-prints with a 2-space indent.
    TransformerFactory transformerFactory = TransformerFactory.newInstance();
    Transformer trans = transformerFactory.newTransformer();
    trans.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
    trans.setOutputProperty(OutputKeys.INDENT, "yes");
    trans.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

    // Write the result to a file.
    String testSelectorSuffix = "";
    if (!testSelectorList.isEmpty()) {
      testSelectorSuffix += ".test_selectors";
    }
    if (isDryRun) {
      testSelectorSuffix += ".dry_run";
    }
    File outputFile = new File(outputDirectory, testClassName + testSelectorSuffix + ".xml");
    OutputStream output = new BufferedOutputStream(new FileOutputStream(outputFile));
    StreamResult streamResult = new StreamResult(output);
    DOMSource source = new DOMSource(doc);
    trans.transform(source, streamResult);
    output.close();
  }

  private String stackTraceToString(Throwable exc) {
    StringWriter writer = new StringWriter();
    exc.printStackTrace(new PrintWriter(writer, /* autoFlush */true));
    return writer.toString();
  }

  /**
   * Expected arguments are:
   * <ul>
   *   <li>(string) output directory
   *   <li>(long) default timeout in milliseconds (0 for no timeout)
   *   <li>(string) newline separated list of test selectors
   *   <li>(string...) fully-qualified names of test classes
   * </ul>
   */
  protected void parseArgs(String... args) throws Throwable {
    // Verify the arguments.
    if (args.length == 0) {
      System.err.println("Must specify an output directory.");
      System.exit(1);
    } else if (args.length == 1) {
      System.err.println("Must specify an output directory and a default timeout.");
      System.exit(1);
    } else if (args.length == 2) {
      System.err.println("Must specify some test selectors (or empty string for no selectors).");
      System.exit(1);
    } else if (args.length == 3) {
      System.err.println("Must specify at least one test.");
      System.exit(1);
    }

    // The first argument should specify the output directory.
    File outputDirectory = new File(args[0]);
    if (!outputDirectory.exists()) {
      System.err.printf("The output directory did not exist: %s\n", outputDirectory);
      System.exit(1);
    }

    long defaultTestTimeoutMillis = Long.parseLong(args[1]);

    TestSelectorList testSelectorList = TestSelectorList.empty();
    if (!args[2].isEmpty()) {
      List<String> rawSelectors = Arrays.asList(args[2].split("\n"));
      testSelectorList = TestSelectorList.builder()
          .addRawSelectors(rawSelectors)
          .build();
    }

    boolean isDryRun = !args[3].isEmpty();

    // Each subsequent argument should be a class name to run.
    List<String> testClassNames = Arrays.asList(args).subList(4, args.length);

    this.outputDirectory = outputDirectory;
    this.defaultTestTimeoutMillis = defaultTestTimeoutMillis;
    this.isDryRun = isDryRun;
    this.testClassNames = testClassNames;
    this.testSelectorList = testSelectorList;
  }

  protected void runAndExit() throws Throwable {
    // Run the tests.
    try {
      run();
    } catch (Throwable e){
      e.printStackTrace();
    } finally {
      // Explicitly exit to force the test runner to complete even if tests have sloppily left
      // behind non-daemon threads that would have otherwise forced the process to wait and
      // eventually timeout.
      //
      // Separately, we're using a successful exit code regardless of test outcome since JUnitRunner
      // is designed to execute all tests and produce a report of success or failure.  We've done
      // that successfully if we've gotten here.
      System.exit(0);
    }
  }
}
