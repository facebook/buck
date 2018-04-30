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

import com.facebook.buck.android.exopackage.ExopackageInfo.DexInfo;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.ImmutableMultimap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * An ExoHelper which provides a mapping of host source path to device install path for modular dex
 * files when exopackage-for-modules is enabled
 */
public class ModuleExoHelper {
  @VisibleForTesting public static final Path MODULAR_DEX_DIR = Paths.get("modular-dex");
  private final SourcePathResolver pathResolver;
  private final ProjectFilesystem projectFilesystem;
  private final List<ExopackageInfo.DexInfo> dexInfoForModules;

  /**
   * @param pathResolver a resolver for finding the output SourcePaths on disk
   * @param projectFilesystem the filesystem owning buck-out
   * @param dexInfoForModules a list of metadata/dex-output-dirs for the modules that we want to
   *     exo-install
   */
  ModuleExoHelper(
      SourcePathResolver pathResolver,
      ProjectFilesystem projectFilesystem,
      List<DexInfo> dexInfoForModules) {
    this.pathResolver = pathResolver;
    this.projectFilesystem = projectFilesystem;
    this.dexInfoForModules = dexInfoForModules;
  }

  /**
   * @return the list of modular dex files which are installable for this build The returned map
   *     contains entries of the form destination_file_path => local_src_file_path
   */
  public ImmutableMap<Path, Path> getFilesToInstall() throws Exception {
    return ExopackageUtil.applyFilenameFormat(
        getRequiredDexFiles(), MODULAR_DEX_DIR, "module-%s.dex.jar");
  }

  /**
   * @return metadata contents for each module, containing hashes and canary class names along with
   *     a top level metadata file describing the full set of modular jars. The per-module metadata
   *     files contain comment lines beginning with a '.' and entry lines which each describe a jar
   *     belonging to the module with the format: "file_path file_hash"
   *     <p>The top level metadata file has one line per jar with the following format: "file_name
   *     module_name" and provides a top-level listing of all jars included in the build along with
   *     a mapping back to the module name where they came from
   */
  public ImmutableMap<Path, String> getMetadataToInstall() throws Exception {
    Builder<Path, String> builder = ImmutableMap.builder();
    for (DexInfo info : dexInfoForModules) {
      Path metadataFile = pathResolver.getAbsolutePath(info.getMetadata());
      if (!Files.exists(metadataFile)) {
        continue;
      }
      Path dirname = metadataFile.getParent().getFileName();
      String onDeviceName = String.format("%s.metadata", dirname);
      String metadataContents = new String(Files.readAllBytes(metadataFile));
      builder.put(MODULAR_DEX_DIR.resolve(onDeviceName), metadataContents);
    }
    // Top level metadata.txt containing the list of jars
    String fileListing =
        getFilesToInstall()
            .entrySet()
            .stream()
            .map(
                entry -> {
                  String moduleName = entry.getValue().getParent().getFileName().toString();
                  String fileName = entry.getKey().getFileName().toString();
                  return String.format("%s %s\n", fileName, moduleName);
                })
            .collect(Collectors.joining());
    builder.put(MODULAR_DEX_DIR.resolve("metadata.txt"), fileListing);
    return builder.build();
  }

  /**
   * @return a file_hash => local_file_path mapping
   * @throws IOException if an error occurred while parsing the metadata files which describe the
   *     files to be installed
   */
  private ImmutableMap<String, Path> getRequiredDexFiles() throws IOException {
    ImmutableMap.Builder<String, Path> builder = ImmutableMap.builder();
    for (DexInfo dexInfo : dexInfoForModules) {
      Path metadataFile = pathResolver.getAbsolutePath(dexInfo.getMetadata());
      if (!Files.exists(metadataFile)) {
        continue;
      }
      ImmutableMultimap<String, Path> multimap =
          ExopackageInstaller.parseExopackageInfoMetadata(
              metadataFile,
              pathResolver.getAbsolutePath(dexInfo.getDirectory()),
              projectFilesystem);
      for (Map.Entry<String, Path> entry : multimap.entries()) {
        builder.put(entry);
      }
    }
    return builder.build();
  }
}
