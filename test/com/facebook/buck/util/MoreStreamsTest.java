/*
 * Copyright 2016-present Facebook, Inc.
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

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class MoreStreamsTest {
  @Test
  public void testCopyingWithCorrectExactSize() throws IOException {
    testForSizeBytes(42, 42, 42);
  }

  @Test(expected = IOException.class)
  public void testCopyingWithShortInput() throws IOException {
    testForSizeBytes(21, 42, 42);
  }

  @Test(expected = ArrayIndexOutOfBoundsException.class)
  public void testCopyingWithShortOutput() throws IOException {
    testForSizeBytes(42, 21, 42);
  }

  @Test
  public void testCopyingWithLongOutput() throws IOException {
    testForSizeBytes(42, 84, 42);
  }

  @Test
  public void testCopyingWithLongInput() throws IOException {
    testForSizeBytes(84, 42, 42);
  }

  private void testForSizeBytes(
      int inputStreamSizeBytes,
      int outputStreamSizeBytes,
      int bytesToCopy) throws IOException {

    byte[] inputBuffer = new byte[inputStreamSizeBytes];
    for (int i = 0; i < inputStreamSizeBytes; ++i) {
      inputBuffer[i] = (byte) (i % Byte.MAX_VALUE);
    }

    byte[] outputBuffer = new byte[outputStreamSizeBytes];

    try (OutputStream output = wrapBuffer(outputBuffer);
         InputStream input = new ByteArrayInputStream(inputBuffer)) {
      MoreStreams.copyExactly(input, output, bytesToCopy);

      for (int i = 0; i < bytesToCopy; ++i) {
        Assert.assertEquals(
            "Data is different between the input buffer and the output buffer.",
            inputBuffer[i],
            outputBuffer[i]);
      }

      for (int i = inputStreamSizeBytes; i < outputStreamSizeBytes; ++i) {
        Assert.assertEquals(
            "Extra bytes in output buffer should be unchanged.",
            0,
            outputBuffer[i]);
      }
    }
  }

  private OutputStream wrapBuffer(final byte[] buffer) {
    return new OutputStream() {
      private int pos = 0;

      @Override
      public void write(int b) throws IOException {
        buffer[pos++] = (byte) b;
      }
    };
  }
}
