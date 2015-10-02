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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;

/**
 * Implementation of {@link BuckConfig} with no data, or only the data specified by
 * {@link FakeBuckConfig#FakeBuckConfig(ImmutableMap)}. This makes it possible to get an instance of
 * a {@link BuckConfig} without reading {@code .buckconfig} files from disk. Designed exclusively
 * for testing.
 */
public class FakeBuckConfig extends BuckConfig {

  private static final ImmutableMap<String, ImmutableMap<String, String>> EMPTY_SECTIONS =
      ImmutableMap.of();

  public FakeBuckConfig() {
    this(
        EMPTY_SECTIONS,
        Architecture.detect(),
        Platform.detect(),
        new FakeProjectFilesystem(),
        ImmutableMap.copyOf(System.getenv()));
  }

  public FakeBuckConfig(ProjectFilesystem filesystem) {
    this(
        EMPTY_SECTIONS,
        Architecture.detect(),
        Platform.detect(),
        filesystem,
        ImmutableMap.copyOf(System.getenv()));
  }

  public FakeBuckConfig(ImmutableMap<String, ImmutableMap<String, String>> sections) {
    this(
        sections,
        Architecture.detect(),
        Platform.detect(),
        new FakeProjectFilesystem(),
        ImmutableMap.copyOf(System.getenv()));
  }

  public FakeBuckConfig(
      ImmutableMap<String, ImmutableMap<String, String>> sections,
      ProjectFilesystem filesystem) {
    this(
        sections,
        Architecture.detect(),
        Platform.detect(),
        filesystem,
        ImmutableMap.copyOf(System.getenv()));
  }

  public FakeBuckConfig(Architecture architecture, Platform platform) {
    this(
        EMPTY_SECTIONS,
        architecture,
        platform,
        new FakeProjectFilesystem(),
        ImmutableMap.copyOf(System.getenv()));
  }

  public FakeBuckConfig(
      ImmutableMap<String, ImmutableMap<String, String>> sections,
      ImmutableMap<String, String> environment) {
    this(
        sections,
        Architecture.detect(),
        Platform.detect(),
        new FakeProjectFilesystem(),
        environment);
  }

  private FakeBuckConfig(
      ImmutableMap<String, ImmutableMap<String, String>> sections,
      Architecture architecture,
      Platform platform,
      ProjectFilesystem filesystem,
      ImmutableMap<String, String> environment) {
    super(
        new Config(sections),
        filesystem,
        architecture,
        platform,
        environment);
  }

  public FakeBuckConfig(String... iniFileLines) throws IOException {
    super(
        new Config(Inis.read(new StringReader(Joiner.on("\n").join(Arrays.asList(iniFileLines))))),
        new FakeProjectFilesystem(),
        Architecture.detect(),
        Platform.detect(),
        ImmutableMap.copyOf(System.getenv()));
  }

  public FakeBuckConfig(ProjectFilesystem filesystem, String... iniFileLines) throws IOException {
    super(
        new Config(Inis.read(new StringReader(Joiner.on("\n").join(Arrays.asList(iniFileLines))))),
        filesystem,
        Architecture.detect(),
        Platform.detect(),
        ImmutableMap.copyOf(System.getenv()));
  }
}
