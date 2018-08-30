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
package com.facebook.buck.features.project.intellij;

import com.facebook.buck.features.project.intellij.model.IjModule;
import com.facebook.buck.io.file.MorePaths;
import java.nio.file.Path;
import java.nio.file.Paths;

public class IjProjectPaths {
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

  /** @return path where the XML describing the module to IntelliJ will be written to. */
  public Path getModuleImlFilePath(IjModule module) {
    return getModuleDir(module).resolve(module.getName() + ".iml");
  }

  /** @return the directory containing the modules .iml file, $MODULE_DIR$ points to this. */
  public Path getModuleDir(IjModule module) {
    return keepModuleFilesInModuleDirs ? module.getModuleBasePath() : modulesDir;
  }

  /** @return path relative to module path, prefixed with $MODULE_DIR$ */
  public String getModuleQualifiedPath(Path path, IjModule module) {
    String relativePath = MorePaths.pathWithUnixSeparators(getModuleRelativePath(path, module));
    if (relativePath.isEmpty()) {
      return MODULE_DIR;
    } else {
      return MODULE_DIR + "/" + relativePath;
    }
  }

  /** @return path relative to module dir, for a path relative to the project root */
  public Path getModuleRelativePath(Path path, IjModule module) {
    return getModuleDir(module).relativize(path);
  }

  /** @return path relative to project root, prefixed with $PROJECT_DIR$ */
  public String getProjectQualifiedPath(Path path) {
    String projectRelativePath = MorePaths.pathWithUnixSeparators(getProjectRelativePath(path));
    if (projectRelativePath.isEmpty()) {
      return PROJECT_DIR;
    } else {
      return PROJECT_DIR + "/" + projectRelativePath;
    }
  }

  /** @return path relative to project root */
  public Path getProjectRelativePath(Path path) {
    return projectRootPath.toAbsolutePath().relativize(path.toAbsolutePath());
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
