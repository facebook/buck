/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.io.watchman;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/** Factory class to create Watchman instance for tests */
public class FakeWatchmanFactory {

  /**
   * Create Watchman instance that can be used in tests
   *
   * @param client client instance used by a test, usually FakeWatchmanClient, can be configured for
   *     specified behavior
   * @param rootPath Absolute path to watchman root folder
   * @param watchPath Path to watch changes for
   * @param projectName Identifier of a watch used to get results from queries
   */
  public static Watchman createWatchman(
      WatchmanClient client, Path rootPath, Path watchPath, String projectName) {
    return new Watchman(
        ImmutableMap.of(rootPath, ProjectWatch.of(watchPath.toString(), Optional.of(projectName))),
        ImmutableSet.of(
            Capability.SUPPORTS_PROJECT_WATCH, Capability.DIRNAME, Capability.WILDMATCH_GLOB),
        ImmutableMap.of(),
        Optional.of(Paths.get(".watchman-sock"))) {
      @Override
      public WatchmanClient createClient() {
        return client;
      }
    };
  }
}
