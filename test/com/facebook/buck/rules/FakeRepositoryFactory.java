/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.util.environment.EnvironmentFilter;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class FakeRepositoryFactory extends RepositoryFactory {

  private static final Path FAKE_PATH = Paths.get("/fake/repo/factory/path");

  public FakeRepositoryFactory() throws InterruptedException, IOException {
    this(FAKE_PATH);
    cachedRepositories.put(FAKE_PATH, new TestRepositoryBuilder().setRootPath(FAKE_PATH).build());
  }

  public FakeRepositoryFactory(Path root) {
    super(
        EnvironmentFilter.filteredEnvironment(
            ImmutableMap.copyOf(System.getenv()), Platform.detect()),
        Platform.detect(),
        new TestConsole(),
        root);
  }

  public FakeRepositoryFactory setRootRepoForTesting(Repository repo) {
    Path root = canonicalPathNames.inverse().get(Optional.<String>absent());
    cachedRepositories.put(root, repo);
    canonicalPathNames.forcePut(root, Optional.<String>absent());
    return this;
  }
}
