/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class NativeExoHelper {
  @VisibleForTesting public static final Path NATIVE_LIBS_DIR = Paths.get("native-libs");
  private final AndroidDevice device;
  private final SourcePathResolver pathResolver;
  private final ProjectFilesystem projectFilesystem;
  private final ExopackageInfo.NativeLibsInfo nativeLibsInfo;

  NativeExoHelper(
      AndroidDevice device,
      SourcePathResolver pathResolver,
      ProjectFilesystem projectFilesystem,
      ExopackageInfo.NativeLibsInfo nativeLibsInfo) {
    this.device = device;
    this.pathResolver = pathResolver;
    this.projectFilesystem = projectFilesystem;
    this.nativeLibsInfo = nativeLibsInfo;
  }

  public ImmutableMap<Path, Path> getFilesToInstall() throws Exception {
    ImmutableMap.Builder<Path, Path> filesToInstallBuilder = ImmutableMap.builder();
    ImmutableMap<String, ImmutableMap<String, Path>> filesByHashForAbis = getFilesByHashForAbis();
    for (String abi : filesByHashForAbis.keySet()) {
      ImmutableMap<String, Path> filesByHash =
          Preconditions.checkNotNull(filesByHashForAbis.get(abi));
      Path abiDir = NATIVE_LIBS_DIR.resolve(abi);
      filesToInstallBuilder.putAll(
          ExopackageUtil.applyFilenameFormat(filesByHash, abiDir, "native-%s.so"));
    }
    return filesToInstallBuilder.build();
  }

  public ImmutableMap<Path, String> getMetadataToInstall() throws Exception {
    ImmutableMap<String, ImmutableMap<String, Path>> filesByHashForAbis = getFilesByHashForAbis();
    ImmutableMap.Builder<Path, String> metadataBuilder = ImmutableMap.builder();
    for (String abi : filesByHashForAbis.keySet()) {
      ImmutableMap<String, Path> filesByHash =
          Preconditions.checkNotNull(filesByHashForAbis.get(abi));
      Path abiDir = NATIVE_LIBS_DIR.resolve(abi);
      metadataBuilder.put(
          abiDir.resolve("metadata.txt"), getNativeLibraryMetadataContents(filesByHash));
    }
    return metadataBuilder.build();
  }

  private ImmutableMultimap<String, Path> getAllLibraries() throws IOException {
    return ExopackageInstaller.parseExopackageInfoMetadata(
        pathResolver.getAbsolutePath(nativeLibsInfo.getMetadata()),
        pathResolver.getAbsolutePath(nativeLibsInfo.getDirectory()),
        projectFilesystem);
  }

  private ImmutableMap<String, ImmutableMap<String, Path>> getFilesByHashForAbis()
      throws Exception {
    ImmutableMap.Builder<String, ImmutableMap<String, Path>> filesByHashForAbisBuilder =
        ImmutableMap.builder();
    ImmutableMultimap<String, Path> allLibraries = getAllLibraries();
    ImmutableSet.Builder<String> providedLibraries = ImmutableSet.builder();
    for (String abi : device.getDeviceAbis()) {
      ImmutableMap<String, Path> filesByHash =
          getRequiredLibrariesForAbi(allLibraries, abi, providedLibraries.build());
      if (filesByHash.isEmpty()) {
        continue;
      }
      providedLibraries.addAll(filesByHash.keySet());
      filesByHashForAbisBuilder.put(abi, filesByHash);
    }
    return filesByHashForAbisBuilder.build();
  }

  private ImmutableMap<String, Path> getRequiredLibrariesForAbi(
      ImmutableMultimap<String, Path> allLibraries,
      String abi,
      ImmutableSet<String> ignoreLibraries) {
    return filterLibrariesForAbi(
        pathResolver.getAbsolutePath(nativeLibsInfo.getDirectory()),
        allLibraries,
        abi,
        ignoreLibraries);
  }

  @VisibleForTesting
  public static ImmutableMap<String, Path> filterLibrariesForAbi(
      Path nativeLibsDir,
      ImmutableMultimap<String, Path> allLibraries,
      String abi,
      ImmutableSet<String> ignoreLibraries) {
    ImmutableMap.Builder<String, Path> filteredLibraries = ImmutableMap.builder();
    for (Map.Entry<String, Path> entry : allLibraries.entries()) {
      Path relativePath = nativeLibsDir.relativize(entry.getValue());
      // relativePath is of the form libs/x86/foo.so, or assetLibs/x86/foo.so etc.
      Preconditions.checkState(relativePath.getNameCount() == 3);
      Preconditions.checkState(
          relativePath.getName(0).toString().equals("libs")
              || relativePath.getName(0).toString().equals("assetLibs"));
      String libAbi = relativePath.getParent().getFileName().toString();
      String libName = relativePath.getFileName().toString();
      if (libAbi.equals(abi) && !ignoreLibraries.contains(libName)) {
        filteredLibraries.put(entry);
      }
    }
    return filteredLibraries.build();
  }

  private String getNativeLibraryMetadataContents(ImmutableMap<String, Path> libraries) {
    return Joiner.on('\n')
        .join(
            FluentIterable.from(libraries.entrySet())
                .transform(
                    input -> {
                      String hash = input.getKey();
                      String filename = input.getValue().getFileName().toString();
                      int index = filename.indexOf('.');
                      String libname = index == -1 ? filename : filename.substring(0, index);
                      return String.format("%s native-%s.so", libname, hash);
                    }));
  }
}
