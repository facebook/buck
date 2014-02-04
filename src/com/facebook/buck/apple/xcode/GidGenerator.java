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

package com.facebook.buck.apple.xcode;

import com.google.common.collect.Sets;

import java.util.Random;
import java.util.Set;

/**
 * Generator for Global ID (GID) which are present on every xcode project object.
 *
 * The GID is a 96 bit identifier that's unique on a per-project basis.
 */
public class GidGenerator {
  private final Random random;
  private final Set<String> generatedIDs;

  public GidGenerator(long seed) {
    random = new Random(seed);
    generatedIDs = Sets.newHashSet();
  }

  public String genGID() {
    String gid;

    do {
      StringBuilder builder = new StringBuilder();

      for (int i = 0; i < 3; i++) {
        builder.append(String.format("%08X", random.nextLong() & 0xFFFFFFFFL));
      }

      gid = builder.toString();
    } while (generatedIDs.contains(gid));

    generatedIDs.add(gid);
    return gid;
  }
}
