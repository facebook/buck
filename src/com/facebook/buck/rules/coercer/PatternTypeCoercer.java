/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.nio.file.Path;
import java.util.regex.Pattern;

public class PatternTypeCoercer extends LeafTypeCoercer<Pattern> {
  private final LoadingCache<String, Pattern> patternCache =
      CacheBuilder.newBuilder()
          .weakValues()
          .build(CacheLoader.from(string -> Pattern.compile(string)));

  @Override
  public Class<Pattern> getOutputClass() {
    return Pattern.class;
  }

  @Override
  public Pattern coerce(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    if (object instanceof String) {
      return patternCache.getUnchecked((String) object);
    } else {
      throw CoerceFailedException.simple(object, getOutputClass());
    }
  }
}
