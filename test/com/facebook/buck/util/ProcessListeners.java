/*
 * Copyright 2015-present Facebook, Inc.
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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public interface ProcessListeners {
  class CapturingListener implements ListeningProcessExecutor.ProcessListener {
    protected ListeningProcessExecutor.LaunchedProcess process;
    public ByteArrayOutputStream capturedStdout = new ByteArrayOutputStream();
    public ByteArrayOutputStream capturedStderr = new ByteArrayOutputStream();

    @Override
    public void onStart(ListeningProcessExecutor.LaunchedProcess process) {
      this.process = process;
    }

    @Override
    public void onExit(int exitCode) {
    }

    @Override
    public void onStdout(ByteBuffer buffer, boolean closed) {
      writeBufferToStream(buffer, capturedStdout);
    }

    @Override
    public void onStderr(ByteBuffer buffer, boolean closed) {
      writeBufferToStream(buffer, capturedStderr);
    }

    private final void writeBufferToStream(ByteBuffer buffer, ByteArrayOutputStream stream) {
      if (buffer.hasArray()) {
        stream.write(buffer.array(), buffer.position(), buffer.remaining());
        buffer.position(buffer.limit());
      } else {
        byte[] bufferBytes = new byte[buffer.remaining()];
        // This updates buffer.position().
        buffer.get(bufferBytes);
        stream.write(bufferBytes, 0, bufferBytes.length);
      }
    }

    @Override
    public boolean onStdinReady(ByteBuffer buffer) {
      return false;
    }
  }

  class StdinWritingListener extends CapturingListener {
    private final ByteBuffer bufferToWrite;

    public StdinWritingListener(String string) {
      bufferToWrite = ByteBuffer.wrap(string.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean onStdinReady(ByteBuffer buffer) {
      if (!bufferToWrite.hasRemaining()) {
        process.closeStdin(true);
        buffer.flip();
        return false;
      }

      if (buffer.remaining() >= bufferToWrite.remaining()) {
        // All our data fits in the buffer.
        buffer.put(bufferToWrite);
      } else {
        // Not all our data fits in the buffer. Copy as much as we can,
        // then indicate we have more data to write.
        ByteBuffer subBuffer = bufferToWrite.slice();
        subBuffer.limit(buffer.remaining());
        buffer.put(subBuffer);
        bufferToWrite.position(bufferToWrite.position() + subBuffer.limit());
      }
      buffer.flip();
      return true;
    }
  }
}
