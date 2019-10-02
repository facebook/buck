/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.util.zip.collect;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import java.nio.file.Path;
import org.immutables.value.Value;

/** A source for a zip file entry that represents an entry for another zip file. */
@BuckStyleValue
@Value.Immutable(builder = false, copy = false, prehash = true)
public interface ZipEntrySourceFromZip extends ZipEntrySource {

  /** Path to the source zip file. */
  @Override
  Path getSourceFilePath();

  /** The name of the entry */
  @Override
  String getEntryName();

  /** Position of the entry in the list of entries. */
  int getEntryPosition();
}
