/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.cli;

import com.facebook.buck.support.bgtasks.AsyncBackgroundTaskManager;
import com.facebook.buck.support.bgtasks.BackgroundTaskManager;
import com.facebook.buck.support.state.BuckGlobalStateLifecycleManager;
import com.facebook.buck.util.AnsiEnvironmentChecking;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;

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
        DaemonMode.NON_DAEMON);
  }

  /**
   * The entry point of a buck command.
   *
   * @param args
   */
  public static void main(String[] args) {
    // TODO(bobyf): add shutdown handling

    BackgroundTaskManager backgroundTaskManager = AsyncBackgroundTaskManager.of();
    MainWithoutNailgun mainWithoutNailgun = new MainWithoutNailgun();
    installUncaughtExceptionHandler();
    MainRunner mainRunner =
        mainWithoutNailgun.prepareMainRunner(
            backgroundTaskManager,
            new BuckGlobalStateLifecycleManager(),
            new CommandManager.DefaultCommandManager());
    mainRunner.runMainThenExit(args, System.nanoTime());
    backgroundTaskManager.shutdownNow();
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

  private static void installUncaughtExceptionHandler() {
    // Override the default uncaught exception handler for background threads to log
    // to java.util.logging then exit the JVM with an error code.
    //
    // (We do this because the default is to just print to stderr and not exit the JVM,
    // which is not safe in a multithreaded environment if the thread held a lock or
    // resource which other threads need.)
    Thread.setDefaultUncaughtExceptionHandler(
        (t, e) -> {
          exitWithCode(t, e);
        });
  }
}
