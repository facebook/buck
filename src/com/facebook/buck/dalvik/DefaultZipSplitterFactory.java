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

import com.facebook.buck.io.ProjectFilesystem;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.nio.file.Path;
import java.util.Set;

public class DefaultZipSplitterFactory implements ZipSplitterFactory {

  private final long zipSizeSoftLimit;
  private final long zipSizeHardLimit;

  /**
   * @param zipSizeSoftLimit Soft limit for the resulting zip file.  Once hit, a new output zip
   *     file will be created after processing the current {@code inFiles} entry.  This soft
   *     limit affects only secondary zip files.  Has no effect if this value is greater than or
   *     equal to {@code zipSizeHardLimit}.
   * @param zipSizeHardLimit Maximum size of the entries in each output zip file.  A new output
   *     zip file will be created immediately before this limit is exceeded regardless of whether
   *     it straddles a single {@code inFiles} entry.
   */
  public DefaultZipSplitterFactory(long zipSizeSoftLimit, long zipSizeHardLimit) {
    this.zipSizeSoftLimit = zipSizeSoftLimit;
    this.zipSizeHardLimit = zipSizeHardLimit;
  }

  @Override
  public ZipSplitter newInstance(
      ProjectFilesystem filesystem,
      Set<Path> inFiles,
      File outPrimary,
      File outSecondaryDir,
      String secondaryPattern,
      Predicate<String> requiredInPrimaryZip,
      ImmutableSet<String> secondaryHeadSet,
      ImmutableSet<String> secondaryTailSet,
      ZipSplitter.DexSplitStrategy dexSplitStrategy,
      ZipSplitter.CanaryStrategy canaryStrategy,
      File reportDir) {
    return DefaultZipSplitter.splitZip(
        filesystem,
        inFiles,
        outPrimary,
        outSecondaryDir,
        secondaryPattern,
        zipSizeSoftLimit,
        zipSizeHardLimit,
        requiredInPrimaryZip,
        dexSplitStrategy,
        canaryStrategy,
        reportDir);
  }

}
