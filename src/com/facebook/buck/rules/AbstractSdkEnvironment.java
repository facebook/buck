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

package com.facebook.buck.rules;

import com.facebook.buck.apple.AppleSdk;
import com.facebook.buck.apple.AppleSdkPaths;
import com.facebook.buck.apple.AppleToolchain;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.collect.ImmutableMap;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleTuple
abstract class AbstractSdkEnvironment {
  // iOS
  public abstract Optional<ImmutableMap<AppleSdk, AppleSdkPaths>> getAppleSdkPaths();

  public abstract Optional<ImmutableMap<String, AppleToolchain>> getAppleToolchains();
  // Android
  public abstract Optional<Path> getAndroidSdkPath();

  public abstract Optional<Path> getAndroidNdkPath();

  public abstract Optional<String> getNdkVersion();
}
