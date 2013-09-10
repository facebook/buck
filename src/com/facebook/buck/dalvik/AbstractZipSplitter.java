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

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;

public abstract class AbstractZipSplitter implements ZipSplitter {

  private static final String CANARY_PATH_FORMAT = "secondary/dex%02d/Canary.class";

  protected final Set<File> inFiles;
  protected final File outPrimary;
  private final File outSecondaryDir;
  private final String secondaryPattern;
  protected final long zipSizeHardLimit;
  protected final Predicate<String> requiredInPrimaryZip;
  protected final ZipSplitter.DexSplitStrategy dexSplitStrategy;
  private final ZipSplitter.CanaryStrategy canaryStrategy;
  protected final File reportDir;

  protected final ImmutableList.Builder<File> secondaryFiles = ImmutableList.builder();
  protected int currentSecondaryIndex;
  protected ZipOutputStreamHelper primaryOut;
  protected ZipOutputStreamHelper currentSecondaryOut;
  protected boolean newSecondaryOutOnNextEntry;

  /**
   * Produced by compiling the following Java file with JDK 7 with "-target 6 -source 6".
   * <pre>
   * package secondary.dex01;
   * public interface Canary {}
   * </pre>
   */
  private static final byte[] CANARY_TEMPLATE = {
       -54,   -2,  -70,  -66, 0x00, 0x00, 0x00, 0x32,
      0x00, 0x05, 0x07, 0x00, 0x03, 0x07, 0x00, 0x04,
      0x01, 0x00, 0x16, 0x73, 0x65, 0x63, 0x6f, 0x6e,
      0x64, 0x61, 0x72, 0x79, 0x2f, 0x64, 0x65, 0x78,
      0x30, 0x31, 0x2f, 0x43, 0x61, 0x6e, 0x61, 0x72,
      0x79, 0x01, 0x00, 0x10, 0x6a, 0x61, 0x76, 0x61,
      0x2f, 0x6c, 0x61, 0x6e, 0x67, 0x2f, 0x4f, 0x62,
      0x6a, 0x65, 0x63, 0x74, 0x06, 0x01, 0x00, 0x01,
      0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
      0x00, 0x00
  };

  /**
   * Offset into {@link #CANARY_TEMPLATE} where we find the 2-byte UTF-8 index that must be
   * updated for each canary class to change the package.
   */
  private static final int CANARY_INDEX_OFFSET = 32;

  /**
   * @param zipSizeHardLimit Units should be in terms of return value of
   *     {@link ZipOutputStreamHelper#getSize(FileLike)} for the {@link ZipOutputStreamHelper}
   *     returned by {@link #newZipOutput(File)}.
   */
  protected AbstractZipSplitter(
      Set<File> inFiles,
      File outPrimary,
      File outSecondaryDir,
      String secondaryPattern,
      long zipSizeHardLimit,
      Predicate<String> requiredInPrimaryZip,
      ZipSplitter.DexSplitStrategy dexSplitStrategy,
      ZipSplitter.CanaryStrategy canaryStrategy,
      File reportDir) {
    this.canaryStrategy = canaryStrategy;
    this.inFiles = ImmutableSet.copyOf(inFiles);
    this.outPrimary = Preconditions.checkNotNull(outPrimary);
    this.outSecondaryDir = Preconditions.checkNotNull(outSecondaryDir);
    this.secondaryPattern = Preconditions.checkNotNull(secondaryPattern);
    this.zipSizeHardLimit = zipSizeHardLimit;
    this.requiredInPrimaryZip = Preconditions.checkNotNull(requiredInPrimaryZip);
    this.dexSplitStrategy = dexSplitStrategy;
    this.reportDir = reportDir;
  }

  @Override
  public abstract Collection<File> execute() throws IOException;

  protected abstract ZipOutputStreamHelper newZipOutput(File file) throws FileNotFoundException;

  protected ZipOutputStreamHelper getSecondaryZipToWriteTo(FileLike entry) throws IOException {
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
      if (canaryStrategy == ZipSplitter.CanaryStrategy.INCLUDE_CANARIES) {
        // Make sure the first class in the new secondary dex can be safely loaded.
        addCanaryClass(currentSecondaryOut, currentSecondaryIndex);
      }
      // We've already tested for this. It really shouldn't happen.
      Preconditions.checkState(currentSecondaryOut.canPutEntry(entry));
    }

    return currentSecondaryOut;
  }

  /**
   * Adds a "canary" class to a secondary dex that can be safely loaded on any system.
   * This avoids an issue where, during secondary dex loading, we attempt to verify a
   * secondary dex by loading an arbitrary class, but the class we try to load isn't
   * valid on that system (e.g., it depends on Google Maps, but we are on AOSP).
   *
   * @param outZip Zip file to write the canary class to.
   * @param index Index of the current zip (to ensure unique names).
   */
  protected void addCanaryClass(
      ZipOutputStreamHelper outZip,
      final int index)
      throws IOException {
    final byte[] canaryClass = Arrays.copyOf(CANARY_TEMPLATE, CANARY_TEMPLATE.length);
    final String canaryIndexStr = String.format("%02d", index);
    byte[] canaryIndexBytes = canaryIndexStr.getBytes(Charset.forName("UTF-8"));
    Preconditions.checkState(canaryIndexBytes.length == 2,
        "Formatted index string should always be 2 bytes.");
    System.arraycopy(canaryIndexBytes, 0, canaryClass, CANARY_INDEX_OFFSET, 2);

    outZip.putEntry(new AbstractFileLike() {
      @Override
      public File getContainer() {
        return new File(":memory:");
      }

      @Override
      public String getRelativePath() {
        return String.format(CANARY_PATH_FORMAT, index);
      }

      @Override
      public long getSize() {
        return canaryClass.length;
      }

      @Override
      public InputStream getInput() {
        return new ByteArrayInputStream(canaryClass);
      }
    });
  }
}
