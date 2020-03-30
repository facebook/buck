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

package com.facebook.buck.command;

import com.facebook.buck.artifact_cache.ArtifactCacheFactory;
import com.facebook.buck.core.build.engine.cache.manager.BuildInfoStoreManager;
import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.concurrent.ExecutorPool;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.timing.Clock;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.Map;

/** Common arguments for running a build. */
@BuckStyleValue
public abstract class BuildExecutorArgs {
  public abstract Console getConsole();

  public abstract BuckEventBus getBuckEventBus();

  public abstract Platform getPlatform();

  public abstract Clock getClock();

  public abstract Cells getCells();

  public abstract ImmutableMap<ExecutorPool, ListeningExecutorService> getExecutors();

  public abstract ProjectFilesystemFactory getProjectFilesystemFactory();

  public abstract BuildInfoStoreManager getBuildInfoStoreManager();

  public abstract ArtifactCacheFactory getArtifactCacheFactory();

  public abstract RuleKeyConfiguration getRuleKeyConfiguration();

  public BuckConfig getBuckConfig() {
    return getCells().getRootCell().getBuckConfig();
  }

  public static BuildExecutorArgs of(
      Console console,
      BuckEventBus buckEventBus,
      Platform platform,
      Clock clock,
      Cells cells,
      Map<ExecutorPool, ? extends ListeningExecutorService> executors,
      ProjectFilesystemFactory projectFilesystemFactory,
      BuildInfoStoreManager buildInfoStoreManager,
      ArtifactCacheFactory artifactCacheFactory,
      RuleKeyConfiguration ruleKeyConfiguration) {
    return ImmutableBuildExecutorArgs.of(
        console,
        buckEventBus,
        platform,
        clock,
        cells,
        executors,
        projectFilesystemFactory,
        buildInfoStoreManager,
        artifactCacheFactory,
        ruleKeyConfiguration);
  }
}
