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

package com.facebook.buck.parser.api;

import java.nio.file.Path;

/**
 * Reads a manifest file, and ensures that it has parsed the files required to utilize all user
 * defined rules in that manifest (based on buck.type)
 */
public interface UserDefinedRuleLoader {
  /**
   * Loads all of the extensions mentioned in a build manifest
   *
   * @param buildFile The full path to the build file
   * @param manifest The manifest from parsing a build file
   */
  void loadExtensionsForUserDefinedRules(Path buildFile, BuildFileManifest manifest);
}
