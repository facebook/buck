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

package com.facebook.buck.junit;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.internal.builders.AllDefaultPossibilitiesBuilder;
import org.junit.internal.builders.JUnit4Builder;
import org.junit.runner.Computer;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Runner;
import org.junit.runner.notification.Failure;
import org.junit.runners.model.RunnerBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

import junit.framework.TestCase;

/**
 * Class that runs a set of JUnit tests and writes the results to a directory.
 * <p>
 * IMPORTANT! This class limits itself to types that are available in both the JDK and Android Java
 * API. The objective is to limit the set of files added to the ClassLoader that runs the test, as
 * not to interfere with the results of the test.
 */
public final class JUnitRunner {

  private final File outputDirectory;
  private final List<String> testClassNames;
  @SuppressWarnings("unused")
  private final long defaultTestTimeoutMillis;

  public JUnitRunner(
      File outputDirectory,
      List<String> testClassNames,
      long defaultTestTimeoutMillis) {
    this.outputDirectory = outputDirectory;
    this.testClassNames = testClassNames;
    this.defaultTestTimeoutMillis = defaultTestTimeoutMillis;
  }

  public void run() throws Throwable {
    for (String className : testClassNames) {
      final Class<?> testClass = Class.forName(className);
      Ignore ignore = testClass.getAnnotation(Ignore.class);
      boolean isTestClassIgnored = (ignore != null || !isTestClass(testClass));

      List<TestResult> results;
      if (isTestClassIgnored) {
        // Test case has @Ignore annotation, so do nothing.
        results = Collections.emptyList();
      } else {
        results = new ArrayList<>();
        JUnitCore jUnitCore = new JUnitCore();

        Runner suite = new Computer().getSuite(createRunnerBuilder(), new Class<?>[]{testClass});
        Request request = Request.runner(suite);

        jUnitCore.addListener(TestResult.createSingleTestResultRunListener(results));
        jUnitCore.run(request);
      }
      writeResult(className, results);
    }
  }

  private boolean isTestClass(Class<?> klass) {
    return klass.getConstructors().length <= 1;
  }

  /**
   * Creates an {@link AllDefaultPossibilitiesBuilder} that returns our custom
   * {@link BuckBlockJUnit4ClassRunner} when a {@link JUnit4Builder} is requested. This ensures that
   * JUnit 4 tests are executed using our runner whereas other types of tests are run with whatever
   * JUnit thinks is best.
   */
  private RunnerBuilder createRunnerBuilder() {
    final JUnit4Builder jUnit4RunnerBuilder = new JUnit4Builder() {
      @Override
      public Runner runnerForClass(Class<?> testClass) throws Throwable {
        return new BuckBlockJUnit4ClassRunner(testClass, defaultTestTimeoutMillis);
      }
    };

    return new AllDefaultPossibilitiesBuilder(
        /* canUseSuiteMethod */ true) {
      @Override
      protected JUnit4Builder junit4Builder() {
        return jUnit4RunnerBuilder;
      }
    };
  }

  /**
   * The test result file is written as XML to avoid introducing a dependency on JSON (see class
   * overview).
   */
  private void writeResult(String testClassName, List<TestResult> results)
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

      // time attribute
      long runTime = result.runTime;
      test.setAttribute("time", String.valueOf(runTime));

      // Include failure details, if appropriate.
      if (!isSuccess) {
        Failure failure = result.failure;
        String message = failure.getMessage();
        test.setAttribute("message", message);

        String stacktrace = failure.getTrace();
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
    File outputFile = new File(outputDirectory, testClassName + ".xml");
    OutputStream output = new BufferedOutputStream(new FileOutputStream(outputFile));
    StreamResult streamResult = new StreamResult(output);
    DOMSource source = new DOMSource(doc);
    trans.transform(source, streamResult);
    output.close();
  }

  /* @VisibleForTesting */
  static boolean isTestMethod(Method method) {
    // Always ignore a method if it has an @Ignore annotation.
    if (method.getAnnotation(Ignore.class) != null) {
      return false;
    }

    // JUnit 4: Methods annotated with @Test are considered tests. Also must be no-arg methods, but
    // JUnit will complain about that when it tries to run the method.
    if (method.getAnnotation(Test.class) != null) {
      return true;
    }

    // JUnit 3: Declaring class is a subclass of TestCase and method is public void no-arg whose
    // name starts with "test". Ideally, all tests in the codebase would use the JUnit 4 style, but
    // some test cases have not been converted yet.
    Class<?> declaringClass = method.getDeclaringClass();
    return (TestCase.class.isAssignableFrom(declaringClass)
        && method.getName().startsWith("test")
        && method.getParameterTypes().length == 0
        && method.getReturnType().equals(Void.TYPE));
  }

  /**
   * Expected arguments are:
   * <ul>
   *   <li>(string) output directory
   *   <li>(long) default timeout in milliseconds (0 for no timeout)
   *   <li>(string...) fully-qualified names of test classes
   * </ul>
   */
  public static void main(String... args) throws Throwable {
    // Verify the arguments.
    if (args.length == 0) {
      System.err.println("Must specify an output directory.");
      System.exit(1);
    } else if (args.length == 1) {
      System.err.println("Must specify an output directory and a default timeout.");
      System.exit(1);
    } else if (args.length == 2) {
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

    // Each argument other than the first one should be a class name to run.
    List<String> testClassNames = Arrays.asList(args).subList(2, args.length);

    // Run the tests.
    new JUnitRunner(outputDirectory,
        testClassNames,
        defaultTestTimeoutMillis)
    .run();

    // Explicitly exit to force the test runner to complete even if tests have sloppily left behind
    // non-daemon threads that would have otherwise forced the process to wait and eventually
    // timeout.
    //
    // Separately, we're using a successful exit code regardless of test outcome since JUnitRunner
    // is designed to execute all tests and produce a report of success or failure.  We've done
    // that successfully if we've gotten here.
    System.exit(0);
  }

}
