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

package com.facebook.buck.skylark.function;

import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.SkylarkProvider;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.syntax.SkylarkType;
import java.util.Map;
import javax.annotation.Nullable;

/** Provides APIs for creating build rules. */
public class SkylarkRuleFunctions implements SkylarkRuleFunctionsApi {

  @Override
  public Provider provider(String doc, Object fields, Location location) throws EvalException {
    @Nullable Iterable<String> fieldNames = null;
    if (fields instanceof SkylarkList<?>) {
      @SuppressWarnings("unchecked")
      SkylarkList<String> list =
          SkylarkType.cast(
              fields,
              SkylarkList.class,
              String.class,
              location,
              "Expected list of strings or dictionary of string -> string for 'fields'");
      fieldNames = list;
    } else if (fields instanceof SkylarkDict) {
      Map<String, String> dict =
          SkylarkType.castMap(
              fields,
              String.class,
              String.class,
              "Expected list of strings or dictionary of string -> string for 'fields'");
      fieldNames = dict.keySet();
    } else {
      throw new EvalException(location, "fields attribute must be either list or dict.");
    }
    return SkylarkProvider.createUnexportedSchemaful(fieldNames, location);
  }
}
