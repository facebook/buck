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

package com.facebook.buck.parser;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;

/**
 * Create caches for {@link com.google.devtools.build.lib.cmdline.Label} objects in the Skylark
 * Parser
 */
public class LabelCache {
  private LabelCache() {}

  /** Create an empty cache that converts strings of absolute labels to {@link Label}s on demand */
  public static LoadingCache<String, Label> newLabelCache() {
    return CacheBuilder.newBuilder()
        .build(
            CacheLoader.from(
                (labelString) -> {
                  try {
                    return Label.parseAbsolute(labelString, false, ImmutableMap.of());
                  } catch (LabelSyntaxException e) {
                    throw new HumanReadableException(e, "%s is not a valid label", labelString);
                  }
                }));
  }
}
