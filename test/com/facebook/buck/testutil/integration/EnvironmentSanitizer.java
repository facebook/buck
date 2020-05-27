/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.testutil.integration;

import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/** Sanitizes the environment variables for integration tests */
public class EnvironmentSanitizer {

  private EnvironmentSanitizer() {}

  public static ImmutableMap<String, String> getSanitizedEnvForTests(
      ImmutableMap<String, String> environmentOverrides) {
    // Construct a limited view of the parent environment for the child.
    // TODO(#5754812): we should eventually get tests working without requiring these be set.
    ImmutableList<String> inheritedEnvVars =
        ImmutableList.of(
            "ANDROID_HOME",
            "ANDROID_NDK",
            "ANDROID_NDK_REPOSITORY",
            "ANDROID_SDK",
            // TODO(grumpyjames) Write an equivalent of the groovyc and startGroovy
            // scripts provided by the groovy distribution in order to remove these two.
            "GROOVY_HOME",
            "JAVA_HOME",
            "NDK_HOME",
            "PATH",
            "PATHEXT",

            // Needed by ndk-build on Windows
            "OS",
            "ProgramW6432",
            "ProgramFiles(x86)",

            // The haskell integration tests call into GHC, which needs HOME to be set.
            "HOME",

            // TODO(#6586154): set TMP variable for ShellSteps
            "TMP");
    Map<String, String> envBuilder = new HashMap<>();
    for (String variable : inheritedEnvVars) {
      String value = EnvVariablesProvider.getSystemEnv().get(variable);
      if (value != null) {
        envBuilder.put(variable, value);
      }
    }
    envBuilder.putAll(environmentOverrides);

    String python3Interpreter =
        new ExecutableFinder()
            .getExecutable(Paths.get("python3"), EnvVariablesProvider.getSystemEnv())
            .toAbsolutePath()
            .toString();
    envBuilder.put("BUCK_WRAPPER_PYTHON_BIN", python3Interpreter);

    return ImmutableMap.copyOf(envBuilder);
  }
}
