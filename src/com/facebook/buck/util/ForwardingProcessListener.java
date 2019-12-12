/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;

public class ForwardingProcessListener implements ListeningProcessExecutor.ProcessListener {

  private final OutputStream stdout;
  private final OutputStream stderr;
  private final WritableByteChannel stdoutChannel;
  private final WritableByteChannel stderrChannel;

  public ForwardingProcessListener(OutputStream stdout, OutputStream stderr) {
    this.stdout = stdout;
    this.stderr = stderr;
    this.stdoutChannel = Channels.newChannel(stdout);
    this.stderrChannel = Channels.newChannel(stderr);
  }

  @Override
  public void onStart(ListeningProcessExecutor.LaunchedProcess process) {
    process.closeStdin(false);
  }

  @Override
  public void onExit(int exitCode) {}

  @Override
  public void onStdout(ByteBuffer buffer, boolean closed) {
    try {
      stdoutChannel.write(buffer);
      stdout.flush();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void onStderr(ByteBuffer buffer, boolean closed) {
    try {
      stderrChannel.write(buffer);
      stderr.flush();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public boolean onStdinReady(ByteBuffer buffer) {
    buffer.flip();
    return false;
  }
}
