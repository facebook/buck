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

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.module.BuckModuleManager;
import com.facebook.buck.core.module.impl.BuckModuleJarHashProvider;
import com.facebook.buck.core.module.impl.DefaultBuckModuleManager;
import com.facebook.buck.core.plugin.impl.BuckPluginManagerFactory;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.AnsiEnvironmentChecking;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.environment.Platform;
import com.facebook.nailgun.NGContext;
import com.google.common.collect.ImmutableMap;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import org.pf4j.PluginManager;

/**
 * The abstract entry point of Buck commands for both {@link MainWithoutNailgun} and {@link
 * MainWithNailgun}
 */
abstract class AbstractMain {

  private static final String BUCK_BUILD_ID_ENV_VAR = "BUCK_BUILD_ID";
  private static PluginManager pluginManager;
  private static BuckModuleManager moduleManager;

  static {
    pluginManager = BuckPluginManagerFactory.createPluginManager();
    moduleManager = new DefaultBuckModuleManager(pluginManager, new BuckModuleJarHashProvider());
  }

  protected final InputStream stdIn;

  protected final ImmutableMap<String, String> clientEnvironment;
  protected final Platform platform;

  private final Optional<NGContext> optionalNGContext; // TODO(bobyf): remove this dependency.
  private final Console defaultConsole;

  protected AbstractMain(
      PrintStream stdOut,
      PrintStream stdErr,
      InputStream stdIn,
      ImmutableMap<String, String> clientEnvironment,
      Platform platform,
      Optional<NGContext> ngContext) {
    this.stdIn = stdIn;

    this.clientEnvironment = clientEnvironment;
    this.platform = platform;
    this.optionalNGContext = ngContext;

    // Create default console to start outputting errors immediately, if any
    // console may be overridden with custom console later once we have enough information to
    // construct it
    this.defaultConsole =
        new Console(
            Verbosity.STANDARD_INFORMATION,
            stdOut,
            stdErr,
            new Ansi(
                AnsiEnvironmentChecking.environmentSupportsAnsiEscapes(
                    platform, clientEnvironment)));
  }

  /**
   * @return an initialized {@link MainRunner} for running the buck command, with all the base state
   *     setup.
   */
  protected MainRunner prepareMainRunner() {

    return new MainRunner(
        defaultConsole,
        stdIn,
        getBuildId(clientEnvironment),
        clientEnvironment,
        platform,
        pluginManager,
        moduleManager,
        optionalNGContext);
  }

  private static BuildId getBuildId(ImmutableMap<String, String> clientEnvironment) {
    @Nullable String specifiedBuildId = clientEnvironment.get(BUCK_BUILD_ID_ENV_VAR);
    if (specifiedBuildId == null) {
      specifiedBuildId = UUID.randomUUID().toString();
    }
    return new BuildId(specifiedBuildId);
  }
}
