// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import com.android.tools.r8.OutputSink;
import java.io.IOException;
import java.util.Set;

public class IgnoreContentsOutputSink implements OutputSink {

  @Override
  public void writeDexFile(byte[] contents, Set<String> classDescriptors, int fileId) {
    // Intentionally left empty.
  }

  @Override
  public void writeDexFile(byte[] contents, Set<String> classDescriptors, String primaryClassName) {
    // Intentionally left empty.
  }

  @Override
  public void writeClassFile(
      byte[] contents, Set<String> classDescriptors, String primaryClassName) {
    // Intentionally left empty.
  }

  @Override
  public void writePrintUsedInformation(byte[] contents) {
    // Intentionally left empty.
  }

  @Override
  public void writeProguardMapFile(byte[] contents) {
    // Intentionally left empty.
  }

  @Override
  public void writeProguardSeedsFile(byte[] contents) {
    // Intentionally left empty.
  }

  @Override
  public void writeMainDexListFile(byte[] contents) {
    // Intentionally left empty.
  }

  @Override
  public void close() throws IOException {
    // Intentionally left empty.
  }
}
