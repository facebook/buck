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

package com.facebook.buck.skylark.function;

import com.facebook.buck.skylark.function.packages.Provider;
import com.facebook.buck.skylark.function.packages.StarlarkProvider;
import javax.annotation.Nullable;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.StarlarkIterable;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkThread;

/**
 * Implements a version of the `provider()` function for use in Skylark build/extension files. At
 * this time it does not register providers centrally, and is mostly used as a more efficient
 * `struct()` factory.
 */
public class SkylarkProviderFunction implements SkylarkProviderFunctionApi {

  @Override
  public Provider provider(String doc, Object fields, StarlarkThread thread) throws EvalException {
    @Nullable StarlarkIterable<String> fieldNames;
    if (fields instanceof StarlarkList<?>) {
      Sequence<String> list =
          Sequence.cast(
              fields,
              String.class,
              "Expected list of strings or dictionary of string -> string for 'fields'");
      fieldNames = list;
    } else if (fields instanceof Dict) {
      Dict<String, String> dict =
          Dict.cast(
              fields,
              String.class,
              String.class,
              "Expected list of strings or dictionary of string -> string for 'fields'");
      fieldNames = dict;
    } else {
      throw new EvalException("fields attribute must be either list or dict.");
    }
    return StarlarkProvider.createUnexportedSchemaful(fieldNames, thread.getCallerLocation());
  }
}
