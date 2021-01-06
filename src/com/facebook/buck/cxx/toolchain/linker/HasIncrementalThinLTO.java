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

package com.facebook.buck.cxx.toolchain.linker;

import com.facebook.buck.rules.args.Arg;
import java.nio.file.Path;

/** Indicates a linker can support incremental ThinLTO */
public interface HasIncrementalThinLTO {

  /**
   * @return the platform-specific way to generate intermediate build products for ThinLTO.
   * @param output the path of the output index files
   */
  Iterable<Arg> incrementalThinLTOFlags(Path output);
}
