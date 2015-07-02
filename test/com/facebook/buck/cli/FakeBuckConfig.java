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
import com.facebook.buck.util.Config;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;

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
        Platform.detect(),
        new FakeProjectFilesystem(),
        ImmutableMap.copyOf(System.getenv()));
  }

  public FakeBuckConfig(ProjectFilesystem filesystem) {
    this(EMPTY_SECTIONS, Platform.detect(), filesystem, ImmutableMap.copyOf(System.getenv()));
  }

  public FakeBuckConfig(ImmutableMap<String, ImmutableMap<String, String>> sections) {
    this(
        sections,
        Platform.detect(),
        new FakeProjectFilesystem(),
        ImmutableMap.copyOf(System.getenv()));
  }

  public FakeBuckConfig(
      ImmutableMap<String, ImmutableMap<String, String>> sections,
      ProjectFilesystem filesystem) {
    this(sections, Platform.detect(), filesystem, ImmutableMap.copyOf(System.getenv()));
  }

  public FakeBuckConfig(Platform platform) {
    this(
        EMPTY_SECTIONS,
        platform,
        new FakeProjectFilesystem(),
        ImmutableMap.copyOf(System.getenv()));
  }

  public FakeBuckConfig(
      ImmutableMap<String, ImmutableMap<String, String>> sections,
      ImmutableMap<String, String> environment) {
    this(sections, Platform.detect(), new FakeProjectFilesystem(), environment);
  }

  private FakeBuckConfig(
      ImmutableMap<String, ImmutableMap<String, String>> sections,
      Platform platform,
      ProjectFilesystem filesystem,
      ImmutableMap<String, String> environment) {
    super(
        new Config(sections),
        filesystem,
        platform,
        environment);
  }
}
