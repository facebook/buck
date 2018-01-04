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
package com.facebook.buck.testrunner;

import com.facebook.buck.test.result.type.ResultType;
import com.facebook.buck.test.selectors.TestDescription;
import com.facebook.buck.test.selectors.TestSelector;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.testng.IAnnotationTransformer;
import org.testng.IReporter;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;
import org.testng.TestNG;
import org.testng.annotations.Factory;
import org.testng.annotations.Guice;
import org.testng.annotations.ITestAnnotation;
import org.testng.annotations.Test;
import org.testng.reporters.EmailableReporter;
import org.testng.reporters.FailedReporter;
import org.testng.reporters.JUnitReportReporter;
import org.testng.reporters.SuiteHTMLReporter;
import org.testng.reporters.XMLReporter;

/** Class that runs a set of TestNG tests and writes the results to a directory. */
public final class TestNGRunner extends BaseRunner {

  @Override
  public void run() throws Throwable {
    for (String className : testClassNames) {
      if (!shouldIncludeTest(className)) {
        continue;
      }

      final Class<?> testClass = Class.forName(className);

      List<TestResult> results;
      if (!mightBeATestClass(testClass)) {
        results = Collections.emptyList();
      } else {
        results = new ArrayList<>();
        TestNG testng = new TestNG();
        testng.setUseDefaultListeners(false);
        testng.setAnnotationTransformer(new FilteringAnnotationTransformer(results));
        testng.setTestClasses(new Class<?>[]{testClass});
        testng.addListener(new TestListener(results));
        // use default TestNG reporters ...
        testng.addListener(new SuiteHTMLReporter());
        testng.addListener((IReporter) new FailedReporter());
        testng.addListener(new XMLReporter());
        testng.addListener(new EmailableReporter());
        // ... except this replaces JUnitReportReporter ...
        testng.addListener(new JUnitReportReporterImproved());
        // ... and we can't access TestNG verbosity, so we remove VerboseReporter
        testng.run();
      }

      writeResult(className, results);
    }
  }

  /** Guessing whether or not a class is a test class is an imperfect art form. */
  private boolean mightBeATestClass(Class<?> klass) {
    int klassModifiers = klass.getModifiers();
    // Test classes must be public, non-abstract, non-interface
    if (!Modifier.isPublic(klassModifiers)
        || Modifier.isInterface(klassModifiers)
        || Modifier.isAbstract(klassModifiers)) {
      return false;
    }
    // Test classes must either have a public, no-arg constructor, or have a constructor that
    // initializes using dependency injection, via the org.testng.annotations.Guice annotation on
    // the class and the com.google.inject.Inject or javax.inject.Inject annotation on the
    // constructor.
    boolean foundPublicNoArgConstructor = false;
    boolean foundInjectedConstructor = false;
    boolean hasGuiceAnnotation = klass.getAnnotationsByType(Guice.class).length > 0;
    for (Constructor<?> c : klass.getConstructors()) {
      if (Modifier.isPublic(c.getModifiers())) {
        if (c.getParameterCount() == 0) {
          foundPublicNoArgConstructor = true;
        }
        if (hasGuiceAnnotation
            && (c.getAnnotationsByType(com.google.inject.Inject.class).length > 0
                || c.getAnnotationsByType(javax.inject.Inject.class).length > 0)) {
          foundInjectedConstructor = true;
        }
      }
    }
    if (!foundPublicNoArgConstructor && !foundInjectedConstructor) {
      return false;
    }
    // Test classes must have at least one public test method (or something that generates tests)
    boolean hasAtLeastOneTestMethod = false;
    for (Method m : klass.getMethods()) {
      if (Modifier.isPublic(m.getModifiers()) && m.getAnnotation(Test.class) != null) {
        hasAtLeastOneTestMethod = true;
      }
      if (Modifier.isPublic(m.getModifiers()) && m.getAnnotation(Factory.class) != null) {
        hasAtLeastOneTestMethod = true; // technically, not *quite* true, but close enough
      }
    }
    return hasAtLeastOneTestMethod;
  }

  private boolean shouldIncludeTest(String className) {
    TestDescription testDescription = new TestDescription(className, null);
    return testSelectorList.isIncluded(testDescription);
  }

  private static String calculateTestMethodName(ITestResult iTestResult) {
    Object[] parameters = iTestResult.getParameters();
    String name = iTestResult.getName();

    if (parameters == null || parameters.length == 0) {
      return name;
    }

    StringBuilder builder = new StringBuilder(name).append(" (");
    builder.append(Arrays.stream(parameters).map(parameter -> {
      try {
        return parameter == null ? "null" : parameter.toString();
      } catch (Exception e) {
        return "Unstringable object";
      }
    }).collect(Collectors.joining(", ")));
    builder.append(")");
    return builder.toString();
  }

  public class FilteringAnnotationTransformer implements IAnnotationTransformer {
    final List<TestResult> results;

    FilteringAnnotationTransformer(List<TestResult> results) {
      this.results = results;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void transform(
        ITestAnnotation annotation,
        Class testClass,
        Constructor testConstructor,
        Method testMethod) {
      if (testMethod == null) {
        return;
      }
      String className = testMethod.getDeclaringClass().getName();
      String methodName = testMethod.getName();
      TestDescription description = new TestDescription(className, methodName);
      TestSelector matchingSelector = testSelectorList.findSelector(description);
      if (!matchingSelector.isInclusive()) {
        // For tests that have been filtered out, record it now and don't run it
        if (shouldExplainTestSelectors) {
          String reason = "Excluded by filter: " + matchingSelector.getExplanation();
          results.add(TestResult.forExcluded(className, methodName, reason));
        }
        annotation.setEnabled(false);
        return;
      }
      if (!annotation.getEnabled()) {
        // on a dry run, have to record it now -- since it doesn't run, listener can't do it
        results.add(TestResult.forDisabled(className, methodName));
        return;
      }
      if (isDryRun) {
        // on a dry run, record it now and don't run it
        results.add(TestResult.forDryRun(className, methodName));
        annotation.setEnabled(false);
        return;
      }
    }
  }

  private static class TestListener implements ITestListener {
    private final List<TestResult> results;
    private boolean mustRestoreStdoutAndStderr;
    private PrintStream originalOut, originalErr, stdOutStream, stdErrStream;
    private ByteArrayOutputStream rawStdOutBytes, rawStdErrBytes;

    public TestListener(List<TestResult> results) {
      this.results = results;
    }

    @Override
    public void onTestStart(ITestResult result) {
      // Create an intermediate stdout/stderr to capture any debugging statements (usually in the
      // form of System.out.println) the developer is using to debug the test.
      originalOut = System.out;
      originalErr = System.err;
      rawStdOutBytes = new ByteArrayOutputStream();
      rawStdErrBytes = new ByteArrayOutputStream();
      stdOutStream = streamToPrintStream(rawStdOutBytes, System.out);
      stdErrStream = streamToPrintStream(rawStdErrBytes, System.err);
      System.setOut(stdOutStream);
      System.setErr(stdErrStream);
    }

    @Override
    public void onTestSuccess(ITestResult result) {
      recordResult(result, ResultType.SUCCESS, result.getThrowable());
    }

    @Override
    public void onTestSkipped(ITestResult result) {
      recordResult(result, ResultType.FAILURE, result.getThrowable());
    }

    @Override
    public void onTestFailure(ITestResult result) {
      recordResult(result, ResultType.FAILURE, result.getThrowable());
    }

    @Override
    public void onTestFailedButWithinSuccessPercentage(ITestResult result) {
      recordResult(result, ResultType.FAILURE, result.getThrowable());
    }

    @Override
    public void onStart(ITestContext context) {}

    @Override
    public void onFinish(ITestContext context) {}

    private void recordResult(ITestResult result, ResultType type, Throwable failure) {
      // Restore the original stdout/stderr.
      System.setOut(originalOut);
      System.setErr(originalErr);

      // Get the stdout/stderr written during the test as strings.
      stdOutStream.flush();
      stdErrStream.flush();

      String stdOut = streamToString(rawStdOutBytes);
      String stdErr = streamToString(rawStdErrBytes);

      String className = result.getTestClass().getName();
      String methodName = calculateTestMethodName(result);

      long runTimeMillis = result.getEndMillis() - result.getStartMillis();
      results.add(
          new TestResult(className, methodName, runTimeMillis, type, failure, stdOut, stdErr));
    }

    private String streamToString(ByteArrayOutputStream str) {
      try {
        return str.size() == 0 ? null : str.toString(ENCODING);
      } catch (UnsupportedEncodingException e) {
        return null;
      }
    }

    private PrintStream streamToPrintStream(ByteArrayOutputStream str, PrintStream fallback) {
      try {
        return new PrintStream(str, true /* autoFlush */, ENCODING);
      } catch (UnsupportedEncodingException e) {
        return fallback;
      }
    }
  }

  private static class JUnitReportReporterImproved extends JUnitReportReporter {
    @Override
    public String getTestName(ITestResult result) {
      return calculateTestMethodName(result);
    }
  }
}
