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

package com.facebook.buck.android.dalvik;

import com.facebook.buck.android.apkmodule.APKModule;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Predicate;

public interface ZipSplitterFactory {

  /**
   * Both combines and splits a set of input files into zip files such that no one output zip file
   * has entries that in total exceed {@code zipSizeHardLimit}. Input files can be themselves zip
   * files, individual class/resource files, or a directory of such of files. The inputs are
   * "opened", and the files contained within them are individually processed.
   *
   * <p>For example, given a set of inFiles of A.zip and B.zip where A.zip contains { A, B, C }, and
   * B.zip contains { X, Y, Z }, a possible outcome of this method could yield outPrimary.zip
   * containing { A, B }, outSecondary1.zip containing { C, X }, and outSecondary2.zip containing {
   * Y, Z }.
   *
   * <p>This method exists as a critical utility to divide source code so large that dx/dexopt fail
   * due to design constraints.
   *
   * @param inFiles Set of input files (directories or zip files) whose contents should be placed in
   *     the output zip files.
   * @param outPrimary Primary output zip file.
   * @param outSecondaryDir Directory to place secondary output zip files (if any are generated).
   * @param secondaryPattern Pattern containing a single integer (%d) that forms the filename of
   *     output zip files placed in {@code outSecondaryDir}.
   * @param requiredInPrimaryZip Determine which input <em>entries</em> are necessary in the primary
   *     output zip file. Note that this is referring to the entries contained within {@code
   *     inFiles}, not the input files themselves.
   * @param secondaryHeadSet list of classes to include in the primary dex until it is full
   * @param secondaryTailSet list of classes to include last in the secondary dex
   * @param additionalDexStores mapping of APKModules to module names for creating additional dex
   *     stores beyond the primary and secondary dex
   * @param rootAPKModule
   * @param reportDir Directory where to publish a report of which classes were written to which zip
   *     files with a corresponding size estimate.
   */
  ZipSplitter newInstance(
      ProjectFilesystem filesystem,
      Set<Path> inFiles,
      Path outPrimary,
      Path outSecondaryDir,
      String secondaryPattern,
      Path outDexStoresDir,
      Predicate<String> requiredInPrimaryZip,
      ImmutableSet<String> secondaryHeadSet,
      ImmutableSet<String> secondaryTailSet,
      ImmutableMultimap<APKModule, String> additionalDexStores,
      APKModule rootAPKModule,
      ZipSplitter.DexSplitStrategy dexSplitStrategy,
      Path reportDir);
}
