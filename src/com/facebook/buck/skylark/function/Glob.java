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

package com.facebook.buck.skylark.function;

import com.facebook.buck.skylark.packages.PackageContext;
import com.facebook.buck.skylark.packages.PackageFactory;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkSignature;
import com.google.devtools.build.lib.syntax.BuiltinFunction;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.GlobList;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.syntax.SkylarkSignatureProcessor;
import com.google.devtools.build.lib.syntax.Type;
import java.io.FileNotFoundException;
import java.io.IOException;

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
public class Glob {

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
        name = "include",
        type = SkylarkList.class,
        generic1 = String.class,
        doc = "a list of strings specifying patterns of files to include."
      ),
      @Param(
        name = "exclude",
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
        defaultValue = "True",
        positional = false,
        named = true,
        doc = "True indicates directories should not be matched."
      ),
    },
    documented = false,
    useAst = true,
    useEnvironment = true
  )
  private static final BuiltinFunction glob =
      new BuiltinFunction(GLOB_FUNCTION_NAME) {
        @SuppressWarnings("unused")
        public SkylarkList<String> invoke(
            SkylarkList<String> include,
            SkylarkList<String> exclude,
            Boolean excludeDirectories,
            FuncallExpression ast,
            Environment env)
            throws EvalException, IOException {
          PackageContext packageContext = PackageFactory.getPackageContext(env, ast);
          try {
            return SkylarkList.MutableList.copyOf(
                env,
                GlobList.captureResults(
                    include,
                    exclude,
                    packageContext
                        .getGlobber()
                        .run(
                            Type.STRING_LIST.convert(include, "'glob' include"),
                            Type.STRING_LIST.convert(exclude, "'glob' exclude"),
                            excludeDirectories)
                        .stream()
                        .sorted()
                        .collect(ImmutableList.toImmutableList())));
          } catch (FileNotFoundException fnfe) {
            throw new EvalException(ast.getLocation(), "Cannot find " + fnfe.getMessage());
          }
        }
      };

  /**
   * Creates a built-in {@code glob} function that can resolve glob patterns under {@code basePath}.
   */
  public static BuiltinFunction create() {
    return glob;
  }

  static {
    SkylarkSignatureProcessor.configureSkylarkFunctions(Glob.class);
  }
}
