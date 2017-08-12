// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import com.android.tools.r8.OutputSink;
import java.io.IOException;
import java.util.Set;

/**
 * Implementation of an {@link OutputSink} that forwards all calls to another sink.
 * <p>
 * Useful for layering output sinks and intercept some output.
 */
public abstract class ForwardingOutputSink implements OutputSink {

  private final OutputSink forwardTo;

  protected ForwardingOutputSink(OutputSink forwardTo) {
    this.forwardTo = forwardTo;
  }

  @Override
  public void writeDexFile(byte[] contents, Set<String> classDescriptors, int fileId)
      throws IOException {
    forwardTo.writeDexFile(contents, classDescriptors, fileId);
  }

  @Override
  public void writeDexFile(byte[] contents, Set<String> classDescriptors, String primaryClassName)
      throws IOException {
    forwardTo.writeDexFile(contents, classDescriptors, primaryClassName);
  }

  @Override
  public void writeClassFile(byte[] contents, Set<String> classDescriptors, String primaryClassName)
      throws IOException {
    forwardTo.writeClassFile(contents, classDescriptors, primaryClassName);
  }

  @Override
  public void writePrintUsedInformation(byte[] contents) throws IOException {
    forwardTo.writePrintUsedInformation(contents);
  }

  @Override
  public void writeProguardMapFile(byte[] contents) throws IOException {
    forwardTo.writeProguardMapFile(contents);
  }

  @Override
  public void writeProguardSeedsFile(byte[] contents) throws IOException {
    forwardTo.writeProguardSeedsFile(contents);
  }

  @Override
  public void writeMainDexListFile(byte[] contents) throws IOException {
    forwardTo.writeMainDexListFile(contents);
  }

  @Override
  public void close() throws IOException {
    forwardTo.close();
  }
}
