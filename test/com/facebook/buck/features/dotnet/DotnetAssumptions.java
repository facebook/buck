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

package com.facebook.buck.features.dotnet;

import static org.junit.Assume.assumeTrue;

import com.facebook.buck.io.ExecutableFinder;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class DotnetAssumptions {

  private DotnetAssumptions() {
    // Utility class
  }

  public static void assumeCscIsAvailable(ImmutableMap<String, String> env) {
    Optional<Path> csc = new ExecutableFinder().getOptionalExecutable(Paths.get("csc"), env);

    assumeTrue("Unable to find csc", csc.isPresent());
  }
}
