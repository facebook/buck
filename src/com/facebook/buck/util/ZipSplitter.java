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

package com.facebook.buck.util;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ZipSplitter {

  public static enum DexSplitStrategy {
    MAXIMIZE_PRIMARY_DEX_SIZE,
    MINIMIZE_PRIMARY_DEX_SIZE,
    ;
  }

  private final Set<File> inFiles;
  private final File outPrimary;
  private final File outSecondaryDir;
  private final String secondaryPattern;
  private final long zipSizeSoftLimit;
  private final long zipSizeHardLimit;
  private final Predicate<String> requiredInPrimaryZip;
  private final DexSplitStrategy dexSplitStrategy;

  private final ImmutableList.Builder<File> secondaryFiles = ImmutableList.builder();
  private long remainingSize;
  private int currentSecondaryIndex;
  private ZipOutputStreamHelper primaryOut;
  private ZipOutputStreamHelper currentSecondaryOut;
  private boolean newSecondaryOutOnNextEntry;

  private ZipSplitter(
      Set<File> inFiles,
      File outPrimary,
      File outSecondaryDir,
      String secondaryPattern,
      long zipSizeSoftLimit,
      long zipSizeHardLimit,
      Predicate<String> requiredInPrimaryZip,
      DexSplitStrategy dexSplitStrategy) {
    this.inFiles = ImmutableSet.copyOf(inFiles);
    this.outPrimary = Preconditions.checkNotNull(outPrimary);
    this.outSecondaryDir = Preconditions.checkNotNull(outSecondaryDir);
    this.secondaryPattern = Preconditions.checkNotNull(secondaryPattern);
    this.zipSizeSoftLimit = zipSizeSoftLimit;
    this.zipSizeHardLimit = zipSizeHardLimit;
    this.requiredInPrimaryZip = Preconditions.checkNotNull(requiredInPrimaryZip);
    this.dexSplitStrategy = dexSplitStrategy;
  }

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
   * @param zipSizeSoftLimit Soft limit for the resulting zip file.  Once hit, a new output zip
   *     file will be created after processing the current {@code inFiles} entry.  This soft
   *     limit affects only secondary zip files.  Has no effect if this value is greater than or
   *     equal to {@code zipSizeHardLimit}.
   * @param zipSizeHardLimit Maximum size of the entries in each output zip file.  A new output
   *     zip file will be created immediately before this limit is exceeded regardless of whether
   *     it straddles a single {@code inFiles} entry.
   * @param requiredInPrimaryZip Determine which input <em>entries</em> are necessary in the
   *     primary output zip file.  Note that this is referring to the entries contained within
   *     {@code inFiles}, not the input files themselves.
   * @return Secondary output zip files.
   * @throws IOException
   */
  public static Collection<File> splitZip(
      Set<File> inFiles,
      File outPrimary,
      File outSecondaryDir,
      String secondaryPattern,
      long zipSizeSoftLimit,
      long zipSizeHardLimit,
      Predicate<String> requiredInPrimaryZip,
      DexSplitStrategy dexSplitStrategy) throws IOException {
    ZipSplitter splitter = new ZipSplitter(
        inFiles,
        outPrimary,
        outSecondaryDir,
        secondaryPattern,
        zipSizeSoftLimit,
        zipSizeHardLimit,
        requiredInPrimaryZip,
        dexSplitStrategy);
    return splitter.execute();
  }

  // Not safe to execute multiple times.
  private Collection<File> execute() throws IOException {
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
          public void visit(FileLike entry) {
            try {
              processEntry(entry);
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }
        });

        // The soft limit was tripped (and not the hard limit).  Flag that the next non-zero length
        // entry should create a new zip.
        if (currentSecondaryOut != null &&
            currentSecondaryOut.getCurrentSize() >= zipSizeSoftLimit) {
          newSecondaryOutOnNextEntry = true;
        }
      }
    } catch (RuntimeException e) {
      Throwables.propagateIfInstanceOf(e.getCause(), IOException.class);
      throw Throwables.propagate(e);
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

    if ((canFitAllRemaining && dexSplitStrategy == DexSplitStrategy.MAXIMIZE_PRIMARY_DEX_SIZE)
        || requiredInPrimaryZip.apply(entry.getRelativePath())        // File must be in primary
        ) {
      // Going to write this entry to the primary zip.
      if (!primaryOut.canPutEntry(entry)) {
        throw new IllegalArgumentException(
            "Unable to fit all required files in primary zip.");
      }
      targetOut = primaryOut;
    } else {
      // Going to write this entry to a secondary zip.
      if (currentSecondaryOut == null ||
          !currentSecondaryOut.canPutEntry(entry) ||
          newSecondaryOutOnNextEntry) {
        if (currentSecondaryOut != null) {
          currentSecondaryOut.close();
        }
        currentSecondaryIndex++;
        File newSecondaryFile = new File(
            outSecondaryDir,
            String.format(secondaryPattern, currentSecondaryIndex));
        secondaryFiles.add(newSecondaryFile);
        currentSecondaryOut = newZipOutput(newSecondaryFile);
        newSecondaryOutOnNextEntry = false;
        // We've already tested for this. It really shouldn't happen.
        Preconditions.checkState(currentSecondaryOut.canPutEntry(entry));
      }
      targetOut = currentSecondaryOut;
    }

    targetOut.putEntry(entry);
    remainingSize -= entrySize;
  }

  private ZipOutputStreamHelper newZipOutput(File file) throws FileNotFoundException {
    return new ZipOutputStreamHelper(
        new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(file))));
  }

  private class ZipOutputStreamHelper implements Closeable {
    private final ZipOutputStream outStream;
    private final Set<String> entryNames = Sets.newHashSet();

    private long currentSize;

    private ZipOutputStreamHelper(ZipOutputStream outStream) {
      this.outStream = outStream;
    }

    public long getCurrentSize() {
      return currentSize;
    }

    private boolean isEntryTooBig(long entrySize) {
      return (currentSize + entrySize > zipSizeHardLimit);
    }

    /**
     * Tests whether the file-like instance can be placed into the zip entry without
     * exceeding the maximum size limit.
     * @param fileLike File-like instance to test.
     * @return True if the file-like instance is small enough to fit; false otherwise.
     * @see #putEntry
     */
    public boolean canPutEntry(FileLike fileLike) {
      return !isEntryTooBig(fileLike.getSize());
    }

    /**
     * Attempt to put the next entry.
     * @param fileLike File-like instance to add as a zip entry.
     * @throws IOException
     * @throws IllegalStateException Thrown if putting this entry would exceed the maximum size
     *     limit.  See {#link #canPutEntry}.
     */
    public void putEntry(FileLike fileLike) throws IOException {
      String name = fileLike.getRelativePath();
      // Tracks unique entry names and avoids duplicates.  This is, believe it or not, how
      // proguard seems to handle merging multiple -injars into a single -outjar.
      if (!entryNames.contains(name)) {
        entryNames.add(name);
        outStream.putNextEntry(new ZipEntry(name));
        InputStream in = fileLike.getInput();
        long entrySize;
        try {
          entrySize = ByteStreams.copy(in, outStream);
        } finally {
          in.close();
        }
        // Make sure FileLike#getSize didn't lie (or we forgot to call canPutEntry).
        Preconditions.checkState(!isEntryTooBig(entrySize),
            "Putting entry %s (%d) exceeded maximum size of %d", name, entrySize, zipSizeHardLimit);
        currentSize += entrySize;
      }
    }

    @Override
    public void close() throws IOException {
      outStream.close();
    }
  }
}
