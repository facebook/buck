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

package com.facebook.buck.util.autosparse;

import com.facebook.buck.config.Config;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import org.immutables.value.Value;

@Value.Immutable(copy = false)
@BuckStyleTuple
public abstract class AbstractAutoSparseConfig {
  public abstract boolean enabled();
  // Paths to directories, relative to the working copy root ,that should not be added directly
  // to the sparse profile (AutoSparse should instead only add directly contained files,
  // individually).
  public abstract Set<Path> ignoredPaths();

  public static AutoSparseConfig of(boolean enabled, List<String> ignore) {
    ImmutableSet.Builder<Path> ignoredPaths = ImmutableSet.builder();
    for (String path : ignore) {
      ignoredPaths.add(Paths.get(path));
    }
    return AutoSparseConfig.of(enabled, ignoredPaths.build());
  }

  public static AutoSparseConfig of(Config config) {
    return AutoSparseConfig.of(
        config.getBooleanValue("project", "enable_autosparse", false),
        config.getListWithoutComments("autosparse", "ignore"));
  }
}
