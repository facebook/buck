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
package com.facebook.buck.cxx;

import com.facebook.buck.cxx.toolchain.DebugPathSanitizer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Map.Entry;
import java.util.Optional;

/**
 * This {@link DebugPathSanitizer} pretty much doesn't do anything. It depends on the platform's
 * tools to do all sanitization themselves.
 */
public class NoopDebugPathSanitizer extends DebugPathSanitizer {
  public static final DebugPathSanitizer INSTANCE = new NoopDebugPathSanitizer();

  private NoopDebugPathSanitizer() {}

  @Override
  public ImmutableMap<String, String> getCompilationEnvironment(
      Path workingDir, boolean shouldSanitize) {
    return ImmutableMap.of();
  }

  @Override
  protected Iterable<Entry<Path, String>> getAllPaths(Optional<Path> workingDir) {
    return ImmutableList.of();
  }

  @Override
  public String getCompilationDirectory() {
    return ".";
  }

  @Override
  public void restoreCompilationDirectory(Path path, Path workingDir) {
    // no-op
  }
}
