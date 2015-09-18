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

import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Optional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractFakeListeningProcessState {
  public enum Type {
      EXPECT_STDIN,
      EXPECT_STDIN_CLOSED,
      STDOUT,
      STDERR,
      WAIT,
      EXIT
  }

  public static FakeListeningProcessState ofExpectedStdin(String expectedStdin) {
    return FakeListeningProcessState.builder()
        .setType(Type.EXPECT_STDIN)
        .setExpectedStdin(StandardCharsets.UTF_8.encode(expectedStdin))
        .build();
  }

  public static FakeListeningProcessState ofExpectStdinClosed() {
    return FakeListeningProcessState.builder()
        .setType(Type.EXPECT_STDIN_CLOSED)
        .build();
  }

  public static FakeListeningProcessState ofStdout(String stdout) {
    return FakeListeningProcessState.builder()
        .setType(Type.STDOUT)
        .setStdout(StandardCharsets.UTF_8.encode(stdout))
        .build();
  }

  public static FakeListeningProcessState ofStderr(String stderr) {
    return FakeListeningProcessState.builder()
        .setType(Type.STDERR)
        .setStdout(StandardCharsets.UTF_8.encode(stderr))
        .build();
  }

  public static FakeListeningProcessState ofWaitNanos(long waitNanos) {
    return FakeListeningProcessState.builder()
        .setType(Type.WAIT)
        .setWaitNanos(waitNanos)
        .build();
  }

  public static FakeListeningProcessState ofExit(int exitCode) {
    return FakeListeningProcessState.builder()
        .setType(Type.EXIT)
        .setExitCode(exitCode)
        .build();
  }

  public abstract Type getType();

  public abstract Optional<ByteBuffer> getExpectedStdin();
  public abstract Optional<ByteBuffer> getStdout();
  public abstract Optional<ByteBuffer> getStderr();
  public abstract Optional<Long> getWaitNanos();
  public abstract Optional<Integer> getExitCode();
}
