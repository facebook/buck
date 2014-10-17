/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;

/**
 * Utility class for manipulating C/C++/etc. preprocessor flags specified
 * in {@link CxxConstructorArg}.
 */
public class CxxPreprocessorFlags {
  // Utility class; do not instantiate.
  private CxxPreprocessorFlags() { }

  /**
   * Converts from CxxConstructorArg.preprocessorFlags and
   * CxxConstructorArg.langPreprocessorFlags to the multimap
   * of (source type: [flag, flag2, ...]) pairs.
   *
   * If the list version of the arg is present, fills every source
   * type with the list's value.
   *
   * Otherwise, converts the map version of the arg to a multimap.
   */
  public static ImmutableMultimap<CxxSource.Type, String> fromArgs(
      Optional<ImmutableList<String>> defaultFlags,
      Optional<ImmutableMap<CxxSource.Type, ImmutableList<String>>> perLanguageFlags
  ) {
    Preconditions.checkNotNull(defaultFlags);
    Preconditions.checkNotNull(perLanguageFlags);

    ImmutableMultimap.Builder<CxxSource.Type, String> result = ImmutableMultimap.builder();
    if (defaultFlags.isPresent()) {
      for (CxxSource.Type type : CxxSource.Type.values()) {
        result.putAll(type, defaultFlags.get());
      }
    }
    if (perLanguageFlags.isPresent()) {
      for (ImmutableMap.Entry<CxxSource.Type, ImmutableList<String>> entry :
             perLanguageFlags.get().entrySet()) {
        result.putAll(entry.getKey(), entry.getValue());
      }
    }
    return result.build();
  }
}
