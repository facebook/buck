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

package com.facebook.buck.io;

import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Tests for {@link TeeInputStream}.
 */
public class TeeInputStreamTest {

  private static final String UTF_8 = StandardCharsets.UTF_8.name();

  @Test
  public void readEmpty() throws IOException {
    byte[] input = new byte[0];
    try (ByteArrayInputStream sourceStream = new ByteArrayInputStream(input);
         ByteArrayOutputStream destinationStream = new ByteArrayOutputStream();
         TeeInputStream teeStream = new TeeInputStream(sourceStream, destinationStream)) {
      assertThat(teeStream.read(), is(equalTo(-1)));
      assertThat(destinationStream.toString(UTF_8), is(emptyString()));
    }
  }

  @Test
  public void readEmptyWithBuffer() throws IOException {
    byte[] input = new byte[0];
    try (ByteArrayInputStream sourceStream = new ByteArrayInputStream(input);
         ByteArrayOutputStream destinationStream = new ByteArrayOutputStream();
         TeeInputStream teeStream = new TeeInputStream(sourceStream, destinationStream)) {
      byte[] buffer = new byte[1];
      assertThat(teeStream.read(buffer), is(equalTo(-1)));
      assertThat(destinationStream.toString(UTF_8), is(emptyString()));
    }
  }

  @Test
  public void readEmptyWithBufferAndIndexAndOffset() throws IOException {
    byte[] input = new byte[0];
    try (ByteArrayInputStream sourceStream = new ByteArrayInputStream(input);
         ByteArrayOutputStream destinationStream = new ByteArrayOutputStream();
         TeeInputStream teeStream = new TeeInputStream(sourceStream, destinationStream)) {
      byte[] buffer = new byte[1];
      assertThat(teeStream.read(buffer, 0, 1), is(equalTo(-1)));
      assertThat(destinationStream.toString(UTF_8), is(emptyString()));
    }
  }

  @Test
  public void read() throws IOException {
    byte[] input = new byte[] { 'X' };
    try (ByteArrayInputStream sourceStream = new ByteArrayInputStream(input);
         ByteArrayOutputStream destinationStream = new ByteArrayOutputStream();
         TeeInputStream teeStream = new TeeInputStream(sourceStream, destinationStream)) {
      assertThat(teeStream.read(), is(equalTo((int) 'X')));
      assertThat(destinationStream.toString(UTF_8), is("X"));
    }
  }

  @Test
  public void readWithBuffer() throws IOException {
    byte[] input = new byte[] { 'X' };
    try (ByteArrayInputStream sourceStream = new ByteArrayInputStream(input);
         ByteArrayOutputStream destinationStream = new ByteArrayOutputStream();
         TeeInputStream teeStream = new TeeInputStream(sourceStream, destinationStream)) {
      byte[] buffer = new byte[1];
      assertThat(teeStream.read(buffer), is(equalTo(1)));
      assertThat(buffer, is(equalTo(input)));
      assertThat(destinationStream.toString(UTF_8), is("X"));
    }
  }

  @Test
  public void readWithBufferAndIndexAndOffset() throws IOException {
    byte[] input = new byte[] { 'X' };
    try (ByteArrayInputStream sourceStream = new ByteArrayInputStream(input);
         ByteArrayOutputStream destinationStream = new ByteArrayOutputStream();
         TeeInputStream teeStream = new TeeInputStream(sourceStream, destinationStream)) {
      byte[] buffer = new byte[1];
      assertThat(teeStream.read(buffer, 0, 1), is(equalTo(1)));
      assertThat(buffer, is(equalTo(input)));
      assertThat(destinationStream.toString(UTF_8), is("X"));
    }
  }
}
