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

import com.facebook.buck.android.AndroidBuckConfig;
import com.facebook.buck.android.AndroidDirectoryResolver;
import com.facebook.buck.android.FakeAndroidDirectoryResolver;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.java.FakeJavaPackageFinder;
import com.facebook.buck.java.JavaPackageFinder;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.rules.NoopArtifactCache;
import com.facebook.buck.rules.Repository;
import com.facebook.buck.rules.TestRepositoryBuilder;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProcessManager;
import com.facebook.buck.util.cache.NullFileHashCache;
import com.facebook.buck.util.environment.Platform;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;

public class CommandRunnerParamsForTesting {

  /** Utility class: do not instantiate. */
  private CommandRunnerParamsForTesting() {}

  public static CommandRunnerParams createCommandRunnerParamsForTesting(
      Console console,
      Repository repository,
      AndroidDirectoryResolver androidDirectoryResolver,
      ArtifactCacheFactory artifactCacheFactory,
      BuckEventBus eventBus,
      BuckConfig config,
      Platform platform,
      ImmutableMap<String, String> environment,
      JavaPackageFinder javaPackageFinder,
      ObjectMapper objectMapper,
      Optional<WebServer> webServer)
      throws IOException, InterruptedException {
    return new CommandRunnerParams(
        console,
        repository,
        Main.createAndroidPlatformTargetSupplier(
            androidDirectoryResolver,
            new AndroidBuckConfig(new FakeBuckConfig(), platform),
            eventBus),
        artifactCacheFactory,
        eventBus,
        Parser.createBuildFileParser(
            repository,
            /* useWatchmanGlob */ false),
        platform,
        environment,
        javaPackageFinder,
        objectMapper,
        new DefaultClock(),
        Optional.<ProcessManager>absent(),
        webServer,
        config,
        new NullFileHashCache());
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {

    private AndroidDirectoryResolver androidDirectoryResolver = new FakeAndroidDirectoryResolver();
    private ArtifactCacheFactory artifactCacheFactory = new InstanceArtifactCacheFactory(
        new NoopArtifactCache());
    private Console console = new TestConsole();
    private BuckConfig config = new FakeBuckConfig();
    private BuckEventBus eventBus = BuckEventBusFactory.newInstance();
    private Platform platform = Platform.detect();
    private ImmutableMap<String, String> environment = ImmutableMap.copyOf(System.getenv());
    private JavaPackageFinder javaPackageFinder = new FakeJavaPackageFinder();
    private ObjectMapper objectMapper = new ObjectMapper();
    private Optional<WebServer> webServer = Optional.absent();

    public CommandRunnerParams build()
        throws IOException, InterruptedException{
      return createCommandRunnerParamsForTesting(
          console,
          new TestRepositoryBuilder().build(),
          androidDirectoryResolver,
          artifactCacheFactory,
          eventBus,
          config,
          platform,
          environment,
          javaPackageFinder,
          objectMapper,
          webServer);
    }

    public Builder setConsole(Console console) {
      this.console = console;
      return this;
    }

    public Builder setWebserver(Optional<WebServer> webServer) {
      this.webServer = webServer;
      return this;
    }

    public Builder setArtifactCacheFactory(ArtifactCacheFactory factory) {
      this.artifactCacheFactory = factory;
      return this;
    }

    public Builder setBuckConfig(BuckConfig buckConfig) {
      this.config = buckConfig;
      return this;
    }

  }
}
