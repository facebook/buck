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

package com.facebook.buck.features.python;

import static org.junit.Assume.assumeThat;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.UnconfiguredTargetConfiguration;
import com.facebook.buck.features.python.toolchain.PythonEnvironment;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.features.python.toolchain.PythonVersion;
import com.facebook.buck.features.python.toolchain.impl.PythonVersionFactory;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.hamcrest.Matcher;

public class PythonTestUtils {

  private PythonTestUtils() {}

  public static final PythonBuckConfig PYTHON_CONFIG =
      new PythonBuckConfig(FakeBuckConfig.builder().build());

  public static final PythonPlatform PYTHON_PLATFORM =
      new TestPythonPlatform(
          InternalFlavor.of("default-py-platform"),
          new PythonEnvironment(
              Paths.get("python"),
              PythonVersion.of("CPython", "2.6"),
              PythonBuckConfig.SECTION,
              UnconfiguredTargetConfiguration.INSTANCE),
          Optional.empty());

  public static final FlavorDomain<PythonPlatform> PYTHON_PLATFORMS =
      FlavorDomain.of("python", PYTHON_PLATFORM);

  public static Path assumeInterpreter(String name) {
    ExecutableFinder finder = new ExecutableFinder();
    Optional<Path> interpreter =
        finder.getOptionalExecutable(Paths.get(name), EnvVariablesProvider.getSystemEnv());
    assumeTrue(interpreter.isPresent());
    return interpreter.get();
  }

  public static void assumeVersion(
      Path interpreter, Matcher<String> matchInterpreter, Matcher<String> matchVersionString)
      throws InterruptedException {
    PythonVersion version =
        PythonVersionFactory.fromInterpreter(
            new DefaultProcessExecutor(new TestConsole()), interpreter);
    assumeThat(version.getInterpreterName(), matchInterpreter);
    assumeThat(version.getVersionString(), matchVersionString);
  }
}
