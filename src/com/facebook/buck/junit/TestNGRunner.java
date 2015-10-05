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

import org.testng.IAnnotationTransformer;
import org.testng.IReporter;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;
import org.testng.TestNG;
import org.testng.annotations.ITestAnnotation;
import org.testng.reporters.EmailableReporter;
import org.testng.reporters.FailedReporter;
import org.testng.reporters.JUnitReportReporter;
import org.testng.reporters.SuiteHTMLReporter;
import org.testng.reporters.XMLReporter;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Class that runs a set of TestNG tests and writes the results to a directory.
 */
public final class TestNGRunner extends BaseRunner {
  @Override
  public void run() throws Throwable {
    for (String className : testClassNames) {
      if (!shouldIncludeTest(className)) {
        continue;
      }

      final Class<?> testClass = Class.forName(className);

      List<TestResult> results;
      if (!isTestClass(testClass)) {
        results = Collections.emptyList();
      } else {
        results = new ArrayList<>();
        TestNG testng = new TestNG();
        testng.setUseDefaultListeners(false);
        testng.setAnnotationTransformer(new TestFilter());
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

  private boolean isTestClass(Class<?> klass) {
    return klass.getConstructors().length <= 1;
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
    for (int i = 0; i < parameters.length; i++) {
      Object parameter = parameters[i];
      if (parameter == null) {
        builder.append("null");
      } else {
        try {
          builder.append(parameter.toString());
        } catch (Exception e) {
          builder.append("Unstringable object");
        }
      }
      if (i < parameters.length - 1) {
        builder.append(", ");
      }
    }
    builder.append(")");
    return builder.toString();
  }

  public class TestFilter implements IAnnotationTransformer {
    @Override
    @SuppressWarnings("rawtypes")
    public void transform(ITestAnnotation annotation, Class testClass, Constructor testConstructor,
        Method testMethod) {
      if (!annotation.getEnabled()) {
        return;
      }
      if (testMethod == null) {
        return;
      }
      String className = testMethod.getDeclaringClass().getName();
      String methodName = testMethod.getName();
      TestDescription testDescription = new TestDescription(className, methodName);
      boolean isIncluded = testSelectorList.isIncluded(testDescription);
      annotation.setEnabled(isIncluded && !isDryRun);
    }
  }

  private static class TestListener implements ITestListener {
    private final List<TestResult> results;
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
    public void onTestSkipped(ITestResult result) {}

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
      results.add(new TestResult(className,
            methodName,
            runTimeMillis,
            type,
            failure,
            stdOut,
            stdErr));
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
