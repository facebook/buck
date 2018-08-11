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

package com.facebook.buck.util.unarchive;

import com.google.common.collect.ImmutableList;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A collection different archive types, and the unarchiver that should be used to extract archives
 */
public enum ArchiveFormat {
  TAR(".tar", "tar", Untar.tarUnarchiver()),
  TAR_BZ2(".tar.bz2", "tar.bz2", Untar.bzip2Unarchiver()),
  TAR_GZ(".tar.gz", "tar.gz", Untar.gzipUnarchiver()),
  TAR_XZ(".tar.xz", "tar.xz", Untar.xzUnarchiver()),
  TAR_ZSTD(".tar.zst", "tar.zst", Untar.zstdUnarchiver()),
  ZIP(".zip", "zip", new Unzip());

  private final String extension;
  private final String shortName;
  private final Unarchiver unarchiver;

  /**
   * Creates an instance of {@link ArchiveFormat}
   *
   * @param extension The file extension (including dots) that this type normally has
   * @param shortName A short name that is used in build files to specify a particular archive type
   * @param unarchiver The unarchiver that should handle extracting archives of this type
   */
  ArchiveFormat(String extension, String shortName, Unarchiver unarchiver) {
    this.extension = extension;
    this.shortName = shortName;
    this.unarchiver = unarchiver;
  }

  /**
   * Gets the archive format based on a file
   *
   * @param filename The filename to try to use
   * @return The archive format, or empty if no matching format could be found
   */
  public static Optional<ArchiveFormat> getFormatFromFilename(String filename) {
    return Stream.of(ArchiveFormat.values())
        .filter(format -> filename.endsWith(format.extension))
        .findFirst();
  }

  /**
   * Gets the archive format based on a short name
   *
   * @param shortName The short name used in build files
   * @return The archive format, or empty if no matching format could be found
   */
  public static Optional<ArchiveFormat> getFormatFromShortName(String shortName) {
    return Stream.of(ArchiveFormat.values())
        .filter(format -> shortName.endsWith(format.shortName))
        .findFirst();
  }

  /** Get the unarchiver used to extract archives of this type */
  public Unarchiver getUnarchiver() {
    return unarchiver;
  }

  /** Get the extension (including '.') for this archive type */
  public String getExtension() {
    return extension;
  }

  /** Get the short name for the archive type */
  public String getShortName() {
    return shortName;
  }

  @Override
  public String toString() {
    return String.format("%s (%s)", shortName, extension);
  }

  /** Get all available short names */
  public static ImmutableList<String> getShortNames() {
    return Stream.of(ArchiveFormat.values())
        .map(ArchiveFormat::getShortName)
        .collect(ImmutableList.toImmutableList());
  }

  /** Get all available file extensions */
  public static ImmutableList<String> getFileExtensions() {
    return Stream.of(ArchiveFormat.values())
        .map(ArchiveFormat::getExtension)
        .collect(ImmutableList.toImmutableList());
  }
}
