/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.cli;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

/** Configuration options used by {@code buck clean} command. */
@BuckStyleTuple
@Value.Immutable(builder = false, copy = false)
public abstract class AbstractCleanCommandBuckConfig implements ConfigView<BuckConfig> {

  @Override
  public abstract BuckConfig getDelegate();

  @Value.Lazy
  public ImmutableList<String> getCleanAdditionalPaths() {
    return getDelegate().getListWithoutComments("clean", "additional_paths");
  }

  @Value.Lazy
  public ImmutableList<String> getCleanExcludedCaches() {
    return getDelegate().getListWithoutComments("clean", "excluded_dir_caches");
  }
}
