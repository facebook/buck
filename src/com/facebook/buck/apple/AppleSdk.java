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

package com.facebook.buck.apple;

import com.facebook.buck.util.immutables.BuckStyleImmutable;

import org.immutables.value.Value;

import java.util.Set;

/**
 * Metadata about an Apple SDK.
 */
@Value.Immutable
@BuckStyleImmutable
public interface AppleSdk {
  /**
   * The full name of the SDK. For example: {@code iphonesimulator8.0}.
   */
  String getName();

  /**
   * The version number of the SDK. For example: {@code 8.0}.
   */
  String getVersion();

  /**
   * The version of Xcode under which the SDK is installed. For example: {@code 6A2008a}
   */
  String getXcodeVersion();

  /**
   * The platform of the SDK. For example, {@link ApplePlatform#IPHONESIMULATOR}.
   */
  ApplePlatform getApplePlatform();

  /**
   * The architectures supported by the SDK. For example: {@code [i386, x86_64]}.
   */
  Set<String> getArchitectures();

  /**
   * The identifiers for the toolchains used by the SDK. For example:
   * {@code ["com.apple.dt.toolchain.XcodeDefault"]}
   */
  Set<String> getToolchains();
}
