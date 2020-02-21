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

package com.facebook.buck.skylark.parser;

import com.facebook.buck.core.starlark.compatible.BuckSkylarkTypes;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.StructProvider;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import com.google.devtools.build.lib.skylarkinterface.SkylarkGlobalLibrary;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.SkylarkDict;

/** Simple struct implementation that makes values immutable */
@SkylarkGlobalLibrary()
public class FrozenStructProvider {

  public static final FrozenStructProvider INSTANCE = new FrozenStructProvider();

  /**
   * A `struct` implementation that freezes all values that are passed in before passing them to the
   * skylark native `struct()`
   */
  @SkylarkCallable(name = "struct", useLocation = true, extraKeywords = @Param(name = "kwargs"))
  public Object struct(SkylarkDict<String, Object> kwargs, Location loc) throws EvalException {
    Object frozenValues = BuckSkylarkTypes.asDeepImmutable(kwargs);
    if (!(frozenValues instanceof SkylarkDict)) {
      throw new EvalException(loc, "kwargs passed were not valid");
    }
    return StructProvider.STRUCT.createStruct((SkylarkDict<?, ?>) frozenValues, loc);
  }
}
