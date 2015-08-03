/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.dalvik;

import com.facebook.buck.java.classes.FileLike;

import java.io.IOException;

/**
 * Interface for generating a zip file.
 */
interface ZipOutputStreamHelper extends AutoCloseable {

  /**
   * Tests whether the file-like instance can be placed into the zip entry without
   * exceeding the maximum size limit.
   * @param fileLike File-like instance to test.
   * @return True if the file-like instance is small enough to fit; false otherwise.
   * @see #putEntry
   */
  boolean canPutEntry(FileLike fileLike) throws IOException;

  /**
   * Attempt to put the next entry.
   * @param fileLike File-like instance to add as a zip entry.
   * @throws IOException
   * @throws IllegalStateException Thrown if putting this entry would exceed the maximum size
   *     limit.  See {#link #canPutEntry}.
   */
  void putEntry(FileLike fileLike) throws IOException;

  /**
   * Checks whether the helper contains the specified entry.
   *
   * @param fileLike the file to check
   * @return whether it contains the entry
   */
  boolean containsEntry(FileLike fileLike);

  @Override
  void close() throws IOException;
}
