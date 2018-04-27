/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.swift.toolchain;

import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.immutables.value.Value;

/** Interface describing a Swift toolchain and platform to build for. */
@Value.Immutable
@BuckStyleImmutable
interface AbstractSwiftPlatform {

  Tool getSwiftc();

  Optional<Tool> getSwiftStdlibTool();

  /**
   * @return A set of directories which contain the Swift runtime as dynamic libraries. On macOS,
   *     the directory will contain libs like libswiftCore.dylib and others.
   */
  Set<Path> getSwiftRuntimePaths();

  /**
   * @return A set of directories which contain the Swift runtime as static libraries. On macOS, the
   *     directory will contain libs like libswiftCore.a and others.
   */
  Set<Path> getSwiftStaticRuntimePaths();

  /**
   * @return A set of search paths used by the dynamic linker loader to find of linked shared
   *     libraries. Each of the paths is usually referred as an "rpath". For example, on iOS,
   *     "@executable_path/Frameworks" is a common rpath.
   */
  ImmutableList<Path> getSwiftSharedLibraryRunPaths();
}
