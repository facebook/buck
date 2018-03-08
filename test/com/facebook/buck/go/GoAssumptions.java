/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.go;

import static org.junit.Assume.assumeNoException;

import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.toolchain.ToolchainCreationContext;
import com.facebook.buck.toolchain.impl.ToolchainProviderBuilder;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.Assume;

abstract class GoAssumptions {
  // e.g., "go version go1.9.2 darwin/amd64", "go version go1.9.2 windows/amd64",
  // "go version go1.8 linux/amd64"
  private static final Pattern VERSION_PATTERN =
      Pattern.compile("^go version go(\\d+)\\.(\\d+).*$");

  public static void assumeGoCompilerAvailable() throws IOException {
    Throwable exception = null;
    try {
      ProcessExecutor executor = new DefaultProcessExecutor(new TestConsole());

      FakeBuckConfig.Builder baseConfig = FakeBuckConfig.builder();
      String goRoot = System.getenv("GOROOT");
      if (goRoot != null) {
        baseConfig.setSections("[go]", "  root = " + goRoot);
        // This should really act like some kind of readonly bind-mount onto the real filesystem.
        // But this is currently enough to check whether Go seems to be installed, so we'll live...
        FakeProjectFilesystem fs = new FakeProjectFilesystem();
        fs.mkdirs(fs.getPath(goRoot));
        baseConfig.setFilesystem(fs);
      }
      new GoToolchainFactory()
          .createToolchain(
              new ToolchainProviderBuilder().build(),
              ToolchainCreationContext.of(
                  ImmutableMap.of(),
                  baseConfig.build(),
                  new FakeProjectFilesystem(),
                  executor,
                  new ExecutableFinder(),
                  TestRuleKeyConfigurationFactory.create()))
          .get()
          .getCompiler();
    } catch (HumanReadableException e) {
      exception = e;
    }
    assumeNoException(exception);
  }

  public static void assumeGoVersionAtLeast(String minimumVersion) {
    List<Integer> minimumVersionNumbers =
        Arrays.stream(minimumVersion.split("\\."))
            .map(Integer::valueOf)
            .collect(Collectors.toList());
    Assume.assumeTrue(
        "Expect minimum version string in the form of x.y, got: " + minimumVersion,
        minimumVersionNumbers.size() >= 2);
    Throwable exception = null;
    List<Integer> actualVersionNumbers = null;
    ProcessExecutor processExecutor = new DefaultProcessExecutor(new TestConsole());
    try {
      ProcessExecutor.Result goToolResult =
          processExecutor.launchAndExecute(
              ProcessExecutorParams.builder().addCommand("go", "version").build(),
              EnumSet.of(ProcessExecutor.Option.EXPECTING_STD_OUT),
              /* stdin */ Optional.empty(),
              /* timeOutMs */ Optional.empty(),
              /* timeoutHandler */ Optional.empty());
      if (goToolResult.getExitCode() == 0) {
        String versionOut = goToolResult.getStdout().get().trim();
        Matcher matcher = VERSION_PATTERN.matcher(versionOut);
        if (matcher.matches()) {
          actualVersionNumbers =
              IntStream.range(1, 3)
                  .mapToObj(matcher::group)
                  .map(Integer::valueOf)
                  .collect(Collectors.toList());
        } else {
          exception = new HumanReadableException("Unknown version: " + versionOut);
        }
      } else {
        exception = new HumanReadableException(goToolResult.getStderr().get());
      }
    } catch (Exception e) {
      exception = e;
    }
    assumeNoException(exception);
    boolean versionSatisfied = true;
    for (int i = 0; i < 2; i++) {
      if (actualVersionNumbers.get(i) < minimumVersionNumbers.get(i)) {
        versionSatisfied = false;
        break;
      } else if (actualVersionNumbers.get(i) > minimumVersionNumbers.get(i)) {
        versionSatisfied = true;
        break;
      }
    }

    Assume.assumeTrue("Actual Go version is lower than the required version", versionSatisfied);
  }
}
