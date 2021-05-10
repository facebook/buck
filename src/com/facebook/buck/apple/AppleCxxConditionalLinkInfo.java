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

package com.facebook.buck.apple;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/**
 * Stores the information that's needed to determine whether linking can be skipped (for Mach-O).
 */
@BuckStyleValue
@JsonDeserialize(as = ImmutableAppleCxxConditionalLinkInfo.class)
public interface AppleCxxConditionalLinkInfo {
  int CURRENT_VERSION = 1;

  /** The file format version, useful when upgrading and evolving the format. */
  int getVersion();

  /** The Mach-O UUID (LC_UUD) of the executable that was linked. */
  String getExecutableUUID();

  /** A map of all file arguments (as paths), passed to the linker, to the SHA-1 hash */
  ImmutableMap<String, String> getLinkerInputFileToHash();

  /** The SHA-1 hash of the linker argfile. */
  String getLinkerArgfileHash();

  /** The SHA-1 hash of the linker filelist. */
  String getLinkerFilelistHash();

  /** The list of _all_ symbols bound to dependent libraries, including system frameworks. */
  ImmutableList<String> getExecutableBoundSymbols();

  /** The list of all dylib (as paths). */
  ImmutableList<String> getDylibs();

  /**
   * For each dylib listed in {@link #getDylibs()}, records the intersection of the exported symbols
   * from the dylib and {@link #getExecutableBoundSymbols()}.
   */
  ImmutableMap<String, ImmutableList<String>> getCandidateBoundSymbols();

  /** The environment passed to the linker. */
  ImmutableMap<String, String> getLinkerEnvironment();

  /**
   * The linker command prefix (i.e., linker path + additional arguments that come before the
   * argfile).
   */
  ImmutableList<String> getLinkerCommandPrefix();

  ImmutableList<String> getFocusedTargets();

  /** Factory method for AppleCxxRelinkInfo */
  static AppleCxxConditionalLinkInfo of(
      String executableUUID,
      ImmutableMap<String, String> inputFileToHashMap,
      String argfileHash,
      String filelistHash,
      ImmutableList<String> executableBoundSymbols,
      ImmutableList<String> dylibs,
      ImmutableMap<String, ImmutableList<String>> candidateBoundSymbols,
      ImmutableMap<String, String> linkerEnvironment,
      ImmutableList<String> linkerCommandPrefix,
      ImmutableList<String> focusedTargets) {
    return ImmutableAppleCxxConditionalLinkInfo.ofImpl(
        CURRENT_VERSION,
        executableUUID,
        inputFileToHashMap,
        argfileHash,
        filelistHash,
        executableBoundSymbols,
        dylibs,
        candidateBoundSymbols,
        linkerEnvironment,
        linkerCommandPrefix,
        focusedTargets);
  }
}
