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

package com.facebook.buck.cxx.toolchain.macho;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import com.facebook.buck.cxx.toolchain.objectfile.ObjectFileScrubbers;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.Test;

public class ObjectFileScrubbersTest {
  @Test
  public void testPutLittleEndianLongPositive() {
    long value = 0x123456789ABCDEF0L;
    byte[] buffer = new byte[8];
    ByteBuffer bufferWrapper = ByteBuffer.wrap(buffer);
    ObjectFileScrubbers.putLittleEndianLong(bufferWrapper, value);
    assertThat(buffer[0], equalTo((byte) 0xF0));
    assertThat(buffer[1], equalTo((byte) 0xDE));
    assertThat(buffer[2], equalTo((byte) 0xBC));
    assertThat(buffer[3], equalTo((byte) 0x9A));
    assertThat(buffer[4], equalTo((byte) 0x78));
    assertThat(buffer[5], equalTo((byte) 0x56));
    assertThat(buffer[6], equalTo((byte) 0x34));
    assertThat(buffer[7], equalTo((byte) 0x12));
  }

  @Test
  public void testPutLittleEndianIntPositive() {
    int value = 0x12345678;
    byte[] buffer = new byte[4];
    ByteBuffer bufferWrapper = ByteBuffer.wrap(buffer);
    ObjectFileScrubbers.putLittleEndianInt(bufferWrapper, value);
    assertThat(buffer[0], equalTo((byte) 0x78));
    assertThat(buffer[1], equalTo((byte) 0x56));
    assertThat(buffer[2], equalTo((byte) 0x34));
    assertThat(buffer[3], equalTo((byte) 0x12));
  }

  @Test
  public void testPutLittleEndianLongNegative() {
    long value = 0xFFEEDDCCBBAA9988L;
    byte[] buffer = new byte[8];
    ByteBuffer bufferWrapper = ByteBuffer.wrap(buffer);
    ObjectFileScrubbers.putLittleEndianLong(bufferWrapper, value);
    assertThat(buffer[0], equalTo((byte) 0x88));
    assertThat(buffer[1], equalTo((byte) 0x99));
    assertThat(buffer[2], equalTo((byte) 0xAA));
    assertThat(buffer[3], equalTo((byte) 0xBB));
    assertThat(buffer[4], equalTo((byte) 0xCC));
    assertThat(buffer[5], equalTo((byte) 0xDD));
    assertThat(buffer[6], equalTo((byte) 0xEE));
    assertThat(buffer[7], equalTo((byte) 0xFF));
  }

  @Test
  public void testPutLittleEndianIntNegative() {
    int value = 0xFEEDFACE;
    byte[] buffer = new byte[4];
    ByteBuffer bufferWrapper = ByteBuffer.wrap(buffer);
    ObjectFileScrubbers.putLittleEndianInt(bufferWrapper, value);
    assertThat(buffer[0], equalTo((byte) 0xCE));
    assertThat(buffer[1], equalTo((byte) 0xFA));
    assertThat(buffer[2], equalTo((byte) 0xED));
    assertThat(buffer[3], equalTo((byte) 0xFE));
  }

  @Test
  public void getCStringBufferNonEmptyString() {
    byte[] stringBytes = "TestString".getBytes();
    byte[] nullTermStringBytes = new byte[stringBytes.length + 1];
    System.arraycopy(stringBytes, 0, nullTermStringBytes, 0, stringBytes.length);
    nullTermStringBytes[stringBytes.length] = 0x0;

    // Check that length of returned string is equal to length of input string
    ByteBuffer cStringBuffer = ObjectFileScrubbers.getCharByteBuffer(nullTermStringBytes, 0);
    assertThat(cStringBuffer.limit(), equalTo(stringBytes.length));

    // Check that chars of returned string and input string are the same
    byte[] rawBytes = new byte[cStringBuffer.limit()];
    cStringBuffer.get(rawBytes);
    assertThat(stringBytes, equalTo(rawBytes));
  }

  @Test
  public void getCStringBufferEmptyString() {
    byte[] emptyString = new byte[] {0x0};
    ByteBuffer cStringBuffer = ObjectFileScrubbers.getCharByteBuffer(emptyString, 0);

    // Check that length of returned string is zero
    assertThat(cStringBuffer.limit(), equalTo(0));
  }

  @Test
  public void putCStringBufferNonEmptyString() {
    byte[] stringBytes = "TestString".getBytes();
    byte[] nullTermStringBytes = new byte[stringBytes.length + 1];
    ObjectFileScrubbers.putCharByteBuffer(
        ByteBuffer.wrap(nullTermStringBytes), 0, ByteBuffer.wrap(stringBytes));

    // Check string chars were written into the buffer
    assertThat(
        Arrays.copyOfRange(nullTermStringBytes, 0, stringBytes.length), equalTo(stringBytes));
    // Check that string chars are terminated by a NULL char
    assertThat(nullTermStringBytes[stringBytes.length], equalTo((byte) 0));
  }

  @Test
  public void putCStringBufferEmptyString() {
    byte[] nullTermStringBytes = new byte[1];
    ObjectFileScrubbers.putCharByteBuffer(
        ByteBuffer.wrap(nullTermStringBytes), 0, ByteBuffer.allocate(0));

    // Check that terminating NULL char exists
    assertThat(nullTermStringBytes[0], equalTo((byte) 0));
  }
}
