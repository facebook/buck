/*
 * Copyright 2018-present Facebook, Inc.
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

import com.facebook.buck.android.exopackage.ExopackageInfo.NativeLibsInfo;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Support out-of-process exopackage installation by creating a symlink tree mirroring the desired
 * on-device layout. This allows an external process to call `adb push` or the equivalent without
 * any knowledge of the internals of exo-install (or a dependency on the implementing code)
 */
public class ExopackageSymlinkTree {

  /**
   * Create a symlink tree rooted at rootPath which contains a mirror of what should appear on the
   * device as a result of an exopackage installation.
   *
   * @param packageName
   * @param exoInfo the exopackage info for the app which will be installed
   * @param pathResolver needed to convert the exoInfo SourcePaths into real Paths
   * @param filesystem need to write files/create symlinks/access paths
   * @param rootPath location where the resulting symlink tree will be rooted
   * @throws IOException if the symlink tree or any of its files cannot be written
   */
  public static void createSymlinkTree(
      String packageName,
      ExopackageInfo exoInfo,
      SourcePathResolver pathResolver,
      ProjectFilesystem filesystem,
      Path rootPath)
      throws IOException {

    // Symlink the secondary dex files
    if (exoInfo.getDexInfo().isPresent()) {
      DexExoHelper dexExoHelper =
          new DexExoHelper(pathResolver, filesystem, exoInfo.getDexInfo().get());
      linkFiles(rootPath, dexExoHelper.getFilesToInstall(), filesystem);
      writeMetadata(rootPath, dexExoHelper.getMetadataToInstall(), filesystem);
    }

    // Symlink the native so files
    if (exoInfo.getNativeLibsInfo().isPresent()) {
      NativeLibsInfo nativeLibsInfo = exoInfo.getNativeLibsInfo().get();
      Path metadataPath = pathResolver.getAbsolutePath(nativeLibsInfo.getMetadata());
      // We don't yet know which device is our installation target, so we need to link all abis
      // which were built for the device
      List<String> abis = new ArrayList<>(detectAbis(metadataPath, filesystem));
      NativeExoHelper nativeExoHelper =
          new NativeExoHelper(() -> abis, pathResolver, filesystem, nativeLibsInfo);
      linkFiles(rootPath, nativeExoHelper.getFilesToInstall(), filesystem);
      writeMetadata(rootPath, nativeExoHelper.getMetadataToInstall(), filesystem);
    }

    // Symlink the resources bundle
    if (exoInfo.getResourcesInfo().isPresent()) {
      ResourcesExoHelper resourcesExoHelper =
          new ResourcesExoHelper(pathResolver, filesystem, exoInfo.getResourcesInfo().get());
      linkFiles(rootPath, resourcesExoHelper.getFilesToInstall(), filesystem);
      writeMetadata(rootPath, resourcesExoHelper.getMetadataToInstall(), filesystem);
    }

    // Symlink any app modules which were marked for exopackage install
    if (exoInfo.getModuleInfo().isPresent()) {
      ModuleExoHelper moduleExoHelper =
          new ModuleExoHelper(pathResolver, filesystem, exoInfo.getModuleInfo().get());
      linkFiles(rootPath, moduleExoHelper.getFilesToInstall(), filesystem);
      writeMetadata(rootPath, moduleExoHelper.getMetadataToInstall(), filesystem);
    }
    // Finally, write a metadata file to the root of the directory containing the base path
    // where this filetree should be rooted on the device
    // Write a metadata.txt file which declares where the on-device filetree should
    // be rooted
    Path deviceRootPath = ExopackageInstaller.EXOPACKAGE_INSTALL_ROOT.resolve(packageName);
    filesystem.writeContentsToPath(
        MorePaths.pathWithUnixSeparators(deviceRootPath), rootPath.resolve("metadata.txt"));
  }
  /** Create symlinks for each of the filesToInstall in the destPath with the proper hierarchy */
  private static void linkFiles(
      Path destPathRoot, Map<Path, Path> filesToInstall, ProjectFilesystem filesystem)
      throws IOException {
    // The format of filesToInstall is RelativeDestinationPathOnDevice -> LocalPath
    for (Map.Entry<Path, Path> entry : filesToInstall.entrySet()) {
      Path linkPath = destPathRoot.resolve(entry.getKey());
      if (!Files.exists(linkPath.getParent())) {
        filesystem.mkdirs(linkPath.getParent());
      }
      filesystem.createSymLink(linkPath, entry.getValue(), false);
    }
  }

  /**
   * Write out metadata files relative to a root path. This is used for laying out the metadata.txt
   * files in the destination symlink tree directory.
   *
   * @param destPathRoot root path where the metadata files should use a relative root
   * @param pathsAndContents a mapping of paths relative to the destPathRoot and the string contents
   *     they contain
   * @param filesystem project filesystem to resolve our buck-out
   * @throws IOException if a file cannot be written
   */
  private static void writeMetadata(
      Path destPathRoot, Map<Path, String> pathsAndContents, ProjectFilesystem filesystem)
      throws IOException {
    for (Map.Entry<Path, String> entry : pathsAndContents.entrySet()) {
      filesystem.writeContentsToPath(entry.getValue(), destPathRoot.resolve(entry.getKey()));
    }
  }

  /**
   * We need to create symlinks for all built abis because we don't yet have a target device. This
   * method determines which abis were actually built for our target by examining the metadata
   * produced by the exopackage build.
   *
   * @return a list of the ABI values that were found in the exopackage data for our app
   */
  private static ImmutableList<String> detectAbis(Path metadataPath, ProjectFilesystem filesystem)
      throws IOException {
    // Each shared-object referenced by the metadata is contained within a folder named with the abi
    // so we extract the set of parent folder names for all the objects.
    return ExopackageInstaller.parseExopackageInfoMetadata(
            metadataPath, MorePaths.emptyOf(metadataPath), filesystem)
        .values().stream()
        .map(path -> path.getParent().getFileName().toString())
        .collect(ImmutableSet.toImmutableSet())
        .asList();
  }
}
