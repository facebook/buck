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

package com.facebook.buck.features.apple.project;

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import org.immutables.value.Value;

/** Options for how {@link ProjectGenerator} generates Xcode projects. */
@Value.Immutable
@BuckStyleImmutable
interface AbstractProjectGeneratorOptions {
  /** Use short BuildTarget name instead of full name for targets */
  @Value.Default
  default boolean shouldUseShortNamesForTargets() {
    return false;
  }

  /** Put targets into groups reflecting directory structure of their BUCK files */
  @Value.Default
  default boolean shouldCreateDirectoryStructure() {
    return false;
  }

  /** Create schemes for each project's contained build and test targets. */
  @Value.Default
  default boolean shouldGenerateProjectSchemes() {
    return false;
  }

  /** Generate read-only project files */
  @Value.Default
  default boolean shouldGenerateReadOnlyFiles() {
    return false;
  }

  /** Include tests that test root targets in the scheme */
  @Value.Default
  default boolean shouldIncludeTests() {
    return false;
  }

  /** Include dependencies tests in the scheme */
  @Value.Default
  default boolean shouldIncludeDependenciesTests() {
    return false;
  }

  /** Use header maps as header search paths */
  @Value.Default
  default boolean shouldUseHeaderMaps() {
    return true;
  }

  /** Use absolute header map paths in .xcconfig files */
  @Value.Default
  default boolean shouldUseAbsoluteHeaderMapPaths() {
    return false;
  }

  /**
   * Generate one header map containing all the headers it's using and reference only this header
   * map in the header search paths.
   */
  @Value.Default
  default boolean shouldMergeHeaderMaps() {
    return false;
  }

  /** Generates only headers symlink trees. */
  @Value.Default
  default boolean shouldGenerateHeaderSymlinkTreesOnly() {
    return false;
  }

  /** Generate an umbrella header for modular targets without one for use in a modulemap */
  @Value.Default
  default boolean shouldGenerateMissingUmbrellaHeader() {
    return false;
  }

  /** Add linker flags to OTHER_LDFLAGS to force load of libraries with link_whole = true */
  @Value.Default
  default boolean shouldForceLoadLinkWholeLibraries() {
    return false;
  }

  /** Add linker flags to OTHER_LDFLAGS for libraries rather than to the library build phase */
  @Value.Default
  default boolean shouldAddLinkedLibrariesAsFlags() {
    return false;
  }
}
