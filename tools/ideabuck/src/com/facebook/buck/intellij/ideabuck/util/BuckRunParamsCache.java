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

package com.facebook.buck.intellij.ideabuck.util;

import com.facebook.buck.intellij.ideabuck.api.BuckTarget;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** A in memory cache to store user provided params for running Buck targets */
public class BuckRunParamsCache {
  private Map<BuckTarget, String> targetToParams = new HashMap<>();

  private static class BuckParamsCacheHolder {
    static final BuckRunParamsCache INSTANCE = new BuckRunParamsCache();
  }

  public static BuckRunParamsCache getInstance() {
    return BuckParamsCacheHolder.INSTANCE;
  }

  private BuckRunParamsCache() {}

  public void insertParam(BuckTarget target, String param) {
    targetToParams.put(target, param);
  }

  public Optional<String> getParam(BuckTarget target) {
    return Optional.ofNullable(targetToParams.get(target));
  }
}
