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

package com.facebook.buck.features.project.intellij.model;

import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.features.project.intellij.IjAndroidHelper;
import com.facebook.buck.features.project.intellij.Util;
import com.facebook.buck.features.project.intellij.lang.android.AndroidProjectType;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Ordering;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.stream.Collectors;
import org.immutables.value.Value;

/**
 * The information necessary to add the Android facet to the module. This essentially means the Java
 * code in the given module is written against the Android SDK.
 */
@BuckStyleValueWithBuilder
public abstract class IjModuleAndroidFacet {
  public static final String ANDROID_MANIFEST = "AndroidManifest.xml";

  /** @return set of paths to all AndroidManifest.xml files. */
  public abstract ImmutableSet<Path> getManifestPaths();

  /** @return paths to resources (usually stuff under the res/ folder). */
  public abstract ImmutableSet<Path> getResourcePaths();

  /** @return paths to assets (usually stuff under the assets/ folder). */
  public abstract ImmutableSet<Path> getAssetPaths();

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
    return getMinSdkVersions().stream().filter(IjModuleAndroidFacet::isInteger).min(byIntegerValue);
  }

  private static boolean isInteger(String s) {
    try {
      Integer.valueOf(s);
      return true;
    } catch (NumberFormatException e) {
      return false;
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

  public abstract ImmutableSet<String> getMinSdkVersions();

  public abstract Path getGeneratedSourcePath();

  public abstract ImmutableSet<String> getPermissions();

  /** Get the package name from the facet. If empty, use the one from the project config */
  public Optional<String> getPackageNameOrDefault(IjProjectConfig projectConfig) {
    return getPackageName().isEmpty()
        ? projectConfig.getDefaultAndroidManifestPackageName()
        : getPackageName();
  }

  /** Get the min SDK version from the facet. If empty, use the one from the project config */
  public Optional<String> getMinSdkVersionOrDefault(IjProjectConfig projectConfig) {
    return getMinSdkVersion().isEmpty()
        ? projectConfig.getMinAndroidSdkVersion()
        : getMinSdkVersion();
  }

  /** Returns the path to the AndroidManifest.xml for this facet */
  public Optional<Path> getAndroidManifestPath(
      ProjectFilesystem filesystem, IjProjectConfig projectConfig) {
    if (projectConfig.isGeneratingAndroidManifestEnabled() && shouldGenerateAndroidManifest()) {
      // We are not able to generate AndroidManifest.xml if we don't have any information from the
      // facet or the buckconfig. If this happens, we can use the default project-wide
      // AndroidManifest.xml if available
      if (projectConfig.getAndroidManifest().isPresent()
          && isAndroidManifestEmpty()
          && projectConfig.getDefaultAndroidManifestPackageName().isEmpty()
          && projectConfig.getMinAndroidSdkVersion().isEmpty()) {
        return projectConfig.getAndroidManifest();
      }
      return Optional.of(getGeneratedAndroidManifestPath(filesystem, projectConfig));
    }

    Optional<Path> firstManifest = getFirstManifestPath();
    if (firstManifest.isPresent()) {
      return firstManifest;
    } else {
      return projectConfig.getAndroidManifest();
    }
  }

  private boolean isAndroidManifestEmpty() {
    return getPackageName().isEmpty() && getMinSdkVersion().isEmpty() && getPermissions().isEmpty();
  }

  private Path getGeneratedAndroidManifestPath(
      ProjectFilesystem filesystem, IjProjectConfig projectConfig) {
    if (projectConfig.isSharedAndroidManifestGenerationEnabled()) {
      // The path to the AndroidManifest.xml depends on its content
      Path manifestPath = Paths.get(IjAndroidHelper.getAndroidGenDir(filesystem, projectConfig));
      Optional<String> packageName = getPackageNameOrDefault(projectConfig);
      if (packageName.isPresent()) {
        String packagePath = packageName.get().replace('.', File.separatorChar);
        manifestPath = manifestPath.resolve(packagePath);
      }
      // Hashing min SDK version & permissions
      String hashedFolder =
          Util.hash(
              getMinSdkVersionOrDefault(projectConfig).orElse("")
                  + "_"
                  + getPermissions().stream().sorted().collect(Collectors.joining("_")));
      return manifestPath.resolve(hashedFolder).resolve(ANDROID_MANIFEST);
    } else {
      return getGeneratedSourcePath().resolve(ANDROID_MANIFEST);
    }
  }

  /**
   * We can skip the generation of AndroidManifest.xml if and only if we have one
   * AndroidManifest.xml in this facet
   */
  public boolean shouldGenerateAndroidManifest() {
    return getManifestPaths().size() != 1;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder extends ImmutableIjModuleAndroidFacet.Builder {}
}
