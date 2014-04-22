/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.java.classes;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Provides a file-like interface for objects which may be present within more specialized
 * containers (like zip files).
 */
public interface FileLike {
  /**
   * Returns the containing file for this file-like entry.
   */
  public File getContainer();

  /**
   * Returns the relative path for the entry.  For example, if this were a
   * zip file, this would be the relative path of a particular item in the zip file.
   */
  public String getRelativePath();

  /**
   * Returns the size of the entry in bytes.
   */
  public long getSize();

  /**
   * Opens a new input stream for the entry.  This can be repeated to open the file-like
   * object multiple times.
   *
   * @return Newly opened input stream.
   * @throws java.io.IOException An error occurred opening the stream.
   */
  public InputStream getInput() throws IOException;
}
