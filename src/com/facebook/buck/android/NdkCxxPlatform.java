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

package com.facebook.buck.android;

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.util.immutables.BuckStyleImmutable;

import org.immutables.value.Value;

import java.nio.file.Path;

/**
 * Adds Android-specific tools to {@link CxxPlatform}.
 */
@Value.Immutable
@BuckStyleImmutable
public interface NdkCxxPlatform {

  CxxPlatform getCxxPlatform();

  Path getObjcopy();

  NdkCxxPlatforms.CxxRuntime getCxxRuntime();

  /**
   * @return the {@link Path} to the C/C++ runtime library.
   */
  Path getCxxSharedRuntimePath();
}
