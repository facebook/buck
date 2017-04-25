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

package com.facebook.buck.rules.macros;

import com.facebook.buck.model.Either;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import java.util.List;

public class StringWithMacrosUtils {

  private StringWithMacrosUtils() {}

  /** @return a {@link StringWithMacros} object built with the given format strings and macros. */
  public static StringWithMacros format(String format, Macro... macros) {
    ImmutableList.Builder<Either<String, Macro>> partsBuilder = ImmutableList.builder();

    List<String> stringParts = Splitter.on("%s").splitToList(format);
    Preconditions.checkState(stringParts.size() == macros.length + 1);

    if (!stringParts.get(0).isEmpty()) {
      partsBuilder.add(Either.ofLeft(stringParts.get(0)));
    }

    for (int i = 0; i < macros.length; i++) {
      partsBuilder.add(Either.ofRight(macros[i]));
      if (!stringParts.get(i + 1).isEmpty()) {
        partsBuilder.add(Either.ofLeft(stringParts.get(i + 1)));
      }
    }

    return StringWithMacros.of(partsBuilder.build());
  }
}
