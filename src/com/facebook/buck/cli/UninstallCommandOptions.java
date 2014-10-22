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

import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import java.util.List;

public class UninstallCommandOptions extends AbstractCommandOptions {

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
  private AdbOptions adbOptions;

  @AdditionalOptions
  @SuppressFieldNotInitialized
  private TargetDeviceOptions deviceOptions;

  @Argument
  private List<String> arguments = Lists.newArrayList();

  public UninstallOptions uninstallOptions() {
    return uninstallOptions;
  }

  public AdbOptions adbOptions() {
    return adbOptions;
  }

  public TargetDeviceOptions targetDeviceOptions() {
    return deviceOptions;
  }

  public UninstallCommandOptions(BuckConfig buckConfig) {
    super(buckConfig);
  }

  public List<String> getArguments() {
    return arguments;
  }

  public List<String> getArgumentsFormattedAsBuildTargets() {
    return getCommandLineBuildTargetNormalizer().normalizeAll(getArguments());
  }
}
