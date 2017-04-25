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

import com.facebook.buck.rules.ExopackageInfo.DexInfo;
import com.facebook.buck.rules.ExopackageInfo.NativeLibsInfo;
import com.facebook.buck.rules.ExopackageInfo.ResourcesInfo;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Preconditions;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable
@Value.Enclosing
@BuckStyleImmutable
abstract class AbstractExopackageInfo {

  @Value.Immutable
  interface AbstractDexInfo {
    @Value.Parameter
    Path getMetadata();

    @Value.Parameter
    Path getDirectory();
  }

  @Value.Immutable
  interface AbstractNativeLibsInfo {
    @Value.Parameter
    Path getMetadata();

    @Value.Parameter
    Path getDirectory();
  }

  @Value.Immutable
  interface AbstractResourcesInfo {
    @Value.Parameter
    List<SourcePath> getResourcesPaths();
  }

  public abstract Optional<DexInfo> getDexInfo();

  public abstract Optional<NativeLibsInfo> getNativeLibsInfo();

  public abstract Optional<ResourcesInfo> getResourcesInfo();

  @Value.Check
  protected void check() {
    Preconditions.checkArgument(
        getDexInfo().isPresent()
            || getNativeLibsInfo().isPresent()
            || getResourcesInfo().isPresent(),
        "ExopackageInfo must have something to install.");
  }
}
