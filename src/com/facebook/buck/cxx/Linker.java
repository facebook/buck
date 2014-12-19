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

/**
 * An object wrapping a linker, providing its source path and an interface to decorate
 * arguments with specific flags.
 */
public interface Linker {

  /**
   * @return {@link Tool} representing the linker.
   */
  Tool getTool();

  /**
   * @return the platform-specific way to specify that the library represented by the
   *     given argument should be linked whole.
   */
  Iterable<String> linkWhole(String arg);

  /**
   * @return the platform-specific way to specify that linker should use the given soname
   *     when linking a shared library.
   */
  Iterable<String> soname(String soname);

  /**
   * The various ways to link an output file.
   */
  public static enum LinkType {

    // Link as standalone executable.
    EXECUTABLE,

    // Link as shared library, which can be loaded into a process image.
    SHARED,

  }

  /**
   * The various ways to link in dependencies.
   */
  public static enum LinkableDepType {

    // Provide input suitable for statically linking this linkable (e.g. return references to
    // static libraries, libfoo.a).
    STATIC,

    // Provide input suitable for dynamically linking this linkable (e.g. return references to
    // shared libraries, libfoo.so).
    SHARED

  }

}
