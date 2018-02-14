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

package com.facebook.buck.skylark.packages;

import com.google.common.annotations.VisibleForTesting;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import javax.annotation.Nullable;

/** Provides functions to create package related domain entities. */
public class PackageFactory {

  @VisibleForTesting public static final String PACKAGE_CONTEXT = "$pkg_context";

  /** Get the {@link PackageContext} by looking up in the environment. */
  public static PackageContext getPackageContext(Environment env, FuncallExpression ast)
      throws EvalException {
    @Nullable PackageContext value = (PackageContext) env.lookup(PACKAGE_CONTEXT);
    if (value == null) {
      // if package context is missing, we're not called from a build file. This happens if someone
      // uses native.some_func() in the wrong place.
      throw new EvalException(
          ast.getLocation(),
          "The native module cannot be accessed from here. "
              + "Wrap the function in a macro and call it from a BUCK file");
    }
    return value;
  }
}
