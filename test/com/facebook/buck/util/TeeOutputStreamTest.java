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

package com.facebook.buck.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class TeeOutputStreamTest {
  @Test
  public void delegates() throws Exception {
    CloseRecordingByteArrayOutputStream one = new CloseRecordingByteArrayOutputStream();
    CloseRecordingByteArrayOutputStream two = new CloseRecordingByteArrayOutputStream();
    TeeOutputStream teeOutputStream = new TeeOutputStream(one, two);
    teeOutputStream.write("foo".getBytes(StandardCharsets.UTF_8));
    teeOutputStream.flush();
    assertEquals("foo", one.toUtf8String());
    assertEquals("foo", two.toUtf8String());

    teeOutputStream.close();
    assertTrue(one.isClosed());
    assertTrue(two.isClosed());
  }

  static class CloseRecordingByteArrayOutputStream extends FilterOutputStream {
    private final ByteArrayOutputStream delegate;

    public CloseRecordingByteArrayOutputStream() {
      this(new ByteArrayOutputStream());
    }

    private CloseRecordingByteArrayOutputStream(ByteArrayOutputStream delegate) {
      super(delegate);
      this.delegate = delegate;
    }

    private boolean isClosed = false;

    @Override
    public void close() {
      isClosed = true;
    }

    public boolean isClosed() {
      return isClosed;
    }

    public String toUtf8String() {
      try {
        return delegate.toString(StandardCharsets.UTF_8.toString());
      } catch (UnsupportedEncodingException e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
