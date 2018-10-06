/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.step.external;

import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.util.env.BuckClasspath;
import com.facebook.buck.util.environment.BuckBuildType;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.stream.Collectors;

/** Provides methods for launching a java binary bundled within the buck binary. */
public class BundledExternalProcessLauncher {

  enum EntryPoints {
    EXTERNAL_STEP_EXECUTOR("com.facebook.buck.step.external.executor.ExternalStepExecutorMain"),
    ;

    private final String entryPointName;

    EntryPoints(String entryPointName) {
      this.entryPointName = entryPointName;
    }

    public String getEntryPointName() {
      return entryPointName;
    }
  }

  public ImmutableList<String> getCommandForStepExecutor() {
    return getCommand(EntryPoints.EXTERNAL_STEP_EXECUTOR);
  }

  private ImmutableList<String> getCommand(EntryPoints entryPoint) {
    BuckBuildType buckBuildType = BuckBuildType.CURRENT_BUCK_BUILD_TYPE.get();
    switch (buckBuildType) {
      case RELEASE_PEX:
      case LOCAL_PEX:
        return getCommandForPexBuild(entryPoint);
      case LOCAL_ANT:
        return getCommandForAntBuild(entryPoint);
      case UNKNOWN:
        return getCommandForWhenProbablyRunningUnderTest(entryPoint);
      default:
        throw new RuntimeException("Unknown build type " + buckBuildType);
    }
  }

  private ImmutableList<String> getCommandForPexBuild(EntryPoints entryPoint) {
    String jarPath = System.getProperty("buck.external_executor_jar");
    Objects.requireNonNull(
        jarPath,
        "The buck.external_executor_jar property is not set despite this being a PEX build.");
    return ImmutableList.of("java", "-cp", jarPath, entryPoint.getEntryPointName());
  }

  private ImmutableList<String> getCommandForAntBuild(EntryPoints entryPoint) {
    return ImmutableList.of(
        "java", "-cp", getClassPathForAntBuild(), entryPoint.getEntryPointName());
  }

  private ImmutableList<String> getCommandForWhenProbablyRunningUnderTest(EntryPoints entryPoint) {
    // When running tests with Buck we inject the path to the step runner into the environment.
    String runnerJar = System.getenv("EXTERNAL_STEP_RUNNER_JAR_FOR_BUCK_TEST");
    if (runnerJar != null) {
      return ImmutableList.of("java", "-cp", runnerJar, entryPoint.getEntryPointName());
    }
    // Right, this means we're running in an ant or intellij test, hold on tight..
    String classPath = getClasspathArgumentForUnknownBuild();
    return ImmutableList.of("java", "-cp", classPath, entryPoint.getEntryPointName());
  }

  private String getClassPathForAntBuild() {
    // In this case we do want System.getenv, to get at the buckd env variables rather than
    // at the env that was used when the buck command is invoked.
    String classPath;
    try {
      classPath = BuckClasspath.getBuckClasspathFromEnvVarOrThrow();
    } catch (Exception e) {
      throw new IllegalStateException(
          "Un-set "
              + BuckClasspath.ENV_VAR_NAME
              + " means that either it "
              + " was not configured by the launcher or we're in the wrong build mode.",
          e);
    }
    // We expect there to be at least a single entry for every 3rd party lib, plus entries for the
    // build outputs. If we get a classpath with a single entry, that's most likely the server
    // JAR from the PEX, which means the build modes got mixed up.
    Preconditions.checkState(
        classPath.split(File.pathSeparator).length > 1,
        "A short %s [%s] means that either it "
            + " was not configured by the launcher correctly or we're in the wrong build mode.",
        BuckClasspath.ENV_VAR_NAME,
        classPath);
    return classPath;
  }

  private String getClasspathArgumentForUnknownBuild() {
    try {
      return BuckClasspath.getBuckClasspathForIntellij()
          .stream()
          .map(MorePaths::pathWithUnixSeparators)
          .collect(Collectors.joining(File.pathSeparator));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
