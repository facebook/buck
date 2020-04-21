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

import com.facebook.buck.cli.MainRunner.KnownRuleTypesFactoryFactory;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.rules.knowntypes.DefaultKnownNativeRuleTypesFactory;
import com.facebook.buck.support.bgtasks.BackgroundTaskManager;
import com.facebook.buck.support.state.BuckGlobalStateLifecycleManager;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.environment.CommandMode;
import com.facebook.buck.util.environment.Platform;
import com.facebook.nailgun.NGContext;
import com.google.common.collect.ImmutableMap;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * The main entry point for running {@link MainRunner} in tests under the integration test
 * framework.
 */
public class MainForTests extends AbstractMain {

  private final KnownRuleTypesFactoryFactory knownRuleTypesFactoryFactory;

  /**
   * @param console the console the test run will print to
   * @param stdIn the stdin stream for the test command
   * @param knownRuleTypesFactoryFactory the {@link KnownRuleTypesFactoryFactory} for this test
   *     command
   * @param projectRoot the root of the project for this test command
   * @param rawClientPwd the raw path of where the client invoked this command
   * @param ngContext the nailgun context for this test command.
   * @param clientEnvironment the client environment being tested
   */
  public MainForTests(
      Console console,
      InputStream stdIn,
      KnownRuleTypesFactoryFactory knownRuleTypesFactoryFactory,
      Path projectRoot,
      @Nullable String rawClientPwd,
      ImmutableMap<String, String> clientEnvironment,
      Optional<NGContext> ngContext) {
    super(
        console,
        stdIn,
        clientEnvironment,
        Platform.detect(),
        projectRoot,
        rawClientPwd,
        CommandMode.TEST,
        ngContext);
    this.knownRuleTypesFactoryFactory = knownRuleTypesFactoryFactory;
  }

  /**
   * @param console the console the test run will print to
   * @param stdIn the stdin stream for the test command
   * @param clientEnvironment the client environment being tested
   * @param projectRoot the root of the project for this test command
   * @param rawClientPwd the raw path of where the client invoked this command
   * @param ngContext the nailgun context for this test command.
   */
  public MainForTests(
      Console console,
      InputStream stdIn,
      Path projectRoot,
      @Nullable String rawClientPwd,
      ImmutableMap<String, String> clientEnvironment,
      Optional<NGContext> ngContext) {
    this(
        console,
        stdIn,
        DefaultKnownNativeRuleTypesFactory::new,
        projectRoot,
        rawClientPwd,
        clientEnvironment,
        ngContext);
  }

  @Override
  public MainRunner prepareMainRunner(
      BackgroundTaskManager backgroundTaskManager,
      BuckGlobalStateLifecycleManager buckGlobalStateLifecycleManager,
      CommandManager commandManager) {
    return super.prepareMainRunner(
        backgroundTaskManager, buckGlobalStateLifecycleManager, commandManager);
  }

  @Override
  protected KnownRuleTypesFactoryFactory getKnownRuleTypesFactory() {
    return knownRuleTypesFactoryFactory;
  }

  @Override
  protected BuildId getBuildId() {
    return new BuildId();
  }

  public static class TestCommandManager extends CommandManager.DefaultCommandManager {}
}
