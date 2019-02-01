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

package com.facebook.buck.core.config;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.impl.DefaultCellPathResolver;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetFactory;
import com.facebook.buck.core.parser.buildtargetparser.UnconfiguredBuildTargetFactory;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.util.config.Config;
import com.facebook.buck.util.config.ConfigBuilder;
import com.facebook.buck.util.config.RawConfig;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;

/**
 * Implementation of {@link BuckConfig} with no data, or only the data specified by {@link
 * FakeBuckConfig.Builder#setSections(ImmutableMap)}}. This makes it possible to get an instance of
 * a {@link BuckConfig} without reading {@code .buckconfig} files from disk. Designed exclusively
 * for testing.
 */
public class FakeBuckConfig {

  private FakeBuckConfig() {
    // Utility class
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private ProjectFilesystem filesystem = new FakeProjectFilesystem();
    private ImmutableMap<String, String> environment = EnvVariablesProvider.getSystemEnv();
    private RawConfig sections = RawConfig.of();
    private Architecture architecture = Architecture.detect();
    private Platform platform = Platform.detect();
    private int numThreads = -1;

    public Builder setArchitecture(Architecture architecture) {
      this.architecture = architecture;
      return this;
    }

    public Builder setEnvironment(ImmutableMap<String, String> environment) {
      this.environment = environment;
      return this;
    }

    public Builder setFilesystem(ProjectFilesystem filesystem) {
      this.filesystem = filesystem;
      return this;
    }

    public Builder setPlatform(Platform platform) {
      this.platform = platform;
      return this;
    }

    public Builder setSections(RawConfig sections) {
      this.sections = sections;
      return this;
    }

    public Builder setSections(ImmutableMap<String, ImmutableMap<String, String>> sections) {
      this.sections = RawConfig.of(sections);
      return this;
    }

    public Builder setSections(String... iniFileLines) {
      sections = ConfigBuilder.rawFromLines(iniFileLines);
      return this;
    }

    public BuckConfig build() {
      Config config = new Config(sections);
      CellPathResolver cellPathResolver =
          DefaultCellPathResolver.of(filesystem.getRootPath(), config);
      UnconfiguredBuildTargetFactory buildTargetFactory =
          new ParsingUnconfiguredBuildTargetFactory();
      return new BuckConfig(
          config,
          filesystem,
          architecture,
          platform,
          environment,
          buildTargetName -> buildTargetFactory.create(cellPathResolver, buildTargetName));
    }
  }
}
