/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.java;

import com.facebook.buck.model.BuildTarget;

import java.nio.file.Path;

public interface JavaPackageFinder {

  /**
   * Given the relative path to a file under the project root, return the Java package with which
   * the file is associated. For .java files, this is generally obvious, as they contain an explicit
   * "package" statement. For other files, such as resources, other heuristics must be used.
   * @param pathRelativeToProjectRoot may be a path to either a file or a directory. If a directory,
   *     then it must end in a slash.
   * @return a path that always ends with a slash, or the empty string, indicating the root
   *     directory.
   */
  Path findJavaPackageFolder(Path pathRelativeToProjectRoot);

  String findJavaPackage(Path pathRelativeToProjectRoot);

  String findJavaPackage(BuildTarget buildTarget);
}
