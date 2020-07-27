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

package com.facebook.buck.android.exopackage;

import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;

/** An exo installer helper. */
interface ExoHelper {
  /** Returns the files to install. */
  ImmutableMap<Path, Path> getFilesToInstall() throws IOException;

  /** Returns metadata to install. */
  ImmutableMap<Path, String> getMetadataToInstall() throws IOException;

  /** Returns the type of this installer. */
  String getType();
}
