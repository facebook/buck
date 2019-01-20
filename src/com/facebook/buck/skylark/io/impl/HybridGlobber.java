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

import com.facebook.buck.skylark.io.Globber;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/**
 * A {@link Globber} implementation that tries to use Watchman if it's available and falls back to a
 * fallback globber, in case Watchman query cannot be fulfilled.
 */
public class HybridGlobber implements Globber {
  private final Globber fallbackGlobber;
  private final WatchmanGlobber watchmanGlobber;

  public HybridGlobber(Globber fallbackGlobber, WatchmanGlobber watchmanGlobber) {
    this.fallbackGlobber = fallbackGlobber;
    this.watchmanGlobber = watchmanGlobber;
  }

  @Override
  public Set<String> run(
      Collection<String> include, Collection<String> exclude, boolean excludeDirectories)
      throws IOException, InterruptedException {
    Optional<ImmutableSet<String>> watchmanResult =
        watchmanGlobber.run(include, exclude, excludeDirectories);
    if (watchmanResult.isPresent()) {
      return watchmanResult.get();
    }
    return fallbackGlobber.run(include, exclude, excludeDirectories);
  }
}
