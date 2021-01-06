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

package com.facebook.buck.apple.toolchain;

import com.facebook.buck.core.toolchain.ComparableToolchain;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableMap;
import java.util.Map;

@BuckStyleValue
public interface AppleToolchainProvider extends ComparableToolchain {
  String DEFAULT_NAME = "apple-toolchain-provider";

  ImmutableMap<String, AppleToolchain> getAppleToolchains();

  @Override
  default String getName() {
    return DEFAULT_NAME;
  }

  static AppleToolchainProvider of(Map<String, ? extends AppleToolchain> appleToolchains) {
    return ImmutableAppleToolchainProvider.of(appleToolchains);
  }
}
