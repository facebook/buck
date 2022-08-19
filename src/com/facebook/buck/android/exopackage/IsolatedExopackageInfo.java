/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

/**
 * Isolated ExopackageInfo. Doesn't have any references to SourcePath and other buck's internal data
 * structures.
 */
@BuckStyleValueWithBuilder
public abstract class IsolatedExopackageInfo {

  /** Isolated DexInfo */
  @BuckStyleValue
  public interface IsolatedDexInfo {
    AbsPath getMetadata();

    AbsPath getDirectory();

    static IsolatedExopackageInfo.IsolatedDexInfo of(AbsPath metadata, AbsPath directory) {
      return ImmutableIsolatedDexInfo.ofImpl(metadata, directory);
    }
  }

  /** Isolated NativeLibsInfo */
  @BuckStyleValue
  public interface IsolatedNativeLibsInfo {
    AbsPath getMetadata();

    AbsPath getDirectory();

    static IsolatedExopackageInfo.IsolatedNativeLibsInfo of(AbsPath metadata, AbsPath directory) {
      return ImmutableIsolatedNativeLibsInfo.ofImpl(metadata, directory);
    }
  }

  /** Isolated ResourcesInfo */
  @BuckStyleValue
  public interface IsolatedResourcesInfo {
    ImmutableList<IsolatedExopackagePathAndHash> getResourcesPaths();

    static IsolatedExopackageInfo.IsolatedResourcesInfo of(
        ImmutableList<IsolatedExopackagePathAndHash> resourcesPaths) {
      return ImmutableIsolatedResourcesInfo.ofImpl(resourcesPaths);
    }
  }

  /** Isolated ExopackagePathAndHash */
  @BuckStyleValue
  public interface IsolatedExopackagePathAndHash extends AddsToRuleKey {

    static IsolatedExopackagePathAndHash of(AbsPath path, AbsPath hashPath) {
      return ImmutableIsolatedExopackagePathAndHash.ofImpl(path, hashPath);
    }

    AbsPath getPath();

    AbsPath getHashPath();
  }

  public abstract Optional<IsolatedExopackageInfo.IsolatedDexInfo> getDexInfo();

  public abstract Optional<ImmutableList<IsolatedExopackageInfo.IsolatedDexInfo>> getModuleInfo();

  public abstract Optional<IsolatedExopackageInfo.IsolatedNativeLibsInfo> getNativeLibsInfo();

  public abstract Optional<IsolatedExopackageInfo.IsolatedResourcesInfo> getResourcesInfo();
}
