/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.zip;

import com.facebook.buck.util.function.ThrowingSupplier;
import java.io.IOException;
import java.io.InputStream;

/**
 * Encapsulates a file or directory to be added as a single entry to a jar by {@link JarBuilder}.
 */
public class JarEntrySupplier {
  private final CustomZipEntry entry;
  private final String owner;
  private final ThrowingSupplier<InputStream, IOException> inputStreamSupplier;

  public JarEntrySupplier(
      CustomZipEntry entry,
      String owner,
      ThrowingSupplier<InputStream, IOException> inputStreamSupplier) {
    this.entry = entry;
    this.owner = owner;
    this.inputStreamSupplier = inputStreamSupplier;
  }

  public CustomZipEntry getEntry() {
    return entry;
  }

  public String getEntryOwner() {
    return owner;
  }

  public ThrowingSupplier<InputStream, IOException> getInputStreamSupplier() {
    return inputStreamSupplier;
  }
}
