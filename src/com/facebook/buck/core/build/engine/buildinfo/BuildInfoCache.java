/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.core.build.engine.buildinfo;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.model.BuildTarget;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In memory cache for the build info store, for speeding up build info access when building large
 * targets.
 */
public class BuildInfoCache {

  private final ConcurrentHashMap<AbsPath, Map<BuildTarget, Map<String, String>>> buildInfoCaches =
      new ConcurrentHashMap<>();

  /**
   * Gets the in memory meta data cache of the specified repository root path, which is the same
   * path used to identify the {@link BuildInfoStore}
   *
   * @param path root path to the repository of the {@link BuildInfoStore}
   * @return a map containing build info
   */
  public Map<BuildTarget, Map<String, String>> getMetaDataCache(AbsPath path) {
    return buildInfoCaches.computeIfAbsent(path, new_path -> new HashMap<>());
  }
}
