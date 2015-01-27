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

package com.facebook.buck.cli;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.io.Reader;

import javax.annotation.Nullable;

public class BuckConfigTestUtils {
  private BuckConfigTestUtils() {}

  public static BuckConfig createWithDefaultFilesystem(
      DebuggableTemporaryFolder temporaryFolder,
      Reader reader,
      @Nullable BuildTargetParser parser)
      throws IOException {
    ProjectFilesystem projectFilesystem = new ProjectFilesystem(temporaryFolder.getRootPath());
    if (parser == null) {
      parser = new BuildTargetParser();
    }
    return createFromReader(
        reader,
        projectFilesystem,
        parser,
        Platform.detect(),
        ImmutableMap.copyOf(System.getenv()));
  }

  public static BuckConfig createFromReader(
      Reader reader,
      ProjectFilesystem projectFilesystem,
      BuildTargetParser buildTargetParser,
      Platform platform,
      ImmutableMap<String, String> environment)
      throws IOException {
    return BuckConfig.createFromReaders(
        ImmutableList.of(reader),
        projectFilesystem,
        buildTargetParser,
        platform,
        environment);
  }
}
