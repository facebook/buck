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

public interface ZipSplitterFactory {

  /**
   * Both combines and splits a set of input files into zip files such that no one output zip file
   * has entries that in total exceed {@code zipSizeHardLimit}.  Input files can be themselves zip
   * files, individual class/resource files, or a directory of such of files.  The inputs are
   * "opened", and the files contained within them are individually processed.
   * <p>
   * For example, given a set of inFiles of A.zip and B.zip where A.zip contains { A, B, C }, and
   * B.zip contains { X, Y, Z }, a possible outcome of this method could yield outPrimary.zip
   * containing { A, B }, outSecondary1.zip containing { C, X }, and outSecondary2.zip containing
   * { Y, Z }.
   * <p>
   * This method exists as a critical utility to divide source code so large that dx/dexopt fail
   * due to design constraints.
   *
   * @param inFiles Set of input files (directories or zip files) whose contents should be placed in
   *     the output zip files.
   * @param outPrimary Primary output zip file.
   * @param outSecondaryDir Directory to place secondary output zip files (if any are generated).
   * @param secondaryPattern Pattern containing a single integer (%d) that forms the filename of
   *     output zip files placed in {@code outSecondaryDir}.
   * @param requiredInPrimaryZip Determine which input <em>entries</em> are necessary in the
   *     primary output zip file.  Note that this is referring to the entries contained within
   *     {@code inFiles}, not the input files themselves.
   * @param canaryStrategy Determine whether to include canary classes for easy verification.
   * @param reportDir Directory where to publish a report of which classes were written to which
   *     zip files with a corresponding size estimate.
   */
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
      File reportDir);
}
