/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.testrunner;

import static com.facebook.buck.testrunner.CheckDependency.classPresent;
import static com.facebook.buck.testrunner.CheckDependency.requiresClass;

/**
 * Launcher for JUnit Jupiter (Junit5).
 *
 * @see JUnitMain
 */
public class JupiterMain {

  private JupiterMain() {
    // Launcher class.
  }

  /**
   * JUnit5 (Jupiter) will require its engine and launcher to be present into the classpath. In case
   * JUnit4 is also present it will require its vintage-engine to be present into the classpath.
   *
   * @param args runner arguments.
   */
  public static void main(String[] args) {
    // Requires Jupiter (JUnit5) engine + platform + launcher
    requiresClass("junit-jupiter-api", "org.junit.jupiter.api.Test");
    requiresClass("junit-jupiter-engine", "org.junit.jupiter.engine.JupiterTestEngine");
    requiresClass("junit-jupiter-platform", "org.junit.platform.engine.TestEngine");
    requiresClass(
        "junit-platform-launcher", "org.junit.platform.launcher.LauncherDiscoveryRequest");
    // If JUnit4 api is also present, it will require Vintage Engine to run
    if (classPresent("org.junit.Test")) {
      requiresClass("junit-vintage-engine", "org.junit.vintage.engine.VintageTestEngine");
    }

    JupiterRunner runner = new JupiterRunner();
    runner.parseArgs(args);
    runner.runAndExit();
  }
}
