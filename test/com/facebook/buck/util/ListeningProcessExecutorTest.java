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

import static org.junit.Assert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.util.environment.Platform;

import org.junit.Rule;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Tests for {@link ListeningProcessExecutor}.
 */
public class ListeningProcessExecutorTest {

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  private class CapturingListener implements ListeningProcessExecutor.ProcessListener {
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

  private class StdinWritingListener extends CapturingListener {
    private final ByteBuffer bufferToWrite;

    public StdinWritingListener(String string) {
      bufferToWrite = ByteBuffer.wrap(string.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean onStdinReady(ByteBuffer buffer) {
      if (!bufferToWrite.hasRemaining()) {
        process.closeStdin();
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

  @Test
  public void echoTextReceivedOnStdout() throws Exception {
    ListeningProcessExecutor executor = new ListeningProcessExecutor();
    CapturingListener listener = new CapturingListener();
    ProcessExecutorParams params;
    if (Platform.detect() == Platform.WINDOWS) {
      params = ProcessExecutorParams.ofCommand("cmd.exe", "/c", "echo", "Hello");
    } else {
      params = ProcessExecutorParams.ofCommand("echo", "Hello");
    }
    ListeningProcessExecutor.LaunchedProcess process = executor.launchProcess(
        params,
        listener);
    int returnCode = executor.waitForProcess(process, Long.MAX_VALUE, TimeUnit.SECONDS);
    assertThat(returnCode, equalTo(0));
    assertThat(listener.capturedStdout.toString("UTF-8"), equalTo(String.format("Hello%n")));
    assertThat(listener.capturedStderr.toString("UTF-8"), is(emptyString()));
  }

  @Test
  public void processCwdIsRespected() throws Exception {
    ProcessExecutorParams.Builder paramsBuilder = ProcessExecutorParams.builder();
    if (Platform.detect() == Platform.WINDOWS) {
      paramsBuilder.addCommand("cmd.exe", "/c", "type");
    } else {
      paramsBuilder.addCommand("cat");
    }
    paramsBuilder.addCommand("hello-world.txt");
    paramsBuilder.setDirectory(tmp.getRoot().toFile());
    Path helloWorldPath = tmp.getRoot().resolve("hello-world.txt");
    String fileContents = "Hello, world!";
    Files.write(helloWorldPath, fileContents.getBytes(StandardCharsets.UTF_8));
    ListeningProcessExecutor executor = new ListeningProcessExecutor();
    CapturingListener listener = new CapturingListener();
    ListeningProcessExecutor.LaunchedProcess process = executor.launchProcess(
        paramsBuilder.build(),
        listener);
    int returnCode = executor.waitForProcess(process, Long.MAX_VALUE, TimeUnit.SECONDS);
    assertThat(returnCode, equalTo(0));
    assertThat(listener.capturedStdout.toString("UTF-8"), equalTo(fileContents));
    assertThat(listener.capturedStderr.toString("UTF-8"), is(emptyString()));
  }

  @Test
  public void catTextSentToStdinReceivedOnStdout() throws Exception {
    ProcessExecutorParams params;
    if (Platform.detect() == Platform.WINDOWS) {
      params = ProcessExecutorParams.ofCommand(
          "python",
          "-c",
          "import sys, shutil; shutil.copyfileobj(sys.stdin, sys.stdout)");
    } else {
      params = ProcessExecutorParams.ofCommand("cat");
    }
    ListeningProcessExecutor executor = new ListeningProcessExecutor();
    StdinWritingListener listener = new StdinWritingListener(String.format("Meow%n"));
    ListeningProcessExecutor.LaunchedProcess process = executor.launchProcess(
        params,
        listener);
    process.wantWrite();
    int returnCode = executor.waitForProcess(process, Long.MAX_VALUE, TimeUnit.SECONDS);
    assertThat(returnCode, equalTo(0));
    assertThat(listener.capturedStdout.toString("UTF-8"), equalTo(String.format("Meow%n")));
    assertThat(listener.capturedStderr.toString("UTF-8"), is(emptyString()));
  }

  @Test
  public void catMoreTextThanFitsInSingleBufferReceivedOnStdout() throws Exception {
    ProcessExecutorParams params;
    if (Platform.detect() == Platform.WINDOWS) {
      params = ProcessExecutorParams.ofCommand(
          "python",
          "-c",
          "import sys, shutil; shutil.copyfileobj(sys.stdin, sys.stdout)");
    } else {
      params = ProcessExecutorParams.ofCommand("cat");
    }
    ListeningProcessExecutor executor = new ListeningProcessExecutor();
    StringBuilder sb = new StringBuilder();
    // Use a 3 byte Unicode sequence to ensure writes go across byte buffer
    // boundaries, and append it as many times as needed to ensure it doesn't
    // fit in a single I/O buffer.
    String threeByteUTF8 = "\u2764";
    for (int i = 0; i < ListeningProcessExecutor.LaunchedProcess.BUFFER_CAPACITY + 1; i++) {
      sb.append(threeByteUTF8);
    }
    sb.append(String.format("%n"));
    String longString = sb.toString();
    StdinWritingListener listener = new StdinWritingListener(longString);
    ListeningProcessExecutor.LaunchedProcess process = executor.launchProcess(
        params,
        listener);
    process.wantWrite();
    int returnCode = executor.waitForProcess(process, Long.MAX_VALUE, TimeUnit.SECONDS);
    assertThat(returnCode, equalTo(0));
    assertThat(listener.capturedStdout.toString("UTF-8"), equalTo(longString));
    assertThat(listener.capturedStderr.toString("UTF-8"), is(emptyString()));
  }

  @Test
  public void processFailureExitCodeNotZero() throws Exception {
    ProcessExecutorParams params;
    if (Platform.detect() == Platform.WINDOWS) {
      params = ProcessExecutorParams.ofCommand("cmd.exe", "/c", "exit", "1");
    } else {
      params = ProcessExecutorParams.ofCommand("false");
    }
    ListeningProcessExecutor executor = new ListeningProcessExecutor();
    CapturingListener listener = new CapturingListener();
    ListeningProcessExecutor.LaunchedProcess process = executor.launchProcess(
        params,
        listener);
    int returnCode = executor.waitForProcess(process, Long.MAX_VALUE, TimeUnit.SECONDS);
    assertThat(returnCode, not(equalTo(0)));
    assertThat(listener.capturedStdout.toString("UTF-8"), is(emptyString()));
    assertThat(listener.capturedStderr.toString("UTF-8"), is(emptyString()));
  }

  @Test
  public void nonExistentBinaryExitCodeNotZero() throws Exception {
    ListeningProcessExecutor executor = new ListeningProcessExecutor();
    CapturingListener listener = new CapturingListener();
    ListeningProcessExecutor.LaunchedProcess process = executor.launchProcess(
        ProcessExecutorParams.ofCommand("this-better-not-be-a-process-on-your-system-for-real"),
        listener);
    int returnCode = executor.waitForProcess(process, Long.MAX_VALUE, TimeUnit.SECONDS);
    assertThat(returnCode, not(equalTo(0)));
    assertThat(listener.capturedStdout.toString("UTF-8"), is(emptyString()));
    assertThat(listener.capturedStderr.toString("UTF-8"), is(emptyString()));
  }

  @Test
  public void waitForProcessReturnsMinIntegerOnTimeout() throws Exception {
    ListeningProcessExecutor executor = new ListeningProcessExecutor();
    CapturingListener listener = new CapturingListener();
    ProcessExecutorParams params;
    if (Platform.detect() == Platform.WINDOWS) {
      params = ProcessExecutorParams.ofCommand("python", "-c", "import time; time.sleep(50)");
    } else {
      params = ProcessExecutorParams.ofCommand("sleep", "50");
    }
    ListeningProcessExecutor.LaunchedProcess process = executor.launchProcess(
        params,
        listener);
    int returnCode = executor.waitForProcess(process, 100, TimeUnit.MILLISECONDS);
    assertThat(returnCode, equalTo(Integer.MIN_VALUE));
    assertThat(listener.capturedStdout.toString("UTF-8"), is(emptyString()));
    assertThat(listener.capturedStderr.toString("UTF-8"), is(emptyString()));
    executor.destroyProcess(process, /* force */ true);
    executor.waitForProcess(process, 0, TimeUnit.MILLISECONDS);
  }
}
