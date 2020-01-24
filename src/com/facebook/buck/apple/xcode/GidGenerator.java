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

package com.facebook.buck.apple.xcode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Generator for Global ID (GID) which are present on every xcode project object.
 *
 * <p>The GID is a 96 bit identifier that's unique on a per-project basis.
 */
public class GidGenerator {
  private final Set<String> generatedAndReservedIds;
  private final Map<String, Integer> pbxClassNameCounterMap;

  public GidGenerator() {
    generatedAndReservedIds = new HashSet<>();
    pbxClassNameCounterMap = new HashMap<>();
  }

  /**
   * Generate a stable GID based on the class name and hash of some object info.
   *
   * <p>GIDs generated this way will be in the form of {@code <class-name-hash-32> <obj-hash-32>
   * <counter-32>}
   */
  public String generateGid(String pbxClassName, int hash) {
    int counter = hash == 0 ? pbxClassNameCounterMap.getOrDefault(pbxClassName, 0) : 0;
    String gid;
    do {
      gid = String.format("%08X%08X%08X", pbxClassName.hashCode(), hash, counter++);
    } while (generatedAndReservedIds.contains(gid));
    generatedAndReservedIds.add(gid);
    if (hash == 0) {
      pbxClassNameCounterMap.put(pbxClassName, counter);
    }
    return gid;
  }
}
