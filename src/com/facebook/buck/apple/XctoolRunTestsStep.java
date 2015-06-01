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

package com.facebook.buck.apple;

import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

/**
 * {@link ShellStep} implementation which runs {@code xctool} on
 * one or more logic tests and/or application tests (each paired with
 * a host application).
 *
 * The output is written in streaming JSON format to {@code outputPath}
 * and can be parsed by {@link XctoolOutputParsing}.
 */
public class XctoolRunTestsStep extends ShellStep {

  private final Path xctoolPath;
  private final String sdkName;
  private final Optional<String> simulatorName;
  private final ImmutableSet<Path> logicTestBundlePaths;
  private final ImmutableMap<Path, Path> appTestBundleToHostAppPaths;
  private final Path outputPath;

  public XctoolRunTestsStep(
      Path xctoolPath,
      String sdkName,
      Optional<String> simulatorName,
      Collection<Path> logicTestBundlePaths,
      Map<Path, Path> appTestBundleToHostAppPaths,
      Path outputPath) {
    Preconditions.checkArgument(
        !(logicTestBundlePaths.isEmpty() &&
          appTestBundleToHostAppPaths.isEmpty()),
        "Either logic tests (%s) or app tests (%s) must be present",
        logicTestBundlePaths,
        appTestBundleToHostAppPaths);

    // Each test bundle must have one of these extensions. (xctool
    // depends on them to choose which test runner to use.)
    Preconditions.checkArgument(
        AppleBundleExtensions.allPathsHaveValidTestExtensions(logicTestBundlePaths),
        "Extension of all logic tests must be one of %s (got %s)",
        AppleBundleExtensions.VALID_XCTOOL_BUNDLE_EXTENSIONS,
        logicTestBundlePaths);
    Preconditions.checkArgument(
        AppleBundleExtensions.allPathsHaveValidTestExtensions(appTestBundleToHostAppPaths.keySet()),
        "Extension of all app tests must be one of %s (got %s)",
        AppleBundleExtensions.VALID_XCTOOL_BUNDLE_EXTENSIONS,
        appTestBundleToHostAppPaths.keySet());

    this.xctoolPath = xctoolPath;
    this.sdkName = sdkName;
    this.simulatorName = simulatorName;
    this.logicTestBundlePaths = ImmutableSet.copyOf(logicTestBundlePaths);
    this.appTestBundleToHostAppPaths = ImmutableMap.copyOf(appTestBundleToHostAppPaths);
    this.outputPath = outputPath;
  }

  @Override
  public String getShortName() {
    return "xctool-run-tests";
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.add(xctoolPath.toString());
    args.add("-reporter");
    args.add("json-stream:" + outputPath.toString());
    args.add("-sdk", sdkName);
    if (simulatorName.isPresent()) {
      args.add("-destination");
      args.add("name=" + simulatorName.get());
    }
    args.add("run-tests");
    for (Path logicTestBundlePath : logicTestBundlePaths) {
      args.add("-logicTest");
      args.add(logicTestBundlePath.toString());
    }
    for (Map.Entry<Path, Path> appTestBundleAndHostApp : appTestBundleToHostAppPaths.entrySet()) {
      args.add("-appTest");
      args.add(appTestBundleAndHostApp.getKey() + ":" + appTestBundleAndHostApp.getValue());
    }

    return args.build();
  }

  @Override
  protected int getExitCodeFromResult(ExecutionContext context, ProcessExecutor.Result result) {
    int processExitCode = result.getExitCode();
    if (processExitCode == 0 || processExitCode == 1) {
      // Test failure is denoted by xctool returning 1. Unfortunately, there's no way
      // to distinguish an internal xctool error from a test failure:
      //
      // https://github.com/facebook/xctool/issues/511
      //
      // We don't want to fail the step on a test failure, so return 0 on either
      // xctool exit code.
      return 0;
    } else {
      // Some unknown failure.
      return processExitCode;
    }
  }
}
