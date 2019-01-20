/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.cxx.toolchain;

import com.facebook.buck.core.model.Flavor;
import com.google.common.hash.Hashing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;

public class CxxFlavorSanitizer {
  private CxxFlavorSanitizer() {}

  public static String sanitize(String name) {
    String fileName = Paths.get(name).getFileName().toString();
    // The hash prevents collisions between "an/example.c", "an_example.c" etc.
    return Flavor.replaceInvalidCharacters(fileName)
        + Hashing.murmur3_32().hashString(name, StandardCharsets.UTF_8);
  }
}
