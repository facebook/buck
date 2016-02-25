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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.ImmutableFlavor;
import com.google.common.base.Optional;

import java.nio.file.Paths;

public class PythonTestUtils {

  private PythonTestUtils() {}

  public static final PythonPlatform PYTHON_PLATFORM =
      PythonPlatform.of(
          ImmutableFlavor.of("default"),
          new PythonEnvironment(
              Paths.get("python"),
              PythonVersion.of("2.6")),
          Optional.<BuildTarget>absent());

  public static final FlavorDomain<PythonPlatform> PYTHON_PLATFORMS =
      FlavorDomain.of("python", PYTHON_PLATFORM);

}
