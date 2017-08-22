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

package com.facebook.buck.skylark;

import com.facebook.buck.util.MoreCollectors;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkSignature;
import com.google.devtools.build.lib.syntax.BuiltinFunction;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.GlobList;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.syntax.SkylarkSignatureProcessor;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.UnixGlob;
import java.io.IOException;
import java.util.List;

/**
 * A factory for {@code glob} built-in function available in build files.
 *
 * <p>It's syntax is
 *
 * <pre>
 *   glob(includes=[...], excludes=[...], exclude_directories=True|False|None'])
 * </pre>
 *
 * . For example to resolve all {@code cpp} files following invocation can be used:
 *
 * <pre>
 *   glob(['*.cpp'])
 * </pre>
 *
 * . To make it available in the environment use:
 *
 * <pre>
 *   env.setup("glob", Glob.create(basePath));
 * </pre>
 */
class Glob {

  private static final String GLOB_FUNCTION_NAME = "glob";

  /** Prevent callers from instantiating this class. */
  private Glob() {}

  @SkylarkSignature(
    name = "glob",
    objectType = Object.class,
    returnType = SkylarkList.class,
    doc = "Returns a list of files that match glob search pattern.",
    parameters = {
      @Param(
        name = "includes",
        type = SkylarkList.class,
        generic1 = String.class,
        doc = "a list of strings specifying patterns of files to include."
      ),
      @Param(
        name = "excludes",
        type = SkylarkList.class,
        generic1 = String.class,
        defaultValue = "[]",
        positional = false,
        named = true,
        doc = "a list of strings specifying patterns of files to exclude."
      ),
      @Param(
        name = "exclude_directories",
        type = Boolean.class,
        defaultValue = "False",
        positional = false,
        named = true,
        doc = "True indicates directories should not be matched."
      ),
    },
    documented = false,
    useEnvironment = true
  )
  private static final BuiltinFunction.Factory glob =
      new BuiltinFunction.Factory(GLOB_FUNCTION_NAME) {
        @SuppressWarnings("unused")
        public BuiltinFunction create(Path basePath) {
          return new BuiltinFunction(GLOB_FUNCTION_NAME, this) {
            public SkylarkList<String> invoke(
                SkylarkList<String> includes,
                SkylarkList<String> excludes,
                Boolean excludeDirectories,
                Environment env)
                throws EvalException, InterruptedException, IOException {

              ImmutableSet<String> includePaths =
                  resolvePathsMatchingGlobPatterns(
                      Type.STRING_LIST.convert(includes, "'glob' includes"),
                      basePath,
                      excludeDirectories);
              ImmutableSet<String> excludedPaths =
                  resolvePathsMatchingGlobPatterns(
                      Type.STRING_LIST.convert(excludes, "'glob' excludes"),
                      basePath,
                      excludeDirectories);
              return SkylarkList.MutableList.copyOf(
                  env,
                  GlobList.captureResults(
                      includes,
                      excludes,
                      Sets.difference(includePaths, excludedPaths)
                          .stream()
                          .sorted()
                          .collect(MoreCollectors.toImmutableList())));
            }
          };
        }
      };

  /**
   * Resolves provided list of glob patterns into a set of paths.
   *
   * @param patterns The glob patterns to resolve.
   * @param basePath The base path used when resolving glob patterns.
   * @param excludeDirectories Flag indicating whether directories should be excluded from result.
   * @return The set of paths corresponding to requested patterns.
   */
  private static ImmutableSet<String> resolvePathsMatchingGlobPatterns(
      List<String> patterns, Path basePath, Boolean excludeDirectories) throws IOException {
    UnixGlob.Builder includeGlobBuilder = UnixGlob.forPath(basePath).addPatterns(patterns);
    if (excludeDirectories != null) {
      includeGlobBuilder.setExcludeDirectories(excludeDirectories);
    }
    return includeGlobBuilder
        .glob()
        .stream()
        .map(includePath -> includePath.relativeTo(basePath).getPathString())
        .collect(MoreCollectors.toImmutableSet());
  }

  /**
   * Creates a built-in {@code glob} function that can resolve glob patterns under {@code basePath}.
   *
   * @param basePath The base path relative to which paths matching glob patterns will be resolved.
   */
  public static BuiltinFunction create(Path basePath) {
    return glob.apply(basePath);
  }

  static {
    SkylarkSignatureProcessor.configureSkylarkFunctions(Glob.class);
  }
}
