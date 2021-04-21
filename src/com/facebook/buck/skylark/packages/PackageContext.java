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

package com.facebook.buck.skylark.packages;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.starlark.eventhandler.EventHandler;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.skylark.io.Globber;
import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.immutables.value.Value;

/** Exposes package information to Skylark functions. */
@BuckStyleValue
public abstract class PackageContext {
  /** Returns a globber instance that can resolve paths relative to current package. */
  public abstract Globber getGlobber();

  /**
   * Returns a raw map of configuration options defined in {@code .buckconfig} file and passed
   * through a {@code --config} command line option.
   */
  public abstract ImmutableMap<String, ImmutableMap<String, String>> getRawConfig();

  /** Returns a cell name of the build file that is being parsed. */
  public abstract CanonicalCellName getCellName();

  public abstract ForwardRelativePath getBasePath();

  @Value.Lazy
  public String getBasePathString() {
    return getBasePath().toString();
  }

  /** @return The event handler for reporting events during package parsing. */
  public abstract EventHandler getEventHandler();

  public static PackageContext of(
      Globber globber,
      Map<String, ? extends ImmutableMap<String, String>> rawConfig,
      CanonicalCellName cellName,
      ForwardRelativePath basePath,
      EventHandler eventHandler,
      Map<String, ? extends Object> implicitlyLoadedSymbols) {
    return ImmutablePackageContext.ofImpl(
        globber, rawConfig, cellName, basePath, eventHandler, implicitlyLoadedSymbols);
  }

  /** Gets objects that were implciitly loaded */
  public abstract ImmutableMap<String, Object> getImplicitlyLoadedSymbols();
}
