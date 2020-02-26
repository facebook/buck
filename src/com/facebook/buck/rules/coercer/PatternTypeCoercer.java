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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.reflect.TypeToken;
import java.util.regex.Pattern;

/** Coerce a string to {@link java.util.regex.Pattern}. */
public class PatternTypeCoercer extends LeafUnconfiguredOnlyCoercer<Pattern> {
  private final LoadingCache<String, Pattern> patternCache =
      CacheBuilder.newBuilder()
          .weakValues()
          .build(CacheLoader.from(string -> Pattern.compile(string)));

  @Override
  public TypeToken<Pattern> getUnconfiguredType() {
    return TypeToken.of(Pattern.class);
  }

  @Override
  public Pattern coerceToUnconfigured(
      CellNameResolver cellRoots,
      ProjectFilesystem filesystem,
      ForwardRelativePath pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    if (object instanceof String) {
      return patternCache.getUnchecked((String) object);
    } else {
      throw CoerceFailedException.simple(object, getOutputType());
    }
  }
}
