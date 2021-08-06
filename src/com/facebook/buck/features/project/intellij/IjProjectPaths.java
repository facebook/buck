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

package com.facebook.buck.features.project.intellij;

import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.features.project.intellij.model.IjLibrary;
import com.facebook.buck.features.project.intellij.model.IjModule;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.pathformat.PathFormatter;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

public class IjProjectPaths {
  private static final HashFunction hashFunction = Hashing.murmur3_32();
  private final Path projectRootPath;
  private final Path ideaConfigDir;
  private final Path librariesDir;
  private final Path modulesDir;
  private final boolean keepModuleFilesInModuleDirs;

  private static final String MODULE_DIR = "$MODULE_DIR$";
  private static final String PROJECT_DIR = "$PROJECT_DIR$";

  public IjProjectPaths(String projectRoot, boolean keepModuleFilesInModuleDirs) {
    this.projectRootPath = Paths.get(projectRoot);
    this.ideaConfigDir = projectRootPath.resolve(".idea");
    this.librariesDir = ideaConfigDir.resolve("libraries");
    this.modulesDir = ideaConfigDir.resolve("modules");
    this.keepModuleFilesInModuleDirs = keepModuleFilesInModuleDirs;
  }

  public Path getIdeaConfigDir() {
    return ideaConfigDir;
  }

  public Path getLibrariesDir() {
    return librariesDir;
  }

  /** Returns the module path for the target node */
  public static Path getModulePathForNode(
      TargetNode<?> targetNode, ProjectFilesystem projectFilesystem) {
    return projectFilesystem
        .relativize(
            targetNode
                .getFilesystem()
                .resolve(targetNode.getBuildTarget().getCellRelativeBasePath().getPath()))
        .getPath();
  }

  private static String truncateNameWithHash(String name, int length) {
    length = Math.max(length, 0);
    if (name.length() > length) {
      HashCode hashCode = hashFunction.hashString(name, StandardCharsets.UTF_8);
      return name.substring(0, length) + "_" + hashCode;
    }
    return name;
  }

  /** @return path where the XML describing the module to IntelliJ will be written to. */
  public Path getModuleImlFilePath(IjModule module, IjProjectConfig projectConfig) {
    return getModuleDir(module)
        .resolve(
            truncateNameWithHash(
                    module.getName(), projectConfig.getMaxModuleNameLengthBeforeTruncate())
                + ".iml");
  }

  /** @return the directory containing the modules .iml file, $MODULE_DIR$ points to this. */
  public Path getModuleDir(IjModule module) {
    return keepModuleFilesInModuleDirs ? module.getModuleBasePath() : modulesDir;
  }

  /** @return path relative to module path, prefixed with $MODULE_DIR$ */
  public String getModuleQualifiedPath(Path path, IjModule module) {
    String relativePath = PathFormatter.pathWithUnixSeparators(getModuleRelativePath(path, module));
    if (relativePath.isEmpty()) {
      return MODULE_DIR;
    } else {
      return MODULE_DIR + "/" + relativePath;
    }
  }

  /** @return path relative to module dir, for a path relative to the project root */
  public Path getModuleRelativePath(Path path, IjModule module) {
    return MorePaths.relativizeWithDotDotSupport(getModuleDir(module), path);
  }

  /** @return path where the XML describing the IntelliJ library will be written to. */
  public Path getLibraryXmlFilePath(IjLibrary library, IjProjectConfig projectConfig) {
    return getLibrariesDir()
        .resolve(
            truncateNameWithHash(
                    Util.normalizeIntelliJName(library.getName()),
                    projectConfig.getMaxLibraryNameLengthBeforeTruncate())
                + ".xml");
  }

  /**
   * @param path path to folder.
   * @param moduleLocationBasePath path to the location of the .iml file.
   * @return a path, relative to the module .iml file location describing a folder without the
   *     IntelliJ format.
   */
  static String toRelativeString(Path path, Path moduleLocationBasePath) {
    String moduleRelativePath =
        MorePaths.relativizeWithDotDotSupport(moduleLocationBasePath, path).toString();
    if (moduleRelativePath.isEmpty()) {
      return "";
    } else {
      return "/" + PathFormatter.pathWithUnixSeparators(moduleRelativePath);
    }
  }

  /** @return path relative to project root, prefixed with $PROJECT_DIR$ */
  public String getProjectQualifiedPath(Path path) {
    String projectRelativePath = PathFormatter.pathWithUnixSeparators(getProjectRelativePath(path));
    if (projectRelativePath.isEmpty()) {
      return PROJECT_DIR;
    } else {
      return PROJECT_DIR + "/" + projectRelativePath;
    }
  }

  /** @return path relative to project root */
  public Path getProjectRelativePath(Path path) {
    return MorePaths.relativizeWithDotDotSupport(
        projectRootPath.toAbsolutePath(), path.toAbsolutePath());
  }

  /** @return url string for qualified path */
  public static String getUrl(String qualifiedPath) {
    if (qualifiedPath.endsWith(".jar")) {
      return "jar://" + qualifiedPath + "!/";
    } else {
      return "file://" + qualifiedPath;
    }
  }

  /** Paths in Android facet config marked RELATIVE_PATH expect / prefix */
  public static String getAndroidFacetRelativePath(Path path) {
    return "/" + path.toString();
  }
}
