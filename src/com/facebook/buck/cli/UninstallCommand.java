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

import com.facebook.buck.android.AdbHelper;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.model.Pair;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.InstallableApk;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphToActionGraph;
import com.facebook.buck.rules.TargetGraphTransformer;
import com.facebook.buck.step.AdbOptions;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TargetDeviceOptions;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.util.List;

public class UninstallCommand extends AbstractCommand {

  public static class UninstallOptions {
    @VisibleForTesting static final String KEEP_LONG_ARG = "--keep";
    @VisibleForTesting static final String KEEP_SHORT_ARG = "-k";
    @Option(
        name = KEEP_LONG_ARG,
        aliases = { KEEP_SHORT_ARG },
        usage = "Keep user data when uninstalling.")
    private boolean keepData = false;

    public boolean shouldKeepUserData() {
      return keepData;
    }
  }

  @AdditionalOptions
  @SuppressFieldNotInitialized
  private UninstallOptions uninstallOptions;

  @AdditionalOptions
  @SuppressFieldNotInitialized
  private AdbCommandLineOptions adbOptions;

  @AdditionalOptions
  @SuppressFieldNotInitialized
  private TargetDeviceCommandLineOptions deviceOptions;

  @Argument
  private List<String> arguments = Lists.newArrayList();

  public List<String> getArguments() {
    return arguments;
  }

  @VisibleForTesting
  void setArguments(List<String> arguments) {
    this.arguments = arguments;
  }

  public UninstallOptions uninstallOptions() {
    return uninstallOptions;
  }

  public AdbOptions adbOptions(BuckConfig buckConfig) {
    return adbOptions.getAdbOptions(buckConfig);
  }

  public TargetDeviceOptions targetDeviceOptions() {
    return deviceOptions.getTargetDeviceOptions();
  }

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {

    // Parse all of the build targets specified by the user.
    ActionGraph actionGraph;
    ImmutableSet<BuildTarget> buildTargets;
    try {
      Pair<ImmutableSet<BuildTarget>, TargetGraph> result = params.getParser()
          .buildTargetGraphForTargetNodeSpecs(
              parseArgumentsAsTargetNodeSpecs(
                  params.getBuckConfig(),
                  params.getCell().getFilesystem().getIgnorePaths(),
                  getArguments()),
              new ParserConfig(params.getBuckConfig()),
              params.getBuckEventBus(),
              params.getConsole(),
              params.getEnvironment(),
              getEnableProfiling());
      buildTargets = result.getFirst();
      TargetGraphTransformer targetGraphTransformer = new TargetGraphToActionGraph(
          params.getBuckEventBus(),
          new BuildTargetNodeToBuildRuleTransformer(),
          params.getFileHashCache());
      actionGraph = targetGraphTransformer.apply(result.getSecond());
    } catch (BuildTargetException | BuildFileParseException e) {
      params.getConsole().printBuildFailureWithoutStacktrace(e);
      return 1;
    }

    // Make sure that only one build target is specified.
    if (buildTargets.size() != 1) {
      params.getConsole().getStdErr().println("Must specify exactly one android_binary() rule.");
      return 1;
    }
    BuildTarget buildTarget = Iterables.get(buildTargets, 0);

    // Find the android_binary() rule from the parse.
    BuildRule buildRule = Preconditions.checkNotNull(
        actionGraph.findBuildRuleByTarget(buildTarget));
    if (!(buildRule instanceof InstallableApk)) {
      params.getConsole().printBuildFailure(
          String.format(
              "Specified rule %s must be of type android_binary() or apk_genrule() but was %s().\n",
              buildRule.getFullyQualifiedName(),
              buildRule.getType()));
      return 1;
    }
    InstallableApk installableApk = (InstallableApk) buildRule;

    // We need this in case adb isn't already running.
    try (ExecutionContext context = createExecutionContext(params)) {
      final AdbHelper adbHelper = new AdbHelper(
          adbOptions(params.getBuckConfig()),
          targetDeviceOptions(),
          context,
          params.getConsole(),
          params.getBuckEventBus(),
          params.getBuckConfig().getRestartAdbOnFailure());

      // Find application package name from manifest and uninstall from matching devices.
      String appId = AdbHelper.tryToExtractPackageNameFromManifest(installableApk);
      return adbHelper.uninstallApp(
          appId,
          uninstallOptions().shouldKeepUserData()
      ) ? 0 : 1;
    }
  }

  @Override
  public String getShortDescription() {
    return "uninstalls an APK";
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

}
