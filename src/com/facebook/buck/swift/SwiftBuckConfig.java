/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.swift;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A Swift-specific "view" of BuckConfig. */
public class SwiftBuckConfig implements ConfigView<BuckConfig> {
  private static final String SECTION_NAME = "swift";
  private static final String SWIFT_POSTPROCESSING_MODULE_TO_HEADER_MAPPINGS_SECTION_NAME =
      "swift_postprocessing_module_to_header_mappings";
  public static final String COMPILER_FLAGS_NAME = "compiler_flags";
  public static final String VERSION_NAME = "version";
  public static final String PROJECT_WMO = "project_wmo";
  public static final String PROJECT_EMBED_RUNTIME = "project_embed_runtime";
  public static final String COPY_STDLIB_TO_FRAMEWORKS = "copy_stdlib_to_frameworks";
  public static final String EMIT_SWIFTDOCS = "emit_swiftdocs";
  public static final String SLICE_APP_PACKAGE_RUNTIME = "slice_app_package_runtime";
  public static final String SLICE_APP_BUNDLE_RUNTIME = "slice_app_bundle_runtime";
  public static final String PREFIX_SERIALIZED_DEBUGGING_OPTIONS =
      "prefix_serialized_debugging_options";
  public static final String INCREMENTAL_BUILDS = "incremental";
  public static final String INCREMENTAL_IMPORTS = "incremental_imports";
  public static final String POSTPROCESS_GENERATED_HEADER_FOR_NON_MODULES_COMPATIBILITY =
      "postprocess_generated_header_for_non_modules_compatibility";
  public static final String FORCE_DEBUG_INFO_AT_LINK_TIME = "force_debug_info_at_link_time";
  private final BuckConfig delegate;

  @Override
  public BuckConfig getDelegate() {
    return delegate;
  }

  public SwiftBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  // Reflection-based factory for ConfigView
  public static SwiftBuckConfig of(BuckConfig delegate) {
    return new SwiftBuckConfig(delegate);
  }

  private Optional<Iterable<String>> getFlags(String field) {
    Optional<String> value = delegate.getValue(SECTION_NAME, field);
    return value.map(input -> Splitter.on(" ").split(input.trim()));
  }

  public Optional<Iterable<String>> getCompilerFlags() {
    return getFlags(COMPILER_FLAGS_NAME);
  }

  public Optional<String> getVersion() {
    return delegate.getValue(SECTION_NAME, VERSION_NAME);
  }

  /**
   * If enabled, automatically emebds the Swift runtime if a relevant target depends on any
   * libraries that use Swift.
   */
  public boolean getProjectEmbedRuntime() {
    return delegate.getBooleanValue(SECTION_NAME, PROJECT_EMBED_RUNTIME, true);
  }

  /** If enabled, turns on Whole Module Optimization for any targets that contain Swift. */
  public boolean getProjectWMO() {
    return delegate.getBooleanValue(SECTION_NAME, PROJECT_WMO, false);
  }

  /**
   * If enabled, swift-stdlib-tool will be run on .framework bundles, copying the Swift standard
   * library into them. This is usually not what you want - it will lead to multiple redundant
   * copies of the libraries being embedded in both the app bundle and any descendant framework
   * bundles. Even if Swift is only used in a framework, and not in the app binary, Buck and
   * swift-stdlib-tool will handle that correctly and embed the libraries.
   */
  public boolean getCopyStdlibToFrameworks() {
    return delegate.getBooleanValue(SECTION_NAME, COPY_STDLIB_TO_FRAMEWORKS, false);
  }

  /**
   * If enabled, a .swiftdoc file will be generated along with the .swiftmodule file. This is
   * necessary for Xcode to display the documentation for the libraries prebuilt with buck.
   */
  public boolean getEmitSwiftdocs() {
    return delegate.getBooleanValue(SECTION_NAME, EMIT_SWIFTDOCS, false);
  }

  /**
   * If true, after running swift-stdlib-tool to copy the Swift runtime into the "SwiftSupport"
   * directory in the .ipa, unused architectures will be removed using lipo. If false, the dylibs
   * will be left untouched.
   */
  public boolean getSliceAppPackageSwiftRuntime() {
    return delegate.getBooleanValue(SECTION_NAME, SLICE_APP_PACKAGE_RUNTIME, false);
  }

  /**
   * If true, after running swift-stdlib-tool to copy the Swift runtime into the Frameworks
   * directory, unused architectures will be removed using lipo. If false, the dylibs will be left
   * untouched.
   */
  public boolean getSliceAppBundleSwiftRuntime() {
    return delegate.getBooleanValue(SECTION_NAME, SLICE_APP_BUNDLE_RUNTIME, false);
  }

  /**
   * If true, use the -prefix-serialized-debugging-options flag to remap paths in the serialized
   * Swift debug info. This makes it possible to cache Swift compiler output.
   */
  public boolean getPrefixSerializedDebuggingOptions() {
    return delegate.getBooleanValue(SECTION_NAME, PREFIX_SERIALIZED_DEBUGGING_OPTIONS, false);
  }

  /**
   * If true, BUCK will pass "-incremental" to the Swift compiler. It will enable intra-module
   * incremental builds. To allow that, BUCK will emit OutputFileMap.json file and pass it to the
   * Swift's driver.
   */
  public boolean getIncrementalBuild() {
    return delegate.getBooleanValue(SECTION_NAME, INCREMENTAL_BUILDS, false);
  }

  /**
   * If true, BUCK will pass "-enable-incremental-imports" along with "-incremental". It will enable
   * cross module incremental imports which will allow Swift's driver to do a finer-grained
   * depedency tracking and skip some jobs if they do not depend on changes.
   */
  public boolean getIncrementalImports() {
    return delegate.getBooleanValue(SECTION_NAME, INCREMENTAL_IMPORTS, false);
  }

  /**
   * If true, BUCK will postprocess the generated Objective-C header to make it compatible with
   * Objective-C code that does not use the -fmodules flag for compilation.
   */
  public boolean getPostprocessGeneratedHeaderForNonModulesCompatibility() {
    return delegate.getBooleanValue(
        SECTION_NAME, POSTPROCESS_GENERATED_HEADER_FOR_NON_MODULES_COMPATIBILITY, false);
  }

  /**
   * If true, Buck will add linker commands that reference the precompiled module and modulemap
   * dependencies of a binary to force the debug dependencies to be materialized at link time.
   */
  public boolean getForceDebugInfoAtLinkTime() {
    return delegate.getBooleanValue(SECTION_NAME, FORCE_DEBUG_INFO_AT_LINK_TIME, false);
  }

  /**
   * Maps a system model name to list of header imports that should be substituted for it. Headers
   * are separated by the pipe character, chosen because that character is very unlikely to appear
   * in a system header path.
   */
  public Map<String, List<String>> getGeneratedHeaderPostprocessingSystemModuleToHeadersMap() {
    return delegate
        .getSection(SWIFT_POSTPROCESSING_MODULE_TO_HEADER_MAPPINGS_SECTION_NAME)
        .map(
            section -> {
              ImmutableMap.Builder<String, List<String>> builder = ImmutableMap.builder();
              section.forEach(
                  (moduleName, headers) -> {
                    builder.put(moduleName, ImmutableList.copyOf(headers.split("\\|")));
                  });
              return builder.build();
            })
        .orElseGet(() -> ImmutableMap.of());
  }
}
