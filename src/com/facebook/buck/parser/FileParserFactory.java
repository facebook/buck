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

package com.facebook.buck.parser;

import com.facebook.buck.core.cell.Cell;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.watchman.Watchman;
import com.facebook.buck.parser.api.FileManifest;
import com.facebook.buck.parser.api.FileParser;

/** Factory for creating instances of {@link FileParser}. */
public interface FileParserFactory<T extends FileManifest> {
  /** Creates an instance of {link ProjectBuildFileParser} based on passed in options. */
  FileParser<T> createFileParser(
      BuckEventBus eventBus, Cell cell, Watchman watchman, boolean threadSafe);
}
