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

package com.facebook.buck.util.zip;

import com.google.common.base.Preconditions;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;

/**
 * An implementation of an {@link OutputStream} that will zip output. Note that, just as with {@link
 * java.util.zip.ZipOutputStream}, no implementation of this is thread-safe.
 */
public class CustomZipOutputStream extends OutputStream {

  protected interface Impl {
    /**
     * Called by {@link CustomZipOutputStream#putNextEntry(ZipEntry)} and used by impls to put the
     * next entry into the zip file. It is guaranteed that the {@code entry} won't be null and the
     * stream will be open. It is also guaranteed that there's no current entry open.
     *
     * @param entry The {@link ZipEntry} to write.
     */
    void actuallyPutNextEntry(ZipEntry entry) throws IOException;

    /**
     * Called by {@link CustomZipOutputStream#close()} and used by impls to close the delegate
     * stream. This method will be called at most once in the lifecycle of the
     * CustomZipOutputStream.
     */
    void actuallyCloseEntry() throws IOException;

    /**
     * Called by {@link CustomZipOutputStream#write(byte[], int, int)} only once it is known that
     * the stream has not been closed, and that a {@link ZipEntry} has already been put on the
     * stream and not closed.
     */
    void actuallyWrite(byte b[], int off, int len) throws IOException;

    void actuallyClose() throws IOException;
  }

  private final Impl impl;

  private State state;
  private boolean entryOpen;

  protected CustomZipOutputStream(Impl impl) {
    this.impl = impl;
    this.state = State.CLEAN;
  }

  public final void putNextEntry(ZipEntry entry) throws IOException {
    Preconditions.checkState(state != State.CLOSED, "Stream has been closed.");

    state = State.OPEN;
    closeEntry();
    validateEntry(entry);
    impl.actuallyPutNextEntry(entry);
    entryOpen = true;
  }

  private void validateEntry(ZipEntry entry) {
    if (entry.getMethod() == ZipEntry.STORED) {
      Preconditions.checkState(
          entry.getCompressedSize() == entry.getSize(),
          "STORED entry where compressed != uncompressed size");
    }
  }

  public final void closeEntry() throws IOException {
    Preconditions.checkState(state != State.CLOSED, "Stream has been closed");
    if (!entryOpen) {
      return; // As ZipOutputStream does.
    }

    entryOpen = false;
    impl.actuallyCloseEntry();
  }

  @Override
  public final void write(byte[] b, int off, int len) throws IOException {
    Preconditions.checkState(state != State.CLOSED, "Stream has been closed.");
    if (!entryOpen) {
      // Same exception as Java's ZipOutputStream.
      throw new ZipException("no current ZIP entry");
    }

    impl.actuallyWrite(b, off, len);
  }

  // javadocs taken from OutputStream and amended to make it clear what we're doing here.
  /**
   * Writes the specified byte to this output stream. Specifically one byte is written to the output
   * stream. The byte to be written is the eight low-order bits of the argument <code>b</code>. The
   * 24 high-order bits of <code>b</code> are ignored.
   *
   * @param b the <code>byte</code>.
   * @exception IOException if an I/O error occurs. In particular, an <code>IOException</code> may
   *     be thrown if the output stream has been closed.
   */
  @Override
  public void write(int b) throws IOException {
    byte[] buf = new byte[1];
    buf[0] = (byte) (b & 0xff);
    write(buf, 0, 1);
  }

  public void writeEntry(String name, InputStream contents) throws IOException {
    try {
      putNextEntry(new CustomZipEntry(name));
      ByteStreams.copy(contents, this);
      closeEntry();
    } finally {
      contents.close();
    }
  }

  @Override
  public final void close() throws IOException {
    if (state == State.CLOSED) {
      return; // no-op to call close again.
    }

    try {
      closeEntry();
      impl.actuallyClose();
    } finally {
      state = State.CLOSED;
    }
  }

  /**
   * State of a {@link CustomZipOutputStream}. Certain operations are only available when the stream
   * is in a particular state.
   */
  private enum State {
    CLEAN, // Open but no data written.
    OPEN, // Open and data written.
    CLOSED, // Just as it says on the tin.
  }
}
