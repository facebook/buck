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

package com.facebook.buck.skylark.parser;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.syntax.Environment.Extension;

/**
 * Captures {@link Extension} and information related to its parsing like all other extensions used
 * in order to load it. The main purpose of extra information is to properly captured all dependent
 * information for caching purposes.
 */
@BuckStyleValue
abstract class ExtensionData {
  /** @return an extension with its bindings */
  public abstract Extension getExtension();

  /** @return a path from which the extension was loaded from */
  public abstract com.google.devtools.build.lib.vfs.Path getPath();

  /** @return a set of dependencies that were required to evaluate this extension */
  public abstract ImmutableSet<ExtensionData> getDependencies();

  /** @return a load function label that triggered load of this extension */
  public abstract String getImportString();

  /**
   * @return the list of files loaded in order to parse this extension including the path of this
   *     extension, which is the first element of the list.
   */
  public abstract ImmutableSet<String> getLoadTransitiveClosure();

  ExtensionData withImportString(String importString) {
    return ImmutableExtensionData.of(
        getExtension(), getPath(), getDependencies(), importString, getLoadTransitiveClosure());
  }
}
