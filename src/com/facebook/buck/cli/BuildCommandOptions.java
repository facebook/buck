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

package com.facebook.buck.cli;

import com.facebook.buck.command.Build;
import com.facebook.buck.rules.DependencyGraph;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.NoAndroidSdkException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BuildCommandOptions extends AbstractCommandOptions {

  @Option(name = "--num-threads", usage = "Default is 1.25 * num processors")
  private int numThreads = (int)(Runtime.getRuntime().availableProcessors() * 1.25);

  private ListeningExecutorService listeningExecutorService;

  @Argument
  private List<String> arguments = Lists.newArrayList();

  public BuildCommandOptions(BuckConfig buckConfig) {
    super(buckConfig);

    setNumThreadsFromConfig(buckConfig);
  }

  private void setNumThreadsFromConfig(BuckConfig buckConfig) {
    ImmutableMap<String,String> build = buckConfig.getEntriesForSection("build");
    if (build.containsKey("threads")) {
      try {
        numThreads = Integer.parseInt(build.get("threads"));
      } catch (NumberFormatException e) {
        throw new HumanReadableException(
            "Unable to determine number of threads to use from building from buck config file. " +
                "Value used was '%s'", build.get("threads"));
      }
    }
  }

  public List<String> getArguments() {
    return arguments;
  }

  public void setArguments(List<String> arguments) {
    this.arguments = arguments;
  }

  public List<String> getArgumentsFormattedAsBuildTargets() {
    return CommandLineBuildTargetNormalizer.normalizeAll(getBuckConfig(), getArguments());
  }

  public boolean isCodeCoverageEnabled() {
    return false;
  }

  public boolean isDebugEnabled() {
    return false;
  }

  @VisibleForTesting
  int getNumThreads() {
    return numThreads;
  }

  public ListeningExecutorService getListeningExecutorService() {
    if (listeningExecutorService == null) {
      ExecutorService executorService = Executors.newFixedThreadPool(numThreads);
      listeningExecutorService = MoreExecutors.listeningDecorator(executorService);
    }
    return listeningExecutorService;
  }

  Build createBuild(DependencyGraph graph,
      File projectDirectoryRoot,
      Console console) throws NoAndroidSdkException {
    // First determine the android SDK dir.
    File androidSdkDir = findAndroidSdkDir();

    Build build = new Build(graph,
        androidSdkDir,
        findAndroidNdkDir(),
        projectDirectoryRoot,
        getVerbosity(),
        getListeningExecutorService(),
        getBuckConfig().createDefaultJavaPackageFinder(),
        console.getAnsi(),
        console.getStdOut(),
        console.getStdErr(),
        isCodeCoverageEnabled(),
        isDebugEnabled());
    return build;
  }
}
