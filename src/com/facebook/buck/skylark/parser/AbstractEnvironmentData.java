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
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.syntax.Environment;
import org.immutables.value.Value;

/**
 * Captures {@link Environment} and information related to its parsing like all {@link
 * com.google.devtools.build.lib.vfs.Path} used in order to create this environment.
 */
@Value.Immutable(builder = false)
@BuckStyleImmutable
abstract class AbstractEnvironmentData {
  @Value.Parameter
  public abstract Environment getEnvironment();

  @Value.Parameter
  public abstract ImmutableSet<String> getLoadedPaths();
}
