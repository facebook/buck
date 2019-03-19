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

package com.facebook.buck.features.python;

import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.features.python.toolchain.PythonEnvironment;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.features.python.toolchain.PythonVersion;
import java.nio.file.Paths;
import java.util.Optional;

public class PythonTestUtils {

  private PythonTestUtils() {}

  public static final PythonBuckConfig PYTHON_CONFIG =
      new PythonBuckConfig(FakeBuckConfig.builder().build());

  public static final PythonPlatform PYTHON_PLATFORM =
      new TestPythonPlatform(
          InternalFlavor.of("default-py-platform"),
          new PythonEnvironment(
              Paths.get("python"), PythonVersion.of("CPython", "2.6"), PythonBuckConfig.SECTION),
          Optional.empty());

  public static final FlavorDomain<PythonPlatform> PYTHON_PLATFORMS =
      FlavorDomain.of("python", PYTHON_PLATFORM);
}
