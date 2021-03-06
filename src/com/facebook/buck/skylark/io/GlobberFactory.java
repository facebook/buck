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

package com.facebook.buck.skylark.io;

import com.facebook.buck.core.filesystems.AbsPath;
import java.io.Closeable;
import java.io.IOException;

/** Creates {@link Globber} instances to save clients from implementation details. */
public interface GlobberFactory extends Closeable {

  /**
   * Create a globber which performs a glob against given base path. This operation is pure and
   * cheap.
   */
  Globber create(AbsPath basePath);

  /** Close the globber. */
  @Override
  void close() throws IOException;
}
