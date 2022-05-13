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
  public static final String USE_ARGFILE = "use_argfile";
  public static final String PROJECT_WMO = "project_wmo";
  public static final String PROJECT_EMBED_RUNTIME = "project_embed_runtime";
  public static final String PROJECT_ADD_AST_PATHS = "project_add_ast_paths";
  public static final String COPY_STDLIB_TO_FRAMEWORKS = "copy_stdlib_to_frameworks";
  public static final String EMIT_CLANG_MODULE_BREADCRUMBS = "emit_clang_module_breadcrumbs";
  public static final String EMIT_SWIFTDOCS = "emit_swiftdocs";
  public static final String SLICE_APP_PACKAGE_RUNTIME = "slice_app_package_runtime";
  public static final String SLICE_APP_BUNDLE_RUNTIME = "slice_app_bundle_runtime";
  public static final String INPUT_BASED_COMPILE_ENABLED = "input_based_compile_enabled";
  public static final String TRANSFORM_ERRORS_TO_ABSOLUTE_PATHS =
      "transform_errors_to_absolute_paths";
  public static final String USE_DEBUG_PREFIX_MAP = "use_debug_prefix_map";
  public static final String PREFIX_SERIALIZED_DEBUGGING_OPTIONS =
      "prefix_serialized_debugging_options";
  public static final String ADD_XCTEST_IMPORT_PATHS = "add_xctest_import_paths";
  public static final String ALLOW_PRIVATE_SWIFT_DEPS = "allow_private_swift_deps";
  public static final String INCREMENTAL_BUILDS = "incremental";
  public static final String INCREMENTAL_IMPORTS = "incremental_imports";
  public static final String POSTPROCESS_GENERATED_HEADER_FOR_NON_MODULES_COMPATIBILITY =
      "postprocess_generated_header_for_non_modules_compatibility";
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

  public boolean getUseArgfile() {
    // See https://github.com/apple/swift/pull/15853
    return delegate.getBooleanValue(SECTION_NAME, USE_ARGFILE, false);
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
   * If enabled, AST paths to the .swiftmodules will be added as part of the linker invocation. This
   * is necessary for lldb to be able to debug statically linked Swift libraries.
   */
  public boolean getProjectAddASTPaths() {
    return delegate.getBooleanValue(SECTION_NAME, PROJECT_ADD_AST_PATHS, false);
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
   * If enabled Swift will emit paths to the dependent pcm files of Swift modules in the debug info.
   * This is used as a fallback path in LLDB to get type information when source is unavailable. The
   * corresponding compiler flag is `-no-clang-module-breadcrumbs`. Enabling this will mark the
   * output as uncacheable.
   */
  public boolean getEmitClangModuleBreadcrumbs() {
    return delegate.getBooleanValue(SECTION_NAME, EMIT_CLANG_MODULE_BREADCRUMBS, false);
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
   * Defines whether the Swift compile rule will be treated as an input-based rule. NB: This is a
   * temporary, internal configuration for the purposes of rolling back if needed.
   */
  public boolean getInputBasedCompileEnabled() {
    // TODO(#67788442): Remove config option once behavior correctness has been verified
    return delegate.getBooleanValue(SECTION_NAME, INPUT_BASED_COMPILE_ENABLED, true);
  }

  /**
   * If true, error message paths will be transformed from relative paths to absolute paths. This is
   * required for clicking on the error in Xcode to correctly jump to its location. In addition to
   * setting this config, the --report-absolute-paths option must be used.
   */
  public boolean getTransformErrorsToAbsolutePaths() {
    return delegate.getBooleanValue(SECTION_NAME, TRANSFORM_ERRORS_TO_ABSOLUTE_PATHS, false);
  }

  /**
   * If true, use the -debug-prefix-map flag to remap paths in debug info to use custom prefixes.
   * This is analogous to Cxx use of PrefixMapDebugPathSanitizer.
   */
  public boolean getUseDebugPrefixMap() {
    return delegate.getBooleanValue(SECTION_NAME, USE_DEBUG_PREFIX_MAP, false);
  }

  /**
   * If true, use the -prefix-serialized-debugging-options flag to remap paths in the serialized
   * Swift debug info. This makes it possible to cache Swift compiler output.
   */
  public boolean getPrefixSerializedDebuggingOptions() {
    return delegate.getBooleanValue(SECTION_NAME, PREFIX_SERIALIZED_DEBUGGING_OPTIONS, false);
  }

  /**
   * If true, add -I$PLATFORM_DIR/Developer/usr/lib so that libXCTestSwiftSupport.dylib can be found
   * at compile time.
   */
  public boolean getAddXctestImportPaths() {
    return delegate.getBooleanValue(SECTION_NAME, ADD_XCTEST_IMPORT_PATHS, true);
  }

  /**
   * If true, both "exported_deps" and "deps" will be used as preprocessor inputs which will allow
   * apple_libraries with Swift to contain private deps, not exported to consumers. Otherwise, only
   * targets from "exported_deps" will be passed to the Swift compiler.
   */
  public boolean getAllowPrivateSwiftDeps() {
    return delegate.getBooleanValue(SECTION_NAME, ALLOW_PRIVATE_SWIFT_DEPS, false);
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
