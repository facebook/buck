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

import com.facebook.buck.rules.coercer.SourceWithFlags;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;

public class CxxCompilableEnhancer {

  private CxxCompilableEnhancer() {}

  /**
   * Resolve the map of names to SourcePaths to a map of names to CxxSource objects.
   */
  public static ImmutableMap<String, CxxSource> resolveCxxSources(
      ImmutableMap<String, SourceWithFlags> sources) {

    ImmutableMap.Builder<String, CxxSource> cxxSources = ImmutableMap.builder();

    // For each entry in the input C/C++ source, build a CxxSource object to wrap
    // it's name, input path, and output object file path.
    for (ImmutableMap.Entry<String, SourceWithFlags> ent : sources.entrySet()) {
      String extension = Files.getFileExtension(ent.getKey());
      Optional<CxxSource.Type> type = CxxSource.Type.fromExtension(extension);
      if (!type.isPresent()) {
        throw new HumanReadableException(
            "invalid extension \"%s\": %s",
            extension,
            ent.getKey());
      }
      cxxSources.put(
          ent.getKey(),
          ImmutableCxxSource.of(
              type.get(),
              ent.getValue().getSourcePath(),
              ent.getValue().getFlags()));
    }

    return cxxSources.build();
  }

}
