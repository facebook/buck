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

import com.facebook.buck.cxx.toolchain.objectfile.ULEB128;
import java.nio.ByteBuffer;
import org.junit.Test;

public class ULEB128Test {

  @Test
  public void sizes() {
    // NB: Each byte stores 7 bits for the value and 1 bit as a continuation bit
    assertThat(ULEB128.size(0L), equalTo(1));
    assertThat(ULEB128.size((1L << 7) - 1), equalTo(1));
    assertThat(ULEB128.size(1L << 7), equalTo(2));
    assertThat(ULEB128.size((1L << 14) - 1), equalTo(2));
    assertThat(ULEB128.size(1L << 14), equalTo(3));
    // Leave MSB bit off, so use 63 bits
    assertThat(ULEB128.size((1L << 63) - 1), equalTo(9));
    assertThat(ULEB128.size(Long.MAX_VALUE), equalTo(9));
    assertThat(ULEB128.size(Long.MIN_VALUE), equalTo(10));
    assertThat(ULEB128.size(-1), equalTo(10));
  }

  private void writeReadValue(ByteBuffer bb, long value) {
    bb.rewind();
    final int bytesWritten = ULEB128.write(bb, value);

    bb.rewind();
    final int startPosition = bb.position();
    final long valueRead = ULEB128.read(bb);
    assertThat(valueRead, equalTo(value));
    final int bytesRead = bb.position() - startPosition;
    assertThat(bytesWritten, equalTo(bytesRead));
  }

  @Test
  public void writeReadDuality() {
    ByteBuffer bb = ByteBuffer.allocate(16);
    writeReadValue(bb, 0L);
    writeReadValue(bb, 1L << 7);
    writeReadValue(bb, (1L << 7) - 1);
    writeReadValue(bb, 1L << 14);
    writeReadValue(bb, (1L << 14) - 1);
    writeReadValue(bb, (1L << 21) - 1);
    writeReadValue(bb, Long.MAX_VALUE);
    writeReadValue(bb, Long.MIN_VALUE);
    writeReadValue(bb, -1);
  }

  @Test
  public void readOfPredefinedSequences() {
    // 624485 = 0xE5 0x8E 0x26
    ByteBuffer bb = ByteBuffer.allocate(8);
    int bytesWritten = ULEB128.write(bb, 624485);
    assertThat(bytesWritten, equalTo(3));
    bb.rewind();
    assertThat(bb.get(), equalTo((byte) 0xE5));
    assertThat(bb.get(), equalTo((byte) 0x8E));
    assertThat(bb.get(), equalTo((byte) 0x26));
  }

  @Test
  public void suboptimalSequences() {
    // Test that a continuation bit with all zeroes afterwards is still parsed correctly
    ByteBuffer bb = ByteBuffer.allocate(8);
    bb.put((byte) (1 << 7));

    bb.rewind();
    long value = ULEB128.read(bb);
    assertThat(value, equalTo(0L));
    // Read 2 bytes in total: continuation + terminal
    assertThat(bb.position(), equalTo(2));
  }
}
