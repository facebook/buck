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

package com.facebook.buck.util;

import com.facebook.buck.io.MorePaths;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.util.Arrays;

class FakeHostFilesystem extends HostFilesystem {

  private final ImmutableSet<Path> knownDirectories;

  public static FakeHostFilesystem empty() {
    return new FakeHostFilesystem(ImmutableSet.<Path>of());
  }

  public static FakeHostFilesystem withDirectories(String... knownDirectories) {
    return new FakeHostFilesystem(
        FluentIterable.from(Arrays.asList(knownDirectories))
            .transform(MorePaths.TO_PATH)
            .toSet());
  }

  @Override
  public boolean isDirectory(Path path) {
    return knownDirectories.contains(path);
  }

  private FakeHostFilesystem(ImmutableSet<Path> knownDirectories) {
    this.knownDirectories = knownDirectories;
  }
}
