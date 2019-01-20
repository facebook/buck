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

package com.facebook.buck.skylark.parser;

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;
import org.immutables.value.Value;

/**
 * Captures information related to all included extensions used in order to load the main build
 * spec. The main purpose of extra information is to properly capture all dependent information to
 * obtain the loaded files without full execution of the build spec.
 */
@Value.Immutable(builder = false)
@BuckStyleImmutable
abstract class AbstractIncludesData {
  /** @return a path from which the extension was loaded from */
  @Value.Parameter
  public abstract com.google.devtools.build.lib.vfs.Path getPath();

  /** @return a list of dependencies that were required to evaluate this extension */
  @Value.Parameter
  public abstract ImmutableList<IncludesData> getDependencies();

  /** @return the list of files loaded in order to parse this extension. */
  @Value.Parameter
  public abstract ImmutableList<String> getLoadTransitiveClosure();
}
