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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

public class ForwardingProcessListener implements ListeningProcessExecutor.ProcessListener {

  private final WritableByteChannel stdout;
  private final WritableByteChannel stderr;

  public ForwardingProcessListener(WritableByteChannel stdout, WritableByteChannel stderr) {
    this.stdout = stdout;
    this.stderr = stderr;
  }

  @Override
  public void onStart(ListeningProcessExecutor.LaunchedProcess process) {}

  @Override
  public void onExit(int exitCode) {}

  @Override
  public void onStdout(ByteBuffer buffer, boolean closed) {
    try {
      stdout.write(buffer);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void onStderr(ByteBuffer buffer, boolean closed) {
    try {
      stderr.write(buffer);
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
