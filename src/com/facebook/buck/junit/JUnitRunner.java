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

import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.test.selectors.TestDescription;
import com.facebook.buck.test.selectors.TestSelectorList;

import org.junit.Ignore;
import org.junit.internal.builders.AllDefaultPossibilitiesBuilder;
import org.junit.internal.builders.AnnotatedBuilder;
import org.junit.internal.builders.JUnit4Builder;
import org.junit.runner.Computer;
import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Runner;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.notification.Failure;
import org.junit.runners.model.RunnerBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
 * Class that runs a set of JUnit tests and writes the results to a directory.
 * <p>
 * IMPORTANT! This class limits itself to types that are available in both the JDK and Android Java
 * API. The objective is to limit the set of files added to the ClassLoader that runs the test, as
 * not to interfere with the results of the test.
 */
public final class JUnitRunner {

  private static final String FILTER_DESCRIPTION = "TestSelectorList-filter";

  private final File outputDirectory;
  private final List<String> testClassNames;
  private final long defaultTestTimeoutMillis;
  private final TestSelectorList testSelectorList;
  private final boolean isDryRun;
  private final Set<TestDescription> seenDescriptions = new HashSet<>();

  public JUnitRunner(
      File outputDirectory,
      List<String> testClassNames,
      long defaultTestTimeoutMillis,
      TestSelectorList testSelectorList,
      boolean isDryRun) {
    this.outputDirectory = outputDirectory;
    this.testClassNames = testClassNames;
    this.defaultTestTimeoutMillis = defaultTestTimeoutMillis;
    this.testSelectorList = testSelectorList;
    this.isDryRun = isDryRun;
  }

  public void run() throws Throwable {
    Filter filter = new Filter() {
      @Override
      public boolean shouldRun(Description description) {
        String methodName = description.getMethodName();
        if (methodName == null) {
          // JUnit will give us an org.junit.runner.Description like this for the test class
          // itself.  It's easier for our filtering to make decisions just at the method level,
          // however, so just always return true here.
          return true;
        } else {
          String className = description.getClassName();
          TestDescription testDescription = new TestDescription(className, methodName);
          if (testSelectorList.isIncluded(testDescription)) {
            boolean isIgnored = description.getAnnotation(Ignore.class) != null;
            if (!isIgnored) {
              seenDescriptions.add(testDescription);
            }
            return !isDryRun;
          } else {
            return false;
          }
        }
      }

      @Override
      public String describe() {
        return FILTER_DESCRIPTION;
      }
    };

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
        request = request.filterWith(filter);

        jUnitCore.addListener(TestResult.createSingleTestResultRunListener(results));
        jUnitCore.run(request);
      }

      results = interpretResults(className, results);
      if (results != null) {
        writeResult(className, results);
      }
    }
  }

  /**
   * This method filters a list of test results prior to writing results to a file.  null is
   * returned to indicate "don't write anything", which is different to writing a file containing
   * 0 results.
   *
   * <p>
   *
   * JUnit handles classes-without-tests in different ways.  If you are not using the
   * org.junit.runner.Request.filterWith facility then JUnit ignores classes-without-tests.
   * However, if you are using a filter then a class-without-tests will cause a
   * NoTestsRemainException to be thrown, which is propagated back as an error.
   */
  /* @Nullable */
  private List<TestResult> interpretResults(String className, List<TestResult> results) {
    // For dry runs, write fake results for every method seen in the given class.
    if (isDryRun) {
      List<TestResult> fakeResults = new ArrayList<>();
      for (TestDescription seenDescription : seenDescriptions) {
        if (seenDescription.getClassName().equals(className)) {
          TestResult fakeResult = new TestResult(
            seenDescription.getClassName(),
              seenDescription.getMethodName(),
              0L,
              ResultType.DRY_RUN,
              null,
              "",
              "");
          fakeResults.add(fakeResult);
        }
      }
      results = fakeResults;
    }

    // When not using any command line filtering options, all results should be recorded.
    if (testSelectorList.isEmpty()) {
      if (isSingleResultCausedByNoTestsRemainException(results)) {
        // ...except for testless-classes, where we pretend nothing ran.
        return new ArrayList<>();
      } else {
        return results;
      }
    }

    // If the results size is 0 (which can happen at least with JUnit 4.11), results are not
    // significant and shouldn't be recorded.
    if (results.size() == 0) {
      return null;
    }

    // In (at least) JUnit 4.8, we have an odd scenario where we have one result telling us we
    // have no results.
    if (isSingleResultCausedByNoTestsRemainException(results)) {
      return null;
    }

    return results;
  }

  /**
   * JUnit doesn't normally consider encountering a testless class an error.  However, when
   * using org.junit.runner.manipulation.Filter, testless classes *are* considered an error,
   * throwing org.junit.runner.manipulation.NoTestsRemainException.
   *
   * If we are using test-selectors then it's possible we will run a test class but never run any
   * of its test methods, because they'd all get filtered out.  When this happens, the results will
   * contain a single failure containing the error from the NoTestsRemainException.
   *
   * (NB: we can't decide at the class level whether we need to run a test class or not; we can only
   * run the test class and all its test methods and handle the erroneous exception JUnit throws if
   * no test-methods were actually run.)
   */
  private boolean isSingleResultCausedByNoTestsRemainException(List<TestResult> results) {
    if (results.size() != 1) {
      return false;
    }

    TestResult testResult = results.get(0);
    if (testResult.isSuccess()) {
      return false;
    }

    if (testResult.failure == null) {
      return false;
    }

    String message = testResult.failure.getMessage();
    if (message == null) {
      return false;
    }

    return message.contains("No tests found matching " + FILTER_DESCRIPTION);
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

    return new AllDefaultPossibilitiesBuilder(/* canUseSuiteMethod */ true) {
      @Override
      protected JUnit4Builder junit4Builder() {
        return jUnit4RunnerBuilder;
      }

      @Override
      protected AnnotatedBuilder annotatedBuilder() {
        // If there is no default timeout specified in .buckconfig, then use
        // the original behavior of AllDefaultPossibilitiesBuilder.
        //
        // Additionally, if we are using test selectors or doing a dry-run then
        // we should use the original behavior to use our
        // BuckBlockJUnit4ClassRunner, which provides the Descriptions needed
        // to do test selecting properly.
        if (defaultTestTimeoutMillis <= 0 || isDryRun || !testSelectorList.isEmpty()) {
          return super.annotatedBuilder();
        }

        return new AnnotatedBuilder(this) {
          @Override
          public Runner buildRunner(Class<? extends Runner> runnerClass,
              Class<?> testClass) throws Exception {
            Runner originalRunner = super.buildRunner(runnerClass, testClass);
            return new DelegateRunnerWithTimeout(originalRunner, defaultTestTimeoutMillis);
          }
        };
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

      // type attribute
      test.setAttribute("type", result.type.toString());

      // time attribute
      long runTime = result.runTime;
      test.setAttribute("time", String.valueOf(runTime));

      // Include failure details, if appropriate.
      Failure failure = result.failure;
      if (failure != null) {
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

  /**
   * Expected arguments are:
   * <ul>
   *   <li>(string) output directory
   *   <li>(long) default timeout in milliseconds (0 for no timeout)
   *   <li>(string) newline separated list of test selectors
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

    // Run the tests.
    new JUnitRunner(outputDirectory,
        testClassNames,
        defaultTestTimeoutMillis,
        testSelectorList,
        isDryRun)
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
