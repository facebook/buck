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

import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.InstallableApk;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphToActionGraph;
import com.facebook.buck.rules.TargetGraphTransformer;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.io.IOException;

public class UninstallCommand extends AbstractCommandRunner<UninstallCommandOptions> {

  private final TargetGraphTransformer<ActionGraph> targetGraphTransformer;

  public UninstallCommand(CommandRunnerParams params) {
    super(params);

    this.targetGraphTransformer = new TargetGraphToActionGraph(
        params.getBuckEventBus(),
        new BuildTargetNodeToBuildRuleTransformer());
  }

  @Override
  UninstallCommandOptions createOptions(BuckConfig buckConfig) {
    return new UninstallCommandOptions(buckConfig);
  }

  @Override
  int runCommandWithOptionsInternal(UninstallCommandOptions options)
      throws IOException, InterruptedException {
    // Make sure that only one build target is specified.
    if (options.getArguments().size() != 1) {
      getStdErr().println("Must specify exactly one android_binary() rule.");
      return 1;
    }

    // Get a parser.
    Parser parser = getParser();

    // Parse all of the build targets specified by the user.
    BuildTargetParser buildTargetParser = parser.getBuildTargetParser();
    String buildTargetName = options.getArgumentsFormattedAsBuildTargets().get(0);
    ActionGraph actionGraph;
    BuildTarget buildTarget;
    try {
      buildTarget = buildTargetParser.parse(
          buildTargetName,
          BuildTargetPatternParser.fullyQualified(buildTargetParser));
      TargetGraph targetGraph = parser.buildTargetGraphForBuildTargets(
          ImmutableList.of(buildTarget),
          new ParserConfig(options.getBuckConfig()),
          getBuckEventBus(),
          console,
          environment,
          options.getEnableProfiling());
      actionGraph = targetGraphTransformer.apply(targetGraph);
    } catch (BuildTargetException | BuildFileParseException e) {
      console.printBuildFailureWithoutStacktrace(e);
      return 1;
    }

    // Find the android_binary() rule from the parse.
    BuildRule buildRule = Preconditions.checkNotNull(
        actionGraph.findBuildRuleByTarget(buildTarget));
    if (buildRule == null || !(buildRule instanceof InstallableApk)) {
      console.printBuildFailure(String.format(
          "Specified rule %s must be of type android_binary() or apk_genrule() but was %s().\n",
          buildRule.getFullyQualifiedName(),
          buildRule.getType().getName()));
      return 1;
    }
    InstallableApk installableApk = (InstallableApk) buildRule;

    // We need this in case adb isn't already running.
    try (ExecutionContext context = createExecutionContext()) {
      final AdbHelper adbHelper = new AdbHelper(
          options.adbOptions(),
          options.targetDeviceOptions(),
          context,
          console,
          getBuckEventBus(),
          options.getBuckConfig());

      // Find application package name from manifest and uninstall from matching devices.
      String appId = AdbHelper.tryToExtractPackageNameFromManifest(installableApk, context);
      return adbHelper.uninstallApp(
          appId,
          options.uninstallOptions()
      ) ? 0 : 1;
    }
  }

  @Override
  String getUsageIntro() {
    return "Specify an android_binary() rule whose APK should be uninstalled";
  }
}
