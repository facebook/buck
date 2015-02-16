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

package com.facebook.buck.zip;

import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.util.HumanReadableException;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class ZipOutputStreams {

  private ZipOutputStreams() {
    // factory class
  }

  /**
   * Create a new {@link CustomZipOutputStream} that outputs to the given {@code zipFile}. Note that
   * the parent directory of the {@code zipFile} must exist already. The returned stream will throw
   * an exception should duplicate entries be added.
   *
   * @param zipFile The file to write to.
   */
  public static CustomZipOutputStream newOutputStream(File zipFile) {
    try {
      return newOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)));
    } catch (FileNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Create a new {@link CustomZipOutputStream} that will by default act in the same way as
   * {@link java.util.zip.ZipOutputStream}, notably by throwing an exception if duplicate entries
   * are added.
   *
   * @param out The output stream to write to.
   */
  public static CustomZipOutputStream newOutputStream(OutputStream out) {
    return newOutputStream(out, HandleDuplicates.THROW_EXCEPTION);
  }

  /**
   * Create a new {@link CustomZipOutputStream} that handles duplicate entries in the way dictated
   * by {@code mode}.
   *
   * @param zipFile The file to write to.
   * @param mode How to handle duplicate entries.
   */
  public static CustomZipOutputStream newOutputStream(File zipFile, HandleDuplicates mode)
      throws FileNotFoundException {

    return newOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)), mode);
  }

  /**
   * Create a new {@link CustomZipOutputStream} that handles duplicate entries in the way dictated
   * by {@code mode}.
   *
   * @param out The output stream to write to.
   * @param mode How to handle duplicate entries.
   */
  public static CustomZipOutputStream newOutputStream(OutputStream out, HandleDuplicates mode) {
    Clock clock = new DefaultClock();

    switch (mode) {
      case APPEND_TO_ZIP:
      case THROW_EXCEPTION:
        return new AppendingZipOutputStream(clock,
            out,
            mode == HandleDuplicates.THROW_EXCEPTION);

      case OVERWRITE_EXISTING:
        return new OverwritingZipOutputStream(clock, out);

      default:
        throw new HumanReadableException(
            "Unable to determine which zip output mode to use: %s", mode);
    }
  }

  public static enum HandleDuplicates {
    /** Duplicate entries are simply appended to the zip. */
    APPEND_TO_ZIP,
    /** An exception should be thrown if a duplicate entry is added to a zip. */
    THROW_EXCEPTION,
    /** A duplicate entry overwrites an existing entry with the same name. */
    OVERWRITE_EXISTING
  }
}
