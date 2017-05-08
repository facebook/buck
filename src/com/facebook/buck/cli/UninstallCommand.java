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
import com.facebook.buck.android.HasInstallableApk;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraphAndBuildTargets;
import com.facebook.buck.step.AdbOptions;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.TargetDeviceOptions;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreExceptions;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

public class UninstallCommand extends AbstractCommand {

  public static class UninstallOptions {
    @VisibleForTesting static final String KEEP_LONG_ARG = "--keep";
    @VisibleForTesting static final String KEEP_SHORT_ARG = "-k";

    @Option(
      name = KEEP_LONG_ARG,
      aliases = {KEEP_SHORT_ARG},
      usage = "Keep user data when uninstalling."
    )
    private boolean keepData = false;

    public boolean shouldKeepUserData() {
      return keepData;
    }
  }

  @AdditionalOptions @SuppressFieldNotInitialized private UninstallOptions uninstallOptions;

  @AdditionalOptions @SuppressFieldNotInitialized private AdbCommandLineOptions adbOptions;

  @AdditionalOptions @SuppressFieldNotInitialized
  private TargetDeviceCommandLineOptions deviceOptions;

  @Argument private List<String> arguments = new ArrayList<>();

  public List<String> getArguments() {
    return arguments;
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
    BuildRuleResolver resolver;
    ImmutableSet<BuildTarget> buildTargets;
    try (CommandThreadManager pool =
        new CommandThreadManager("Uninstall", getConcurrencyLimit(params.getBuckConfig()))) {
      TargetGraphAndBuildTargets result =
          params
              .getParser()
              .buildTargetGraphForTargetNodeSpecs(
                  params.getBuckEventBus(),
                  params.getCell(),
                  getEnableParserProfiling(),
                  pool.getExecutor(),
                  parseArgumentsAsTargetNodeSpecs(params.getBuckConfig(), getArguments()));
      buildTargets = result.getBuildTargets();
      resolver =
          Preconditions.checkNotNull(
                  params
                      .getActionGraphCache()
                      .getActionGraph(
                          params.getBuckEventBus(),
                          params.getBuckConfig().isActionGraphCheckingEnabled(),
                          params.getBuckConfig().isSkipActionGraphCache(),
                          result.getTargetGraph(),
                          params.getBuckConfig().getKeySeed()))
              .getResolver();
    } catch (BuildTargetException | BuildFileParseException e) {
      params
          .getBuckEventBus()
          .post(ConsoleEvent.severe(MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return 1;
    }

    // Make sure that only one build target is specified.
    if (buildTargets.size() != 1) {
      params
          .getBuckEventBus()
          .post(ConsoleEvent.severe("Must specify exactly one android_binary() rule."));
      return 1;
    }
    BuildTarget buildTarget = Iterables.get(buildTargets, 0);

    // Find the android_binary() rule from the parse.
    BuildRule buildRule;
    try {
      buildRule = resolver.requireRule(buildTarget);
    } catch (NoSuchBuildTargetException e) {
      throw new HumanReadableException(e.getHumanReadableErrorMessage());
    }
    if (!(buildRule instanceof HasInstallableApk)) {
      params
          .getBuckEventBus()
          .post(
              ConsoleEvent.severe(
                  String.format(
                      "Specified rule %s must be of type android_binary() or apk_genrule() but was %s().\n",
                      buildRule.getFullyQualifiedName(), buildRule.getType())));
      return 1;
    }
    HasInstallableApk hasInstallableApk = (HasInstallableApk) buildRule;

    // We need this in case adb isn't already running.
    try (ExecutionContext context = createExecutionContext(params)) {
      final AdbHelper adbHelper =
          new AdbHelper(
              adbOptions(params.getBuckConfig()),
              targetDeviceOptions(),
              context,
              params.getConsole(),
              params.getBuckEventBus(),
              params.getBuckConfig().getRestartAdbOnFailure());

      // Find application package name from manifest and uninstall from matching devices.
      SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
      String appId =
          AdbHelper.tryToExtractPackageNameFromManifest(
              pathResolver, hasInstallableApk.getApkInfo());
      return adbHelper.uninstallApp(appId, uninstallOptions().shouldKeepUserData()) ? 0 : 1;
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
