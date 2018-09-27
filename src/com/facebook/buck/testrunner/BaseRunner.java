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

package com.facebook.buck.testrunner;

import com.facebook.buck.test.selectors.TestSelectorList;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter; // NOPMD can't depend on Guava
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Base class for both the JUnit and TestNG runners. */
public abstract class BaseRunner {
  protected static final String ENCODING = "UTF-8";
  // This is to be extended when introducing new information into the result .xml file and allow
  // consumers of that information to understand whether the testrunner they're using supports the
  // new features.
  protected static final String[] RUNNER_CAPABILITIES = {"simple_test_selector"};

  protected File outputDirectory;
  protected List<String> testClassNames;
  protected long defaultTestTimeoutMillis;
  protected TestSelectorList testSelectorList;
  protected boolean isDryRun;
  protected boolean shouldExplainTestSelectors;

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
    root.setAttribute("runner_capabilities", getRunnerCapabilities());
    doc.appendChild(root);

    for (TestResult result : results) {
      Element test = doc.createElement("test");

      // suite attribute
      test.setAttribute(
          "suite",
          (result.testMethodName == null && result.testClassName.equals("null"))
              ? testClassName
              : result.testClassName);

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
    // The transformer factory uses a system property to find the class to use. We need to default
    // to the system default since we have the user's classpath and they may not have everything set
    // up for the XSLT transform to work.
    String vendor = System.getProperty("java.vm.vendor");
    String factoryClass;
    if ("IBM Corporation".equals(vendor)) {
      // Used in the IBM JDK --- from
      // https://www.ibm.com/support/knowledgecenter/SSYKE2_8.0.0/com.ibm.java.aix.80.doc/user/xml/using_xml.html
      factoryClass = "com.ibm.xtq.xslt.jaxp.compiler.TransformerFactoryImpl";
    } else {
      // Used in the OpenJDK and the Oracle JDK.
      factoryClass = "com.sun.org.apache.xalan.internal.xsltc.trax.TransformerFactoryImpl";
    }
    // When we get this far, we're exiting, so no need to reset the property.
    System.setProperty("javax.xml.transform.TransformerFactory", factoryClass);
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
    OutputStream output;
    if (outputDirectory != null) {
      File outputFile = new File(outputDirectory, testClassName + testSelectorSuffix + ".xml");
      output = new BufferedOutputStream(new FileOutputStream(outputFile));
    } else {
      output = System.out;
    }
    StreamResult streamResult = new StreamResult(output);
    DOMSource source = new DOMSource(doc);
    trans.transform(source, streamResult);
    if (outputDirectory != null) {
      output.close();
    }
  }

  private static String getRunnerCapabilities() {
    StringBuilder result = new StringBuilder();
    int capsLen = RUNNER_CAPABILITIES.length;
    for (int i = 0; i < capsLen; i++) {
      String capability = RUNNER_CAPABILITIES[i];
      result.append(capability);
      if (i != capsLen - 1) {
        result.append(',');
      }
    }
    return result.toString();
  }

  private String stackTraceToString(Throwable exc) {
    StringWriter writer = new StringWriter();
    exc.printStackTrace(new PrintWriter(writer, /* autoFlush */ true)); // NOPMD no Guava
    return writer.toString();
  }

  /**
   * Expected arguments are:
   *
   * <ul>
   *   <li>(string) output directory
   *   <li>(long) default timeout in milliseconds (0 for no timeout)
   *   <li>(string) newline separated list of test selectors
   *   <li>(string...) fully-qualified names of test classes
   * </ul>
   */
  protected void parseArgs(String... args) {
    File outputDirectory = null;
    long defaultTestTimeoutMillis = Long.MAX_VALUE;
    TestSelectorList.Builder testSelectorListBuilder = TestSelectorList.builder();
    boolean isDryRun = false;
    boolean shouldExplainTestSelectors = false;

    List<String> testClassNames = new ArrayList<>();

    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--default-test-timeout":
          defaultTestTimeoutMillis = Long.parseLong(args[++i]);
          break;
        case "--test-selectors":
          List<String> rawSelectors = Arrays.asList(args[++i].split("\n"));
          testSelectorListBuilder.addRawSelectors(rawSelectors);
          break;
        case "--simple-test-selector":
          try {
            testSelectorListBuilder.addSimpleTestSelector(args[++i]);
          } catch (IllegalArgumentException e) {
            System.err.print("--simple-test-selector takes 2 args: [suite] and [method name].");
            System.exit(1);
          }
          break;
        case "--b64-test-selector":
          try {
            testSelectorListBuilder.addBase64EncodedTestSelector(args[++i]);
          } catch (IllegalArgumentException e) {
            System.err.print("--b64-test-selector takes 2 args: [suite] and [method name].");
            System.exit(1);
          }
          break;
        case "--explain-test-selectors":
          shouldExplainTestSelectors = true;
          break;
        case "--dry-run":
          isDryRun = true;
          break;
        case "--output":
          outputDirectory = new File(args[++i]);
          if (!outputDirectory.exists()) {
            System.err.printf("The output directory did not exist: %s\n", outputDirectory);
            System.exit(1);
          }
          break;
        default:
          testClassNames.add(args[i]);
      }
    }

    if (testClassNames.isEmpty()) {
      System.err.println("Must specify at least one test.");
      System.exit(1);
    }

    this.outputDirectory = outputDirectory;
    this.defaultTestTimeoutMillis = defaultTestTimeoutMillis;
    this.isDryRun = isDryRun;
    this.testClassNames = testClassNames;
    this.testSelectorList = testSelectorListBuilder.build();
    if (!testSelectorList.isEmpty() && !shouldExplainTestSelectors) {
      // Don't bother class-loading any classes that aren't possible, according to test selectors
      testClassNames.removeIf(name -> !testSelectorList.possiblyIncludesClassName(name));
    }
    this.shouldExplainTestSelectors = shouldExplainTestSelectors;
  }

  protected void runAndExit() {
    int exitCode;

    // Run the tests.
    try {
      run();

      // We're using a successful exit code regardless of test outcome since JUnitRunner
      // is designed to execute all tests and produce a report of success or failure.  We've done
      // that successfully if we've gotten here.
      exitCode = 0;
    } catch (Throwable e) {
      e.printStackTrace();
      // We're using a failed exit code here because something in the test runner crashed. We can't
      // tell whether there were still tests left to be run, so it's safest if we fail.
      exitCode = 1;
    }

    // Explicitly exit to force the test runner to complete even if tests have sloppily left
    // behind non-daemon threads that would have otherwise forced the process to wait and
    // eventually timeout.
    System.exit(exitCode);
  }
}
