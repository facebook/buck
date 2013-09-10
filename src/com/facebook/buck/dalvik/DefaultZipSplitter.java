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

package com.facebook.buck.dalvik;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collection;
import java.util.Set;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;

public class DefaultZipSplitter extends AbstractZipSplitter {

  private final long zipSizeSoftLimit;
  private long remainingSize;

  /**
   * @see ZipSplitterFactory#newInstance(Set, File, File, String, Predicate, DexSplitStrategy, CanaryStrategy, File)
   */
  private DefaultZipSplitter(
      Set<File> inFiles,
      File outPrimary,
      File outSecondaryDir,
      String secondaryPattern,
      long zipSizeSoftLimit,
      long zipSizeHardLimit,
      Predicate<String> requiredInPrimaryZip,
      ZipSplitter.DexSplitStrategy dexSplitStrategy,
      ZipSplitter.CanaryStrategy canaryStrategy,
      File reportDir) {
    super(inFiles,
        outPrimary,
        outSecondaryDir,
        secondaryPattern,
        zipSizeHardLimit,
        requiredInPrimaryZip,
        dexSplitStrategy,
        canaryStrategy,
        reportDir);
    this.zipSizeSoftLimit = zipSizeSoftLimit;
  }

  public static DefaultZipSplitter splitZip(
      Set<File> inFiles,
      File outPrimary,
      File outSecondaryDir,
      String secondaryPattern,
      long zipSizeSoftLimit,
      long zipSizeHardLimit,
      Predicate<String> requiredInPrimaryZip,
      ZipSplitter.DexSplitStrategy dexSplitStrategy,
      ZipSplitter.CanaryStrategy canaryStrategy,
      File reportDir) {
    return new DefaultZipSplitter(
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

  // Not safe to execute multiple times.
  @Override
  public Collection<File> execute() throws IOException {
    ClasspathTraverser classpathTraverser = new DefaultClasspathTraverser();

    // Compute the total size of the inputs so that we can figure out whether its safe
    // to begin putting non-essential entries into the primary zip.
    // TODO(devjasta): There's a more compact way of doing this by writing the primary zip during
    // this first-pass step then assigning it as the "currentSecondaryOut" to complete the second
    // pass.  We're already tracking unique entries so we would not end up adding those primary
    // entries twice.
    classpathTraverser.traverse(new ClasspathTraversal(inFiles) {
      @Override
      public void visit(FileLike entry) {
        long entrySize = entry.getSize();
        if (entrySize > 0) {
          remainingSize += entrySize;
        }
      }
    });

    currentSecondaryIndex = 0;
    primaryOut = newZipOutput(outPrimary);
    currentSecondaryOut = null;

    try {
      for (File inFile : inFiles) {
        classpathTraverser.traverse(new ClasspathTraversal(ImmutableSet.of(inFile)) {
          @Override
          public void visit(FileLike entry) throws IOException {
            processEntry(entry);
          }
        });

        // The soft limit was tripped (and not the hard limit).  Flag that the next non-zero length
        // entry should create a new zip.
        if (currentSecondaryOut != null &&
            currentSecondaryOut.getCurrentSize() >= zipSizeSoftLimit) {
          newSecondaryOutOnNextEntry = true;
        }
      }
    } finally {
      primaryOut.close();
      if (currentSecondaryOut != null) {
        currentSecondaryOut.close();
      }
    }

    return secondaryFiles.build();
  }

  private void processEntry(FileLike entry) throws IOException {
    long entrySize = entry.getSize();
    if (entrySize <= 0) {
      return;
    }
    if (entrySize > zipSizeHardLimit) {
      throw new IllegalArgumentException(
          "Single entry larger than limit: " + entry);
    }

    ZipOutputStreamHelper targetOut;

    // An entry is placed in the primary zip if either of the following is true:
    //
    // (1) The entry must appear in the first zip according to the EntryProcessor predicate.
    // (2) All of the remaining zip entries fit in the remaining space in the primary zip and
    //     we're trying to maximize the size of the primary zip.
    //
    // Otherwise, the entry will be added to the secondary zip.
    boolean canFitAllRemaining = remainingSize + primaryOut.getCurrentSize() <= zipSizeHardLimit;

    if ((canFitAllRemaining && dexSplitStrategy == ZipSplitter.DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE)
        || requiredInPrimaryZip.apply(entry.getRelativePath())        // File must be in primary
        ) {
      // Going to write this entry to the primary zip.
      if (!primaryOut.canPutEntry(entry)) {
        throw new IllegalArgumentException(
            "Unable to fit all required files in primary zip.");
      }
      targetOut = primaryOut;
    } else {
      targetOut = getSecondaryZipToWriteTo(entry);
    }

    targetOut.putEntry(entry);
    remainingSize -= entrySize;
  }

  @Override
  protected ZipOutputStreamHelper newZipOutput(File file) throws FileNotFoundException {
    return new ZipOutputStreamHelper(file, zipSizeHardLimit, reportDir) {
      @Override
      long getSize(FileLike fileLike) {
        return fileLike.getSize();
      }
    };
  }

}
