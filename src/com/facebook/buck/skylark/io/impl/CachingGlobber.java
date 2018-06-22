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

package com.facebook.buck.skylark.io.impl;

import com.facebook.buck.skylark.io.GlobSpec;
import com.facebook.buck.skylark.io.Globber;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * {@link Globber} implementation that caches expanded paths from previous invocations and reuses
 * them in case the glob with same {@link GlobSpec} is requested again.
 *
 * <p>Most of the packages usually do not perform identical glob invocations, but when complex
 * macros are used, it's not uncommon to see repeated invocations of the same {@code glob} function.
 *
 * <p>There is a more important reason to use consistent glob resolution while parsing the same
 * package - caching. This approach guarantees that all {@code glob} invocations are expanded in the
 * exact same way and as such all execution paths that rely on glob results will be consistent as
 * well. In other words, the snippet: <code><pre>
 *   if glob(['foo']):
 *     print("A")
 *   else:
 *     print("B")
 *   if glob(['foo']):
 *     print("C")
 *   else:
 *     print("D")
 * </pre></code> will always produce either {@code "AC"} or {@code "CD"} and never {@code "AD"} or
 * {@code "BC"}.
 */
@NotThreadSafe
public class CachingGlobber implements Globber {

  private final Globber delegate;
  private final Map<GlobSpec, Set<String>> cache;

  private CachingGlobber(Globber delegate) {
    this.delegate = delegate;
    this.cache = new HashMap<>();
  }

  @Override
  public Set<String> run(
      Collection<String> include, Collection<String> exclude, boolean excludeDirectories)
      throws IOException, InterruptedException {
    GlobSpec key =
        GlobSpec.builder()
            .setInclude(include)
            .setExclude(exclude)
            .setExcludeDirectories(excludeDirectories)
            .build();
    @Nullable Set<String> expandedPaths = cache.get(key);
    if (expandedPaths == null) {
      expandedPaths = delegate.run(include, exclude, excludeDirectories);
      cache.put(key, expandedPaths);
    }
    return expandedPaths;
  }

  /**
   * @return Glob manifest that includes information about expanded paths for each requested {@link
   *     GlobSpec}.
   */
  public ImmutableMap<GlobSpec, Set<String>> createGlobManifest() {
    return ImmutableMap.copyOf(cache);
  }

  public static CachingGlobber of(Globber globber) {
    return new CachingGlobber(globber);
  }
}
