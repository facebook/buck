/*
 * Copyright 2014-present Facebook, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may
 *  not use this file except in compliance with the License. You may obtain
 *  a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.facebook.buck.rules;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import org.immutables.value.Value;

import java.nio.file.Path;

@Value.Immutable
@Value.Nested
public abstract class ExopackageInfo {

  @Value.Immutable
  public static interface DexInfo {
    @Value.Parameter
    Path metadata();
    @Value.Parameter
    Path directory();
  }

  @Value.Immutable
  public static interface NativeLibsInfo {
    @Value.Parameter
    Path metadata();
    @Value.Parameter
    Path directory();
  }

  public abstract Optional<DexInfo> dexInfo();
  public abstract Optional<NativeLibsInfo> nativeLibsInfo();

  @Value.Check
  protected void check() {
    Preconditions.checkArgument(
        dexInfo().isPresent() || nativeLibsInfo().isPresent(),
        "ExopackageInfo must either have secondary dexes or native libraries to install.");
  }
}
