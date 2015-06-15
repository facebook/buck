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
import java.util.Collections;
import java.util.List;
import org.testng.IAnnotationTransformer;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;
import org.testng.TestNG;
import org.testng.annotations.Factory;
import org.testng.annotations.Guice;
import org.testng.annotations.ITestAnnotation;
import org.testng.annotations.Test;
import org.testng.internal.annotations.JDK15AnnotationFinder;
import org.testng.xml.XmlClass;
import org.testng.xml.XmlSuite;
import org.testng.xml.XmlTest;

/** Class that runs a set of TestNG tests and writes the results to a directory. */
public final class TestNGRunner extends BaseRunner {

  @Override
  public void run() throws Throwable {
    System.out.println("TestNGRunner started!");
    for (String className : testClassNames) {
      System.out.println("TestNGRunner handling " + className);
      final Class<?> testClass = Class.forName(className);

      List<TestResult> results;
      if (!mightBeATestClass(testClass)) {
        results = Collections.emptyList();
      } else {
        results = new ArrayList<>();
        TestNGWrapper tester = new TestNGWrapper();
        tester.setAnnoTransformer(new FilteringAnnotationTransformer(results));
        tester.setXmlSuites(Collections.singletonList(createXmlSuite(testClass)));
        TestListener listener = new TestListener(results);
        tester.addListener(new TestListener(results));
        try {
          System.out.println("TestNGRunner running " + className);
          tester.initializeSuitesAndJarFile();
          tester.runSuitesLocally();
        } catch (Throwable e) {
          // There are failures that the TestNG framework fails to
          // handle (for example, if a test requires a Guice module and
          // the module throws an exception).
          // Furthermore, there are bugs in TestNG itself. For example,
          // when printing the results of a parameterized test, it tries
          // to call toString() on all the test params and does not catch
          // resulting exceptions.
          listener.onFinish(null);
          System.out.println("TestNGRunner caught an exception");
          e.printStackTrace();
          if (!isDryRun) {
            results.add(
                new TestResult(className, "<TestNG failure>", 0, ResultType.FAILURE, e, "", ""));
          }
        }
        System.out.println("TestNGRunner tested " + className + ", got " + results.size());
      }

      writeResult(className, results);
    }
    System.out.println("TestNGRunner done!");
  }

  private XmlSuite createXmlSuite(Class<?> c) {
    XmlSuite xmlSuite = new XmlSuite();
    xmlSuite.setName("TmpSuite");
    XmlTest xmlTest = new XmlTest(xmlSuite);
    xmlTest.setName("TmpTest");
    xmlTest.setXmlClasses(Collections.singletonList(new XmlClass(c)));
    return xmlSuite;
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

  public final class TestNGWrapper extends TestNG {
    /**
     * The built-in setAnnotationTransformer unfortunately does not work with runSuitesLocally()
     *
     * <p>The alternative would be to use the (much heavier) run() method.
     */
    public void setAnnoTransformer(IAnnotationTransformer anno) {
      getConfiguration().setAnnotationFinder(new JDK15AnnotationFinder(anno));
    }
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
    public void onTestStart(ITestResult result) {}

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
      mustRestoreStdoutAndStderr = true;
    }

    @Override
    public void onFinish(ITestContext context) {
      if (mustRestoreStdoutAndStderr) {
        // Restore the original stdout/stderr.
        System.setOut(originalOut);
        System.setErr(originalErr);

        // Get the stdout/stderr written during the test as strings.
        stdOutStream.flush();
        stdErrStream.flush();
        mustRestoreStdoutAndStderr = false;
      }
    }

    private void recordResult(ITestResult result, ResultType type, Throwable failure) {
      String stdOut = streamToString(rawStdOutBytes);
      String stdErr = streamToString(rawStdErrBytes);

      String className = result.getTestClass().getName();
      String methodName = result.getMethod().getMethodName();
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
}
