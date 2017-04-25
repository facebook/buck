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
package com.facebook.buck.ide.intellij;

import com.facebook.buck.io.MorePaths;
import java.nio.file.Path;
import java.nio.file.Paths;

public class IjProjectPaths {

  public static final Path IDEA_CONFIG_DIR = Paths.get(".idea");
  public static final Path LIBRARIES_DIR = IDEA_CONFIG_DIR.resolve("libraries");

  private IjProjectPaths() {}

  /**
   * @param path path to folder.
   * @param moduleLocationBasePath path to the location of the .iml file.
   * @return a path, relative to the module .iml file location describing a folder in IntelliJ
   *     format.
   */
  static String toModuleDirRelativeString(Path path, Path moduleLocationBasePath) {
    String moduleRelativePath = moduleLocationBasePath.relativize(path).toString();
    if (moduleRelativePath.isEmpty()) {
      return "file://$MODULE_DIR$";
    } else {
      return "file://$MODULE_DIR$/" + moduleRelativePath;
    }
  }

  static String toProjectDirRelativeString(Path projectRelativePath) {
    String path = projectRelativePath.toString();
    if (path.isEmpty()) {
      return "file://$PROJECT_DIR$";
    } else {
      return "file://$PROJECT_DIR$/" + MorePaths.pathWithUnixSeparators(path);
    }
  }
}
