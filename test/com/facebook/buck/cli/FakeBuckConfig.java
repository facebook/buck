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
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;

import org.easymock.EasyMock;

import java.nio.file.Path;
import java.util.Map;

/**
 * Implementation of {@link BuckConfig} with no data, or only the data specified by
 * {@link FakeBuckConfig#FakeBuckConfig(Map)}. This makes it possible to get an instance of a
 * {@link BuckConfig} without reading {@code .buckconfig} files from disk. Designed exclusively for
 * testing.
 */
public class FakeBuckConfig extends BuckConfig {

  private static final Map<String, Map<String, String>> EMPTY_SECTIONS =
      ImmutableMap.<String, Map<String, String>>of();

  public FakeBuckConfig() {
    this(EMPTY_SECTIONS, Platform.detect(), null, ImmutableMap.copyOf(System.getenv()));
  }

  public FakeBuckConfig(ProjectFilesystem filesystem) {
    this(EMPTY_SECTIONS, Platform.detect(), filesystem, ImmutableMap.copyOf(System.getenv()));
  }

  public FakeBuckConfig(Map<String, Map<String, String>> sections) {
    this(sections, Platform.detect(), null, ImmutableMap.copyOf(System.getenv()));
  }

  public FakeBuckConfig(Map<String, Map<String, String>> sections, ProjectFilesystem filesystem) {
    this(sections, Platform.detect(), filesystem, ImmutableMap.copyOf(System.getenv()));
  }

  public FakeBuckConfig(Platform platform) {
    this(EMPTY_SECTIONS, platform, null, ImmutableMap.copyOf(System.getenv()));
  }

  public FakeBuckConfig(ImmutableMap<String, String> environment) {
    this(EMPTY_SECTIONS, Platform.detect(), null, environment);
  }

  private FakeBuckConfig(
      Map<String, Map<String, String>> sections,
      Platform platform,
      ProjectFilesystem filesystem,
      ImmutableMap<String, String> environment) {
    super(
        sections,
        filesystem == null ? EasyMock.createMock(ProjectFilesystem.class) : filesystem,
        new BuildTargetParser(),
        platform,
        environment,
        ImmutableMap.<String, Path>of());
  }
}
