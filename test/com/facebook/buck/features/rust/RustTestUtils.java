/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.features.rust;

import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.model.FlavorDomain;

public class RustTestUtils {

  public static final RustPlatform DEFAULT_PLATFORM =
      RustPlatform.of(CxxPlatformUtils.DEFAULT_PLATFORM);

  public static final FlavorDomain<RustPlatform> DEFAULT_PLATFORMS =
      FlavorDomain.of("Rust Platforms");

  public static final RustToolchain DEFAULT_TOOLCHAIN =
      RustToolchain.of(DEFAULT_PLATFORM, DEFAULT_PLATFORMS);
}
