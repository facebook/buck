/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.TestProjectFilesystems;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.config.ConfigBuilder;
import com.facebook.buck.util.environment.Architecture;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.Reader;

public class BuckConfigTestUtils {
  private BuckConfigTestUtils() {}

  public static BuckConfig createWithDefaultFilesystem(
      TemporaryPaths temporaryFolder, Reader reader) throws IOException {
    ProjectFilesystem projectFilesystem =
        TestProjectFilesystems.createProjectFilesystem(temporaryFolder.getRoot());
    return createFromReader(
        reader,
        projectFilesystem,
        Architecture.detect(),
        Platform.detect(),
        ImmutableMap.copyOf(System.getenv()));
  }

  public static <T extends ConfigView<BuckConfig>> T createWithDefaultFilesystem(
      TemporaryPaths temporaryFolder, Reader reader, Class<T> viewClass) throws IOException {
    return createWithDefaultFilesystem(temporaryFolder, reader).getView(viewClass);
  }

  public static BuckConfig createFromReader(
      Reader reader,
      ProjectFilesystem projectFilesystem,
      Architecture architecture,
      Platform platform,
      ImmutableMap<String, String> environment)
      throws IOException {
    return FakeBuckConfig.builder()
        .setSections(ConfigBuilder.rawFromReader(reader))
        .setFilesystem(projectFilesystem)
        .setArchitecture(architecture)
        .setPlatform(platform)
        .setEnvironment(environment)
        .build();
  }
}
