/*
 * Copyright 2019-present Facebook, Inc.
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

package com.facebook.buck.core.parser.buildtargetpattern;

/** Defines symbols which are a part of the build target and build target pattern formats */
public final class BuildTargetLanguageConstants {

  private BuildTargetLanguageConstants() {}

  /** Delimiter that splits cell name and the rest of the pattern */
  public static final String ROOT_SYMBOL = "//";
  /** Delimiter that splits path to a package root in the pattern */
  public static final char PATH_SYMBOL = '/';
  /** Delimiter that splits target name and the rest of the pattern */
  public static final char TARGET_SYMBOL = ':';
  /** Symbol that represents recursive pattern */
  public static final String RECURSIVE_SYMBOL = "...";
  /** Symbol that represents flavor part of a build target */
  public static final char FLAVOR_SYMBOL = '#';
  /** Symbol that delimits flavors specified after FLAVOR_SYMBOL */
  public static final char FLAVOR_DELIMITER = ',';
}
