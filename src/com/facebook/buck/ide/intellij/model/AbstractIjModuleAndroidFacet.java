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

import com.facebook.buck.util.immutables.BuckStyleImmutable;
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
  /** @return path to the AndroidManifest.xml file. */
  public abstract Optional<Path> getManifestPath();

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
  public abstract boolean isAndroidLibrary();

  /**
   * @return The package that the R file should be located in. This is used by android_resources
   *     targets to specify the package their resources should referenced via.
   */
  public abstract Optional<String> getPackageName();

  public abstract boolean autogenerateSources();

  public abstract Path getGeneratedSourcePath();
}
