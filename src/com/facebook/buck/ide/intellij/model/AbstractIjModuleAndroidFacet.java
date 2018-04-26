/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.ide.intellij.model;

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.ide.intellij.lang.android.AndroidManifestParser;
import com.facebook.buck.ide.intellij.lang.android.AndroidProjectType;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Ordering;
import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.immutables.value.Value;

/**
 * The information necessary to add the Android facet to the module. This essentially means the Java
 * code in the given module is written against the Android SDK.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractIjModuleAndroidFacet {
  /** @return set of paths to all AndroidManifest.xml files. */
  public abstract ImmutableSet<Path> getManifestPaths();

  /** @return paths to resources (usually stuff under the res/ folder). */
  public abstract Set<Path> getResourcePaths();

  /** @return paths to assets (usually stuff under the assets/ folder). */
  public abstract Set<Path> getAssetPaths();

  /** @return paths to the proguard configuration. */
  public abstract Optional<Path> getProguardConfigPath();

  /**
   * @return whether or not this is an Android Library. IntelliJ identifies libraries using a
   *     is_android_library_project setting in the module iml to allow other modules which depend on
   *     it to inherit assets, resources, etc.
   */
  public boolean isAndroidLibrary() {
    return getAndroidProjectType() == AndroidProjectType.LIBRARY;
  }

  public abstract AndroidProjectType getAndroidProjectType();

  /**
   * @return The package that the R file should be located in. This is used by android_resources
   *     targets to specify the package their resources should referenced via.
   */
  public abstract Optional<String> getPackageName();

  public abstract boolean autogenerateSources();

  /** @return The minimum Android SDK version supported. */
  @Value.Lazy
  public Optional<String> getMinSdkVersion() {
    Ordering<String> byIntegerValue = Ordering.natural().onResultOf(Integer::valueOf);
    return getMinSdkVersions().stream().min(byIntegerValue);
  }

  /** @return either the package name from facet or package name from the first manifest */
  @Value.Lazy
  public Optional<String> discoverPackageName(AndroidManifestParser androidManifestParser) {
    Optional<String> packageName = getPackageName();
    if (packageName.isPresent()) {
      return packageName;
    } else {
      return getPackageFromFirstManifest(androidManifestParser);
    }
  }

  public Optional<Path> getFirstManifestPath() {
    ImmutableSet<Path> androidManifestPaths = getManifestPaths();
    if (androidManifestPaths.isEmpty()) {
      return Optional.empty();
    } else {
      return Optional.of(androidManifestPaths.iterator().next());
    }
  }

  private Optional<String> getPackageFromFirstManifest(
      AndroidManifestParser androidManifestParser) {
    Optional<Path> firstManifest = getFirstManifestPath();
    if (firstManifest.isPresent()) {
      return androidManifestParser.parsePackage(firstManifest.get());
    } else {
      return Optional.empty();
    }
  }

  public abstract ImmutableSet<String> getMinSdkVersions();

  public abstract Path getGeneratedSourcePath();

  /**
   * AndroidManifest.xml can be generated when package name is known. Also, it's not generated when
   * there is exactly one manifest from targets (this manifest will be used in IntelliJ project).
   */
  @Value.Lazy
  public boolean hasValidAndroidManifest() {
    ImmutableSet<Path> androidManifestPaths = getManifestPaths();
    Optional<String> packageName = getPackageName();

    // This is guaranteed during target parsing and creation
    Preconditions.checkState(packageName.isPresent() || !androidManifestPaths.isEmpty());

    return androidManifestPaths.size() == 1
        || (!packageName.isPresent() && androidManifestPaths.size() > 1);
  }

  @Value.Lazy
  public Path getAndroidManifestPath() {
    Path androidManifestPath;
    if (hasValidAndroidManifest()) {
      Optional<String> packageName = getPackageName();
      Preconditions.checkState(packageName.isPresent());
      androidManifestPath =
          getGeneratedSourcePath()
              .resolve(packageName.get().replace('.', File.separatorChar))
              .resolve("AndroidManifest.xml");

    } else {
      androidManifestPath = getManifestPaths().iterator().next();
    }
    return androidManifestPath;
  }
}
