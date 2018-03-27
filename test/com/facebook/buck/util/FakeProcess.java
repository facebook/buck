/*
 * Copyright 2014-present Facebook, Inc.
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

import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Fake implementation of {@link java.lang.Process}. */
public class FakeProcess extends Process {
  private final int exitValue;
  private final OutputStream outputStream;
  private final ByteArrayOutputStream outputMirror;
  private final InputStream inputStream;
  private final InputStream errorStream;
  private final Optional<InterruptedException> waitForException;
  private boolean isDestroyed;
  private boolean isWaitedFor;

  public FakeProcess(int exitValue) {
    this(exitValue, "", "");
  }

  public FakeProcess(int exitValue, String stdout, String stderr) {
    this(exitValue, stdout, stderr, Optional.empty());
  }

  public FakeProcess(
      int exitValue,
      String stdout,
      String stderr,
      Optional<InterruptedException> waitForException) {
    this(
        exitValue,
        new ByteArrayOutputStream(),
        new ByteArrayInputStream(Preconditions.checkNotNull(stdout).getBytes(Charsets.UTF_8)),
        new ByteArrayInputStream(Preconditions.checkNotNull(stderr).getBytes(Charsets.UTF_8)),
        waitForException);
  }

  public FakeProcess(
      int exitValue, OutputStream outputStream, InputStream inputStream, InputStream errorStream) {
    this(exitValue, outputStream, inputStream, errorStream, Optional.empty());
  }

  public FakeProcess(
      int exitValue,
      OutputStream outputStream,
      InputStream inputStream,
      InputStream errorStream,
      Optional<InterruptedException> waitForException) {
    this.exitValue = exitValue;
    this.outputStream = Preconditions.checkNotNull(outputStream);
    this.outputMirror = new ByteArrayOutputStream();
    this.inputStream = Preconditions.checkNotNull(inputStream);
    this.errorStream = Preconditions.checkNotNull(errorStream);
    this.waitForException = waitForException;
  }

  @Override
  public void destroy() {
    isDestroyed = true;
  }

  @Override
  public int exitValue() {
    if (!isWaitedFor) {
      throw new IllegalThreadStateException();
    }
    return exitValue;
  }

  @Override
  public OutputStream getOutputStream() {
    return new TeeOutputStream(outputStream, outputMirror);
  }

  @Override
  public InputStream getInputStream() {
    return inputStream;
  }

  @Override
  public InputStream getErrorStream() {
    return errorStream;
  }

  @Override
  public int waitFor() throws InterruptedException {
    if (isWaitedFor) {
      return exitValue;
    } else {
      isWaitedFor = true;
      if (waitForException.isPresent()) {
        throw waitForException.get();
      }
      return exitValue;
    }
  }

  @Override
  public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
    waitFor();
    return true;
  }

  /** Returns true if {@link #destroy()} was called on this object, false otherwise. */
  public boolean isDestroyed() {
    return isDestroyed;
  }

  /** Returns what has been written to {@link #getOutputStream()} so far. */
  public String getOutput() {
    return outputMirror.toString();
  }
}
