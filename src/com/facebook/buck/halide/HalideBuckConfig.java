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

package com.facebook.buck.halide;

import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cxx.CxxPlatform;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import java.util.Map;

/**
 * A Halide-specific "view" of BuckConfig.
 */
public class HalideBuckConfig {
  private final BuckConfig delegate;

  public HalideBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  public Optional<String> getHalideTargetForPlatform(Optional<CxxPlatform> cxxPlatform) {
    Optional<String> target = Optional.absent();
    if (cxxPlatform.isPresent()) {
      String flavorName = cxxPlatform.get().getFlavor().toString();
      ImmutableMap<String, String> targetMap = getHalideTargetMap();
      if (targetMap.containsKey(flavorName)) {
        target = Optional.of(targetMap.get(flavorName));
      }
    }
    return target;
  }

  public ImmutableMap<String, String> getHalideTargetMap() {
    ImmutableMap<String, String> allEntries = delegate.getEntriesForSection("halide");
    ImmutableMap.Builder<String, String> targets = ImmutableMap.builder();
    for (Map.Entry<String, String> entry : allEntries.entrySet()) {
      if (entry.getKey().startsWith("target-")) {
        targets.put(entry.getKey().substring("target-".length()), entry.getValue());
      }
    }
    return targets.build();
  }
}
