/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.jvm.kotlin;

import com.facebook.buck.jvm.java.ForkMode;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavaRuntimeLauncher;
import com.facebook.buck.jvm.java.JavaTest;
import com.facebook.buck.jvm.java.TestType;
import com.facebook.buck.model.Either;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.Label;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
public class KotlinTest extends JavaTest {

  public KotlinTest(
      BuildRuleParams params,
      SourcePathResolver pathResolver,
      JavaLibrary compiledTestsLibrary,
      ImmutableSet<Either<SourcePath, Path>> additionalClasspathEntries,
      Set<Label> labels,
      Set<String> contacts,
      TestType testType,
      JavaRuntimeLauncher javaRuntimeLauncher,
      List<String> vmArgs,
      Map<String, String> nativeLibsEnvironment,
      Optional<Long> testRuleTimeoutMs,
      Optional<Long> testCaseTimeoutMs,
      ImmutableMap<String, String> env,
      boolean runTestSeparately,
      ForkMode forkMode,
      Optional<Level> stdOutLogLevel,
      Optional<Level> stdErrLogLevel) {

    super(
        params,
        pathResolver,
        compiledTestsLibrary,
        additionalClasspathEntries,
        labels,
        contacts,
        testType,
        javaRuntimeLauncher,
        vmArgs,
        nativeLibsEnvironment,
        testRuleTimeoutMs,
        testCaseTimeoutMs,
        env,
        runTestSeparately,
        forkMode,
        stdOutLogLevel,
        stdErrLogLevel);
  }

}
