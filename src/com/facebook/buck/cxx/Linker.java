/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.rules.SourcePath;

/**
 * An object wrapping a linker, providing its source path and an interface to decorate
 * arguments with specific flags.
 */
public interface Linker {

  /**
   * @return {@link SourcePath} to the linker.
   */
  public SourcePath getPath();

  /**
   * @return the platform-specific way to specify that the library represented by the
   *     given argument should be linked whole.
   */
  public abstract Iterable<String> linkWhole(String arg);

  /**
   * @return the platform-specific way to specify that linker should use the given soname
   *     when linking a shared library.
   */
  public abstract Iterable<String> soname(String soname);

}
