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

package com.facebook.buck.android.exopackage;

import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.immutables.value.Value;

@Value.Immutable
@Value.Enclosing
@BuckStyleImmutable
abstract class AbstractExopackageInfo {
  @Value.Immutable
  interface AbstractDexInfo {
    @Value.Parameter
    SourcePath getMetadata();

    @Value.Parameter
    SourcePath getDirectory();
  }

  @Value.Immutable
  interface AbstractNativeLibsInfo {
    @Value.Parameter
    SourcePath getMetadata();

    @Value.Parameter
    SourcePath getDirectory();
  }

  @Value.Immutable
  interface AbstractResourcesInfo {
    @Value.Parameter
    List<ExopackagePathAndHash> getResourcesPaths();
  }

  public abstract Optional<ExopackageInfo.DexInfo> getDexInfo();

  public abstract Optional<ImmutableList<ExopackageInfo.DexInfo>> getModuleInfo();

  public abstract Optional<ExopackageInfo.NativeLibsInfo> getNativeLibsInfo();

  public abstract Optional<ExopackageInfo.ResourcesInfo> getResourcesInfo();

  public Stream<SourcePath> getRequiredPaths() {
    Stream.Builder<SourcePath> paths = Stream.builder();
    getNativeLibsInfo()
        .ifPresent(
            info -> {
              paths.add(info.getMetadata());
              paths.add(info.getDirectory());
            });
    getDexInfo()
        .ifPresent(
            info -> {
              paths.add(info.getMetadata());
              paths.add(info.getDirectory());
            });
    getModuleInfo()
        .ifPresent(
            modules ->
                modules.forEach(
                    info -> {
                      paths.add(info.getMetadata());
                      paths.add(info.getDirectory());
                    }));
    getResourcesInfo()
        .ifPresent(
            info ->
                info.getResourcesPaths()
                    .forEach(
                        pair -> {
                          paths.add(pair.getPath());
                          paths.add(pair.getHashPath());
                        }));
    return paths.build();
  }

  @Value.Check
  protected void check() {
    Preconditions.checkArgument(
        getDexInfo().isPresent()
            || getNativeLibsInfo().isPresent()
            || getResourcesInfo().isPresent()
            || getModuleInfo().isPresent(),
        "ExopackageInfo must have something to install.");
  }
}
