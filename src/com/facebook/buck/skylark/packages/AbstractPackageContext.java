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

package com.facebook.buck.skylark.packages;

import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.skylark.io.Globber;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.events.EventHandler;
import org.immutables.value.Value;

/** Exposes package information to Skylark functions. */
@Value.Immutable(builder = false)
@BuckStyleImmutable
abstract class AbstractPackageContext {
  /** Returns a globber instance that can resolve paths relative to current package. */
  @Value.Parameter
  public abstract Globber getGlobber();

  /**
   * Returns a raw map of configuration options defined in {@code .buckconfig} file and passed
   * through a {@code --config} command line option.
   */
  @Value.Parameter
  public abstract ImmutableMap<String, ImmutableMap<String, String>> getRawConfig();

  /** Returns a package identifier of the build file that is being parsed. */
  @Value.Parameter
  public abstract PackageIdentifier getPackageIdentifier();

  /** @return The event handler for reporting events during package parsing. */
  @Value.Parameter
  public abstract EventHandler getEventHandler();

  /** Gets objects that were implciitly loaded */
  @Value.Parameter
  public abstract ImmutableMap<String, Object> getImplicitlyLoadedSymbols();
}
