/*
 * Copyright 2019-present Facebook, Inc.
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

import com.facebook.buck.util.AnsiEnvironmentChecking;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import java.util.Optional;

/**
 * This is the main entry point for running buck without buckd.
 *
 * <p>This main will take care of initializing all the state that buck needs given that the buck
 * state is not stored. It will also take care of error handling and shutdown procedures given that
 * we are not running as a nailgun server.
 */
public class MainWithoutNailgun extends AbstractMain {

  public MainWithoutNailgun() {
    super(
        System.out,
        System.err,
        System.in,
        getClientEnvironment(),
        Platform.detect(),
        Optional.empty());
  }

  /**
   * The entry point of a buck command.
   *
   * @param args
   */
  public static void main(String[] args) {
    // TODO(bobyf): add shutdown handling

    MainWithoutNailgun mainWithoutNailgun = new MainWithoutNailgun();
    MainRunner mainRunner = mainWithoutNailgun.prepareMainRunner();
    mainRunner.runMainThenExit(args, System.nanoTime());
  }

  private static ImmutableMap<String, String> getClientEnvironment() {
    ImmutableMap<String, String> systemEnv = EnvVariablesProvider.getSystemEnv();
    ImmutableMap.Builder<String, String> builder =
        ImmutableMap.builderWithExpectedSize(systemEnv.size());

    systemEnv.entrySet().stream()
        .filter(
            e ->
                !AnsiEnvironmentChecking.NAILGUN_STDOUT_ISTTY_ENV.equals(e.getKey())
                    && !AnsiEnvironmentChecking.NAILGUN_STDERR_ISTTY_ENV.equals(e.getKey()))
        .forEach(builder::put);
    return builder.build();
  }
}
