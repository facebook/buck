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

import com.facebook.buck.build_type.BuildType;
import com.facebook.buck.io.MorePaths;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Provides methods for launching a java binary bundled within the buck binary. */
public class BundledExternalProcessLauncher {

  enum EntryPoints {
    EXTERNAL_STEP_EXECUTOR("com.facebook.buck.step.external.executor.ExternalStepExecutorMain"),
    OOP_JAVAC("com.facebook.buck.oop_javac.Main"),
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

  public ImmutableList<String> getCommandForOutOfProcessJavac() {
    return getCommand(EntryPoints.OOP_JAVAC);
  }

  public ImmutableMap<String, String> getEnvForOutOfProcessJavac() {
    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
    Map<String, String> environment = System.getenv();
    if (environment.containsKey("JAVA_HOME")) {
      builder.put("JAVA_HOME", environment.get("JAVA_HOME"));
    }

    BuildType buildType = BuildType.CURRENT_BUILD_TYPE.get();
    switch (buildType) {
      case LOCAL_ANT:
        return builder.put("BUCK_CLASSPATH", getClassPathForAntBuild()).build();
      case UNKNOWN:
        return builder
            .put("BUCK_CLASSPATH", getClasspathArgumentForUnknownBuild(EntryPoints.OOP_JAVAC))
            .build();
      case RELEASE_PEX:
      case LOCAL_PEX:
        return builder.build();
      default:
        throw new RuntimeException("Unknown build type " + buildType.toString());
    }
  }

  private ImmutableList<String> getCommand(EntryPoints entryPoint) {
    BuildType buildType = BuildType.CURRENT_BUILD_TYPE.get();
    switch (buildType) {
      case RELEASE_PEX:
      case LOCAL_PEX:
        return getCommandForPexBuild(entryPoint);
      case LOCAL_ANT:
        return getCommandForAntBuild(entryPoint);
      case UNKNOWN:
        return getCommandForWhenProbablyRunningUnderTest(entryPoint);
      default:
        throw new RuntimeException("Unknown build type " + buildType.toString());
    }
  }

  private ImmutableList<String> getCommandForPexBuild(EntryPoints entryPoint) {
    String jarPath = System.getProperty("buck.external_executor_jar");
    Preconditions.checkNotNull(
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
    String classPath = getClasspathArgumentForUnknownBuild(entryPoint);
    return ImmutableList.of("java", "-cp", classPath, entryPoint.getEntryPointName());
  }

  private String getClassPathForAntBuild() {
    // In this case we do want System.getenv, to get at the buckd env variables rather than
    // at the env that was used when the buck command is invoked.
    String classPath = System.getenv("BUCK_CLASSPATH");
    Preconditions.checkState(
        classPath != null,
        "Un-set BUCK_CLASSPATH means that either it "
            + " was not configured by the launcher or we're in the wrong build mode.");
    // We expect there to be at least a single entry for every 3rd party lib, plus entries for the
    // build outputs. If we get a classpath with a single entry, that's most likely the server
    // JAR from the PEX, which means the build modes got mixed up.
    Preconditions.checkState(
        classPath.split(File.pathSeparator).length > 1,
        "A short BUCK_CLASSPATH [%s] means that either it "
            + " was not configured by the launcher correctly or we're in the wrong build mode.",
        classPath);
    return classPath;
  }

  private String getClasspathArgumentForUnknownBuild(EntryPoints entryPoint) {
    // CLASSPATH entries need to be absolute paths, mostly because the JVM gets exec'd with
    // IntelliJ's CWD when running tests from there.
    Path cwd = Paths.get("").toAbsolutePath();
    String classPath = null;
    try {
      // Move along, no ridiculous hacks here...
      // Should be something like file:/Users/you/buck/build-ij/classes/production/buck/
      URL myLocation = null;
      try {
        myLocation =
            this.getClass()
                .getClassLoader()
                .loadClass(entryPoint.getEntryPointName())
                .getProtectionDomain()
                .getCodeSource()
                .getLocation();
      } catch (ClassNotFoundException e) {
        throw new RuntimeException("Could not find " + entryPoint + ".");
      }
      Preconditions.checkState(
          myLocation.getProtocol().equals("file"),
          "Don't know how to launch the worker process from %s.",
          myLocation);

      List<String> classPathEntries = new ArrayList<>();
      classPathEntries.add(
          cwd.resolve(MorePaths.pathWithPlatformSeparators(myLocation.getFile())).toString());
      for (String classpathLine : Files.readAllLines(Paths.get("programs", "classpaths"))) {
        classpathLine = classpathLine.trim();
        if (classpathLine.startsWith("#")) {
          continue;
        }
        classPathEntries.add(
            cwd.resolve(MorePaths.pathWithPlatformSeparators(classpathLine)).toString());
      }

      classPath = Joiner.on(File.pathSeparatorChar).join(classPathEntries);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return classPath;
  }
}
