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

package com.facebook.buck.jvm.java;

/** Expresses what kind of library a given JvmLibrary should compile against. */
public enum CompileAgainstLibraryType {
  /**
   * Compile against the full jar. Avoid this whenever possible as it defeats Buck's incremental
   * build in many cases.
   */
  FULL,
  /** Compile against the ABI jar. */
  ABI,
  /** Compile against the source-only ABI jar, if available. Else the ABI jar. */
  SOURCE_ONLY_ABI,
}
