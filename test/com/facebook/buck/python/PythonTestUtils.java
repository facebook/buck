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

package com.facebook.buck.python;

import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.InternalFlavor;

import java.nio.file.Paths;
import java.util.Optional;

public class PythonTestUtils {

  private PythonTestUtils() {}

  public static final String CPYTHON_3_5_DISCOVER_OUTPUT =
      getPythonDiscoverOutput("CPython", "3.5");

  public static final String getPythonDiscoverOutput(
      String interpreterName,
      String versionString) {
    return
        "{\"interpreter_name\": \"" + interpreterName + "\", " +
        "\"version_string\": \"" + versionString + "\", " +
        "\"preprocessor_flags\": [\"-I/usr/local/include/python3.5m\", " +
        "\"-I/usr/local/include/python3.5m\"], \"compiler_flags\": " +
        "[\"-I/usr/local/include/python3.5m\", \"-I/usr/local/include/python3.5m\", " +
        "\"-Wno-unused-result\", \"-Wsign-compare\", \"-DNDEBUG\", \"-g\", \"-fwrapv\", " +
        "\"-O3\", \"-Wall\", \"-Wstrict-prototypes\"], \"linker_flags\": " +
        "[\"-L/usr/local/lib/python3.5/config-3.5m\", \"-lpython3.5\", \"-lpthread\", " +
        "\"-ldl\", \"-lutil\", \"-lm\", \"-Xlinker\", \"-export-dynamic\"], " +
        "\"extension_suffix\": \"so\"}";
  }

  public static final PythonPlatform PYTHON_PLATFORM =
      PythonPlatform.of(
          InternalFlavor.of("default"),
          new PythonEnvironment(
              Paths.get("python"),
              PythonVersion.of("CPython", "2.6"),
              Optional.empty()),
          Optional.empty());

  public static final FlavorDomain<PythonPlatform> PYTHON_PLATFORMS =
      FlavorDomain.of("python", PYTHON_PLATFORM);

}
