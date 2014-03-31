/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.rules.DefaultKnownBuildRuleTypes;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.rules.NoopArtifactCache;
import com.facebook.buck.testutil.BuckTestConstant;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.AndroidDirectoryResolver;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.FakeAndroidDirectoryResolver;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.environment.Platform;

import java.io.File;

public class CommandRunnerParamsForTesting extends CommandRunnerParams {

  public CommandRunnerParamsForTesting(
      Console console,
      ProjectFilesystem projectFilesystem,
      AndroidDirectoryResolver androidDirectoryResolver,
      KnownBuildRuleTypes buildRuleTypes,
      ArtifactCacheFactory artifactCacheFactory,
      BuckEventBus eventBus,
      String pythonInterpreter,
      Platform platform) {
    super(console,
        projectFilesystem,
        androidDirectoryResolver,
        buildRuleTypes,
        artifactCacheFactory,
        eventBus,
        pythonInterpreter,
        platform);
  }

  // Admittedly, this class has no additional methods beyond its superclass today, but we will
  // likely add additional observer methods at some point in the future.

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {

    private ProjectFilesystem projectFilesystem = new ProjectFilesystem(new File("."));
    private AndroidDirectoryResolver androidDirectoryResolver = new FakeAndroidDirectoryResolver();
    private KnownBuildRuleTypes buildRuleTypes =
        DefaultKnownBuildRuleTypes.getDefaultKnownBuildRuleTypes(projectFilesystem);
    private ArtifactCacheFactory artifactCacheFactory = new InstanceArtifactCacheFactory(
        new NoopArtifactCache());
    private Console console = new TestConsole();
    private String pythonInterpreter = BuckTestConstant.PYTHON_INTERPRETER;
    private BuckEventBus eventBus = BuckEventBusFactory.newInstance();
    private Platform platform = Platform.detect();

    public CommandRunnerParamsForTesting build() {
      return new CommandRunnerParamsForTesting(
          console,
          projectFilesystem,
          androidDirectoryResolver,
          buildRuleTypes,
          artifactCacheFactory,
          eventBus,
          pythonInterpreter,
          platform);
    }

    public Builder setConsole(Console console) {
      this.console = console;
      return this;
    }
  }
}
