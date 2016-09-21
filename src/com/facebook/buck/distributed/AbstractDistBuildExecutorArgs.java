/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.distributed;

import com.facebook.buck.artifact_cache.ArtifactCache;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.rules.ActionGraphCache;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.step.ExecutorPool;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.facebook.buck.util.environment.Platform;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.ListeningExecutorService;

import org.immutables.value.Value;

import java.util.Map;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractDistBuildExecutorArgs {
  public abstract DistBuildState getState();

  public abstract ObjectMapper getObjectMapper();

  public abstract Cell getRootCell();

  public abstract Parser getParser();

  public abstract BuckEventBus getBuckEventBus();

  public abstract WeightedListeningExecutorService getExecutorService();

  public abstract ActionGraphCache getActionGraphCache();

  public abstract int getCacheKeySeed();

  public abstract Console getConsole();

  public abstract ArtifactCache getArtifactCache();

  public abstract Platform getPlatform();

  public abstract Clock getClock();

  public abstract Map<ExecutorPool, ListeningExecutorService> getExecutors();

  public abstract FileContentsProvider getProvider();

  public BuckConfig getRemoteRootCellConfig() {
    return getState().getRootCell().getBuckConfig();
  }
}
