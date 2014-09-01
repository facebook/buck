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

import com.facebook.buck.test.selectors.TestSelectorList;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * Launcher for JUnit.
 * <p>
 * Expected arguments are:
 * <ul>
 *   <li>(string) output directory
 *   <li>(long) default timeout in milliseconds (0 for no timeout)
 *   <li>(string) newline separated list of test selectors
 *   <li>(string...) fully-qualified names of test classes
 * </ul>
 * <p>
 * IMPORTANT! This class limits itself to types that are available in both the JDK and Android
 * Java API. The objective is to limit the set of files added to the ClassLoader that runs the test,
 * as not to interfere with the results of the test.
 */
public class Main {

  private Main() {
    // Launcher class.
  }

  public static void main(String[] args) throws Throwable {
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

    // Ensure that both junit and hamcrest are on the classpath
    try {
      Class.forName("org.junit.Test");
    } catch (ClassNotFoundException e) {
      System.err.println(
          "Unable to locate junit on the classpath. Please add as a test dependency.");
      System.exit(1);
    }
    try {
      Class.forName("org.hamcrest.Description");
    } catch (ClassNotFoundException e) {
      System.err.println(
          "Unable to locate hamcrest on the classpath. Please add as a test dependency.");
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
    try {
      new JUnitRunner(
          outputDirectory,
          testClassNames,
          defaultTestTimeoutMillis,
          testSelectorList,
          isDryRun)
          .run();
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
