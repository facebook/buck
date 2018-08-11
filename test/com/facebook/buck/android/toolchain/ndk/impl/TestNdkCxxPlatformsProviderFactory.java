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

package com.facebook.buck.android.toolchain.ndk.impl;

import com.facebook.buck.android.toolchain.ndk.NdkCxxPlatformsProvider;
import com.facebook.buck.core.toolchain.impl.NamedToolchain;
import com.google.common.collect.ImmutableMap;

/**
 * Creates {@link NdkCxxPlatformsProvider} with the default name.
 *
 * <p>This should be used together with {@link
 * com.facebook.buck.core.toolchain.impl.ToolchainProviderBuilder#withToolchain(NamedToolchain)} to
 * provide NDK cxx platforms in tests that rely on them.
 */
public class TestNdkCxxPlatformsProviderFactory {
  public static NamedToolchain createDefaultNdkPlatformsProvider() {
    return NamedToolchain.of(
        NdkCxxPlatformsProvider.DEFAULT_NAME, NdkCxxPlatformsProvider.of(ImmutableMap.of()));
  }
}
