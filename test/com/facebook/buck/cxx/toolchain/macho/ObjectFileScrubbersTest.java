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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.facebook.buck.cxx.toolchain.objectfile.Machos;
import com.facebook.buck.cxx.toolchain.objectfile.ObjectFileScrubbers;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
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
    byte[] stringBytes = "TestString".getBytes(Charsets.UTF_8);
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
    byte[] stringBytes = "TestString".getBytes(Charsets.UTF_8);
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

  @Test
  public void emptyReplacementValue() {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);

    Map<byte[], byte[]> map =
        Machos.generateReplacementMap(ImmutableMap.of(Paths.get("/Users/fb/repo"), Paths.get("")));
    assertThat(map.size(), equalTo(1));

    byte[] expectedSearchPrefix = "/Users/fb/repo/".getBytes(Charsets.UTF_8);
    byte[] expectedReplacementPrefix = "./".getBytes(Charsets.UTF_8);

    Map.Entry<byte[], byte[]> mapEntry = map.entrySet().iterator().next();
    assertThat(expectedSearchPrefix, equalTo(mapEntry.getKey()));
    assertThat(expectedReplacementPrefix, equalTo(mapEntry.getValue()));
  }

  @Test
  public void nonEmptyReplacementValue() {
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);

    Map<byte[], byte[]> map =
        Machos.generateReplacementMap(
            ImmutableMap.of(Paths.get("/Users/fb/repo/cell"), Paths.get("cell")));
    assertThat(map.size(), equalTo(1));

    byte[] expectedSearchPrefix = "/Users/fb/repo/cell/".getBytes(Charsets.UTF_8);
    byte[] expectedReplacementPrefix = "cell/".getBytes(Charsets.UTF_8);

    Map.Entry<byte[], byte[]> mapEntry = map.entrySet().iterator().next();
    assertThat(expectedSearchPrefix, equalTo(mapEntry.getKey()));
    assertThat(expectedReplacementPrefix, equalTo(mapEntry.getValue()));
  }

  @Test
  public void rewriteMatchingEmptyPath() {
    // Due to Unix vs Windows path separators
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);

    byte[] stringBytes = makeNullTerminatedCString("/Users/fb/repo/cell");
    Map<byte[], byte[]> replacementMap =
        Machos.generateReplacementMap(ImmutableMap.of(Paths.get("/Users/fb/repo"), Paths.get("")));

    Optional<ByteBuffer> rewrittenBuffer =
        Machos.tryRewritingMatchingPath(stringBytes, 0, replacementMap);
    assertTrue(rewrittenBuffer.isPresent());

    String rewrittenPath = new String(rewrittenBuffer.get().array());
    assertThat(rewrittenPath, equalTo("./cell"));
  }

  @Test
  public void rewriteMatchingNonEmptyPath() {
    // Due to Unix vs Windows path separators
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);

    byte[] stringBytes = makeNullTerminatedCString("/Users/fb/repo/cell/folder");
    Map<byte[], byte[]> replacementMap =
        Machos.generateReplacementMap(
            ImmutableMap.of(Paths.get("/Users/fb/repo/cell"), Paths.get("cell")));

    Optional<ByteBuffer> rewrittenBuffer =
        Machos.tryRewritingMatchingPath(stringBytes, 0, replacementMap);
    assertTrue(rewrittenBuffer.isPresent());

    String rewrittenPath = new String(rewrittenBuffer.get().array());
    assertThat(rewrittenPath, equalTo("cell/folder"));
  }

  @Test
  public void rewriteNonMatchingNonEmptyPath() {
    // Due to Unix vs Windows path separators
    assumeTrue(Platform.detect() == Platform.MACOS || Platform.detect() == Platform.LINUX);

    byte[] stringBytes = makeNullTerminatedCString("/Users/fb/repo/cell/folder");
    Map<byte[], byte[]> replacementMap =
        Machos.generateReplacementMap(
            ImmutableMap.of(Paths.get("/Users/fb/repo/cell2"), Paths.get("cell2")));

    Optional<ByteBuffer> rewrittenBuffer =
        Machos.tryRewritingMatchingPath(stringBytes, 0, replacementMap);
    assertFalse(rewrittenBuffer.isPresent());
  }

  private static byte[] makeNullTerminatedCString(String string) {
    byte[] stringBytes = string.getBytes(Charsets.UTF_8);
    byte[] nullTermStringBytes = new byte[stringBytes.length + 1];
    System.arraycopy(stringBytes, 0, nullTermStringBytes, 0, stringBytes.length);
    nullTermStringBytes[stringBytes.length] = 0x0;
    return nullTermStringBytes;
  }
}
