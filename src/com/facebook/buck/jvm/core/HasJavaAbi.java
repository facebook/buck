/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.jvm.core;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.ArchiveMemberSourcePath;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.unarchive.Unzip;
import com.facebook.infer.annotation.Assertions;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.jar.JarFile;
import javax.annotation.Nullable;

public interface HasJavaAbi {
  Flavor CLASS_ABI_FLAVOR = InternalFlavor.of("class-abi");
  Flavor SOURCE_ABI_FLAVOR = InternalFlavor.of("source-abi");
  Flavor SOURCE_ONLY_ABI_FLAVOR = InternalFlavor.of("source-only-abi");
  Flavor VERIFIED_SOURCE_ABI_FLAVOR = InternalFlavor.of("verified-source-abi");

  static BuildTarget getClassAbiJar(BuildTarget libraryTarget) {
    Preconditions.checkArgument(isLibraryTarget(libraryTarget));
    return libraryTarget.withAppendedFlavors(CLASS_ABI_FLAVOR);
  }

  static boolean isAbiTarget(BuildTarget target) {
    return isClassAbiTarget(target)
        || isSourceAbiTarget(target)
        || isSourceOnlyAbiTarget(target)
        || isVerifiedSourceAbiTarget(target);
  }

  static boolean isClassAbiTarget(BuildTarget target) {
    return target.getFlavors().contains(CLASS_ABI_FLAVOR);
  }

  static BuildTarget getSourceAbiJar(BuildTarget libraryTarget) {
    Preconditions.checkArgument(isLibraryTarget(libraryTarget));
    return libraryTarget.withAppendedFlavors(SOURCE_ABI_FLAVOR);
  }

  static boolean isSourceAbiTarget(BuildTarget target) {
    return target.getFlavors().contains(SOURCE_ABI_FLAVOR);
  }

  static BuildTarget getSourceOnlyAbiJar(BuildTarget libraryTarget) {
    Preconditions.checkArgument(isLibraryTarget(libraryTarget));
    return libraryTarget.withAppendedFlavors(SOURCE_ONLY_ABI_FLAVOR);
  }

  static boolean isSourceOnlyAbiTarget(BuildTarget target) {
    return target.getFlavors().contains(SOURCE_ONLY_ABI_FLAVOR);
  }

  static BuildTarget getVerifiedSourceAbiJar(BuildTarget libraryTarget) {
    Preconditions.checkArgument(isLibraryTarget(libraryTarget));
    return libraryTarget.withAppendedFlavors(VERIFIED_SOURCE_ABI_FLAVOR);
  }

  static boolean isVerifiedSourceAbiTarget(BuildTarget target) {
    return target.getFlavors().contains(VERIFIED_SOURCE_ABI_FLAVOR);
  }

  static boolean isLibraryTarget(BuildTarget target) {
    return !isAbiTarget(target);
  }

  static BuildTarget getLibraryTarget(BuildTarget abiTarget) {
    Preconditions.checkArgument(isAbiTarget(abiTarget));

    return abiTarget.withoutFlavors(
        CLASS_ABI_FLAVOR, SOURCE_ABI_FLAVOR, SOURCE_ONLY_ABI_FLAVOR, VERIFIED_SOURCE_ABI_FLAVOR);
  }

  BuildTarget getBuildTarget();

  ImmutableSortedSet<SourcePath> getJarContents();

  boolean jarContains(String path);

  /** @return the {@link SourcePath} representing the ABI Jar for this rule. */
  default Optional<BuildTarget> getAbiJar() {
    return Optional.of(getBuildTarget().withAppendedFlavors(CLASS_ABI_FLAVOR));
  }

  default Optional<BuildTarget> getSourceOnlyAbiJar() {
    return Optional.empty();
  }

  class JarContentsSupplier {
    private SourcePathResolver resolver;
    @Nullable private final SourcePath jarSourcePath;
    @Nullable private ImmutableSortedSet<SourcePath> contents;
    @Nullable private ImmutableSet<Path> contentPaths;

    public JarContentsSupplier(SourcePathResolver resolver, @Nullable SourcePath jarSourcePath) {
      this.resolver = resolver;
      this.jarSourcePath = jarSourcePath;
    }

    public void load() throws IOException {
      if (jarSourcePath == null) {
        contents = ImmutableSortedSet.of();
      } else {
        Path jarAbsolutePath = resolver.getAbsolutePath(jarSourcePath);
        if (Files.isDirectory(jarAbsolutePath)) {
          BuildTargetSourcePath buildTargetSourcePath = (BuildTargetSourcePath) jarSourcePath;
          contents =
              Files.walk(jarAbsolutePath)
                  .filter(path -> !path.endsWith(JarFile.MANIFEST_NAME))
                  .map(
                      path ->
                          ExplicitBuildTargetSourcePath.of(buildTargetSourcePath.getTarget(), path))
                  .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
        } else {
          SourcePath nonNullJarSourcePath = Assertions.assertNotNull(jarSourcePath);
          contents =
              Unzip.getZipMembers(jarAbsolutePath)
                  .stream()
                  .filter(path -> !path.endsWith(JarFile.MANIFEST_NAME))
                  .map(path -> ArchiveMemberSourcePath.of(nonNullJarSourcePath, path))
                  .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
        }
        contentPaths =
            contents
                .stream()
                .map(
                    sourcePath -> {
                      if (sourcePath instanceof ExplicitBuildTargetSourcePath) {
                        return ((ExplicitBuildTargetSourcePath) sourcePath).getResolvedPath();
                      } else {
                        return ((ArchiveMemberSourcePath) sourcePath).getMemberPath();
                      }
                    })
                .collect(ImmutableSet.toImmutableSet());
      }
    }

    public ImmutableSortedSet<SourcePath> get() {
      return Preconditions.checkNotNull(contents, "Must call load first.");
    }

    public boolean jarContains(String path) {
      return contentPaths.contains(Paths.get(path));
    }

    public void updateSourcePathResolver(SourcePathResolver resolver) {
      this.resolver = resolver;
    }
  }
}
