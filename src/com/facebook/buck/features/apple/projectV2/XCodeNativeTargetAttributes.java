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

package com.facebook.buck.features.apple.projectV2;

import com.facebook.buck.apple.AppleAssetCatalogDescriptionArg;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleResourceDescriptionArg;
import com.facebook.buck.apple.AppleWrapperResourceArg;
import com.facebook.buck.apple.xcode.xcodeproj.SourceTreePath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

/** A native Xcode target, with a breakdown of all of its files. */
@BuckStyleValueWithBuilder
abstract class XCodeNativeTargetAttributes {

  public abstract AppleConfig appleConfig();

  public abstract Optional<BuildTarget> target();

  @Value.Derived
  public Path shell() {
    return appleConfig().shellPath();
  }

  @Value.Derived
  public Path buildScriptPath() {
    return appleConfig().buildScriptPath();
  }

  public abstract Optional<XcodeProductMetadata> product();

  @Value.Default
  public boolean frameworkHeadersEnabled() {
    return false;
  }

  @Value.Default
  public ImmutableMap<CxxSource.Type, ImmutableList<String>> langPreprocessorFlags() {
    return ImmutableMap.of();
  }

  @Value.Default
  public ImmutableSet<SourceWithFlags> sourcesWithFlags() {
    return ImmutableSet.of();
  }

  @Value.Default
  public ImmutableSet<SourcePath> extraXcodeSources() {
    return ImmutableSet.of();
  }

  @Value.Default
  public ImmutableSet<SourcePath> extraXcodeFiles() {
    return ImmutableSet.of();
  }

  @Value.Default
  public ImmutableSet<SourcePath> publicHeaders() {
    return ImmutableSet.of();
  }

  @Value.Default
  public ImmutableSet<SourcePath> privateHeaders() {
    return ImmutableSet.of();
  }

  @Value.Default
  public ImmutableList<CoreDataResource> coreDataResources() {
    return ImmutableList.of();
  }

  public abstract Optional<SourcePath> prefixHeader();

  public abstract Optional<SourcePath> infoPlist();

  public abstract Optional<SourcePath> bridgingHeader();

  public abstract Optional<Path> buckFilePath();

  @Value.Lazy
  public Optional<Path> packagePath() {
    return buckFilePath().map(buckFilePath -> buckFilePath.getParent());
  }

  @Value.Default
  public ImmutableSet<AppleResourceDescriptionArg> directResources() {
    return ImmutableSet.of();
  }

  @Value.Default
  public ImmutableSet<AppleAssetCatalogDescriptionArg> directAssetCatalogs() {
    return ImmutableSet.of();
  }

  /** Wrapper resources contain bundle resources, e.g. Scene Kit Assets. Similar to xcassets */
  @Value.Default
  public ImmutableSet<AppleWrapperResourceArg> wrapperResources() {
    return ImmutableSet.of();
  }

  @Value.Default
  public ImmutableList<XcconfigBaseConfiguration> xcconfigs() {
    return ImmutableList.of();
  }

  public abstract Optional<Path> entitlementsPlistPath();

  @Value.Default
  public ImmutableList<SourceTreePath> products() {
    return ImmutableList.of();
  }

  @Value.Default
  public ImmutableList<SourceTreePath> frameworks() {
    return ImmutableList.of();
  }

  @Value.Default
  public ImmutableList<SourceTreePath> dependencies() {
    return ImmutableList.of();
  }

  @Value.Default
  public ImmutableList<SourcePath> genruleFiles() {
    return ImmutableList.of();
  }

  @Value.Default
  public ImmutableList<FrameworkPath> systemFrameworks() {
    return ImmutableList.of();
  }
}
