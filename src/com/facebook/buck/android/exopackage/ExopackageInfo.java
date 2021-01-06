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

package com.facebook.buck.android.exopackage;

import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import java.util.stream.Stream;
import org.immutables.value.Value;

@BuckStyleValueWithBuilder
public abstract class ExopackageInfo {
  @BuckStyleValue
  public interface DexInfo {
    SourcePath getMetadata();

    SourcePath getDirectory();

    static DexInfo of(SourcePath metadata, SourcePath directory) {
      return ImmutableDexInfo.of(metadata, directory);
    }
  }

  @BuckStyleValue
  public interface NativeLibsInfo {
    SourcePath getMetadata();

    SourcePath getDirectory();

    static NativeLibsInfo of(SourcePath metadata, SourcePath directory) {
      return ImmutableNativeLibsInfo.of(metadata, directory);
    }
  }

  @BuckStyleValue
  public interface ResourcesInfo {
    ImmutableList<ExopackagePathAndHash> getResourcesPaths();

    static ResourcesInfo of(ImmutableList<ExopackagePathAndHash> resourcesPaths) {
      return ImmutableResourcesInfo.of(resourcesPaths);
    }
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

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder extends ImmutableExopackageInfo.Builder {}
}
