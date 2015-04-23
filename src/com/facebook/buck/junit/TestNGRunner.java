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
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;
import org.testng.TestNG;
import org.testng.annotations.ITestAnnotation;
import org.testng.internal.annotations.JDK15AnnotationFinder;
import org.testng.xml.XmlClass;
import org.testng.xml.XmlSuite;
import org.testng.xml.XmlTest;

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
      final Class<?> testClass = Class.forName(className);

      List<TestResult> results;
      if (!isTestClass(testClass)) {
        results = Collections.emptyList();
      } else {
        results = new ArrayList<>();
        TestNG testng = new TestNG();
        testng.setTestClasses(new Class<?>[]{testClass});
        TestListener listener = new TestListener(results);
        testng.addListener(new TestListener(results));
        testng.run();
     }

      writeResult(className, results);
    }
  }

  private boolean isTestClass(Class<?> klass) {
    return klass.getConstructors().length <= 1;
  }

  private static class TestListener implements ITestListener {
    private final List<TestResult> results;
    private PrintStream originalOut, originalErr, stdOutStream, stdErrStream;
    private ByteArrayOutputStream rawStdOutBytes, rawStdErrBytes;

    public TestListener(List<TestResult> results) {
      this.results = results;
    }

    @Override
    public void onTestStart(ITestResult result) {}

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
    public void onStart(ITestContext context) {
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
    public void onFinish(ITestContext context) {
      // Restore the original stdout/stderr.
      System.setOut(originalOut);
      System.setErr(originalErr);

      // Get the stdout/stderr written during the test as strings.
      stdOutStream.flush();
      stdErrStream.flush();
    }

    private void recordResult(ITestResult result, ResultType type, Throwable failure) {
      String stdOut = streamToString(rawStdOutBytes);
      String stdErr = streamToString(rawStdErrBytes);

      String className = result.getTestClass().getName();
      String methodName = result.getTestName();
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
}
