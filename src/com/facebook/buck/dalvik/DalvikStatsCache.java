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

package com.facebook.buck.dalvik;

import com.facebook.buck.java.classes.FileLike;
import com.google.common.collect.MapMaker;

import java.io.InputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentMap;

/**
 * Cache to memoize results from DalvikStatsTool.
 */
class DalvikStatsCache {

  private final ConcurrentMap<FileLike, DalvikStatsTool.Stats> cache;

  DalvikStatsCache() {
    cache = new MapMaker().weakKeys().makeMap();
  }

  DalvikStatsTool.Stats getStats(FileLike entry) {
    String name = entry.getRelativePath();
    if (!name.endsWith(".class")) {
      // Probably something like a pom.properties file in a JAR: this does not contribute
      // to the linear alloc size, so return zero.
      return DalvikStatsTool.Stats.ZERO;
    }

    DalvikStatsTool.Stats stats = cache.get(entry);
    if (stats != null) {
      return stats;
    }

    try (InputStream is = entry.getInput()) {
      stats = DalvikStatsTool.getEstimate(is);
      cache.put(entry, stats);
      return stats;
    } catch (IOException e) {
      throw new RuntimeException(String.format("Error calculating size for %s.", name), e);
    } catch (RuntimeException e) {
      throw new RuntimeException(String.format("Error calculating size for %s.", name), e);
    }
  }
}
