/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.util.nio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Random;
import org.junit.Test;

public class LargeByteBufferTest {
  private interface BufferGetterByPosition<T> {
    T get(LargeByteBuffer buffer) throws IOException;
  }

  private interface BufferPutterByPosition<T> {
    void put(LargeByteBuffer buffer, T value) throws IOException;
  }

  private interface BufferGetterByIndex<T> {
    T get(LargeByteBuffer buffer, long index) throws IOException;
  }

  private interface BufferPutterByIndex<T> {
    void put(LargeByteBuffer buffer, long index, T value) throws IOException;
  }

  @Test
  public void testByteByPosition() throws Exception {
    testGetAndPutByPosition(
        (buffer, value) -> buffer.put(value), buffer -> buffer.get(), 1, (byte) 0x01);
  }

  @Test
  public void testShortByPosition() throws Exception {
    testGetAndPutByPosition(
        (buffer, value) -> buffer.putShort(value), buffer -> buffer.getShort(), 4, (short) 0x0123);
  }

  @Test
  public void testIntByPosition() throws Exception {
    testGetAndPutByPosition(
        (buffer, value) -> buffer.putInt(value), buffer -> buffer.getInt(), 4, 0x01234567);
  }

  @Test
  public void testLongByPosition() throws Exception {
    testGetAndPutByPosition(
        (buffer, value) -> buffer.putLong(value),
        buffer -> buffer.getLong(),
        8,
        0x0123456789abcdefL);
  }

  @Test
  public void testByteByIndex() throws Exception {
    testGetAndPutByIndex(
        (buffer, index, value) -> buffer.put(index, value),
        (buffer, index) -> buffer.get(index),
        1,
        (byte) 0x01);
  }

  @Test
  public void testShortByIndex() throws Exception {
    testGetAndPutByIndex(
        (buffer, index, value) -> buffer.putShort(index, value),
        (buffer, index) -> buffer.getShort(index),
        4,
        (short) 0x0123);
  }

  @Test
  public void testIntByIndex() throws Exception {
    testGetAndPutByIndex(
        (buffer, index, value) -> buffer.putInt(index, value),
        (buffer, index) -> buffer.getInt(index),
        4,
        0x01234567);
  }

  @Test
  public void testLongByIndex() throws Exception {
    testGetAndPutByIndex(
        (buffer, index, value) -> buffer.putLong(index, value),
        (buffer, index) -> buffer.getLong(index),
        8,
        0x0123456789abcdefL);
  }

  @Test
  public <T> void testBytesByPosition() throws Exception {
    byte[] value = new byte[] {0x01, 0x23, 0x45};

    int position = new Random().nextInt(10) + 2;
    byte[] data = new byte[position + value.length * 10];

    LargeByteBuffer out = LargeByteBuffer.withBytes(data);
    out.position(position);
    for (int i = 0; i < 10; i++) {
      out.put(value);
    }

    // Make sure data was written, but not to the first byte.
    assertEquals(0, data[0]);
    assertNotSame(0, data[position]);

    // Make sure the data is readable.
    byte[] temp = new byte[value.length];

    LargeByteBuffer in = LargeByteBuffer.withBytes(data);
    in.position(position);
    for (int i = 0; i < 10; i++) {
      in.get(temp);
      assertArrayEquals(value, temp);
    }
  }

  private static <T> void testGetAndPutByPosition(
      BufferPutterByPosition<T> putter, BufferGetterByPosition<T> getter, int size, T value)
      throws Exception {
    int position = new Random().nextInt(10) + 2;
    byte[] data = new byte[position + size * 10];

    LargeByteBuffer out = LargeByteBuffer.withBytes(data);
    out.position(position).slice();
    for (int i = 0; i < 10; i++) {
      putter.put(out, value);
    }

    // Make sure data was written, but not to the first byte.
    assertEquals(0, data[0]);
    assertNotSame(0, data[position]);

    // Make sure the data is readable.
    LargeByteBuffer in = LargeByteBuffer.withBytes(data);
    in.position(position);
    for (int i = 0; i < 10; i++) {
      assertEquals(value, getter.get(in));
    }
  }

  private static <T> void testGetAndPutByIndex(
      BufferPutterByIndex<T> putter, BufferGetterByIndex<T> getter, int size, T value)
      throws Exception {
    int position = new Random().nextInt(10) + 2;
    byte[] data = new byte[position + size * 10];

    LargeByteBuffer out = LargeByteBuffer.withBytes(data);
    for (int i = 0; i < 10; i++) {
      int index = (i * 3) % 10; // Use a less predictable order.
      putter.put(out, position + size * index, value);
    }

    // Make sure data was written, but not to the first byte.
    assertEquals(0, out.position());
    assertEquals(0, data[0]);
    assertNotSame(0, data[position]);

    // Make sure the data is readable.
    LargeByteBuffer in = LargeByteBuffer.withBytes(data);
    for (int i = 0; i < 10; i++) {
      int index = (i * 7) % 10; // Use a different, less predictable order.
      assertEquals(value, getter.get(in, position + size * index));
    }
  }

  @Test
  public void testMultipleSlices() throws Exception {
    LargeByteBuffer buffer =
        LargeByteBuffer.withBytes(new byte[] {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08});

    LargeByteBuffer slice1 = buffer.slice(2).slice(1).slice(0).slice(2);
    LargeByteBuffer slice2 = buffer.slice(2).slice(1).slice(0).slice(2);
    LargeByteBuffer slice3 = buffer.slice(0);

    assertEquals((byte) 0x06, slice1.get());
    assertEquals((byte) 0x01, slice3.get());
    assertEquals((byte) 0x01, buffer.get());
    assertEquals((byte) 0x02, slice3.get());
    assertEquals((byte) 0x07, slice1.get());
    assertEquals((byte) 0x06, slice2.get());
  }

  @Test
  public void testInitialState() throws Exception {
    LargeByteBuffer buffer =
        LargeByteBuffer.withBytes(new byte[] {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08});

    assertEquals(0, buffer.position());
    assertEquals(8, buffer.limit());
    assertEquals(8, buffer.remaining());
    assertTrue(buffer.hasRemaining());
    assertEquals((byte) 0x01, buffer.get());
  }

  @Test
  public void testSlice() throws Exception {
    LargeByteBuffer buffer =
        LargeByteBuffer.withBytes(new byte[] {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08})
            .slice(2);

    assertEquals(0, buffer.position());
    assertEquals(6, buffer.limit());
    assertEquals(6, buffer.remaining());
    assertTrue(buffer.hasRemaining());
    assertEquals((byte) 0x03, buffer.get());
  }

  @Test
  public void testSliceAndPosition() throws Exception {
    LargeByteBuffer buffer =
        LargeByteBuffer.withBytes(new byte[] {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08})
            .slice(2)
            .position(2);

    assertEquals(2, buffer.position());
    assertEquals(6, buffer.limit());
    assertEquals(4, buffer.remaining());
    assertTrue(buffer.hasRemaining());
    assertEquals((byte) 0x05, buffer.get());
  }

  @Test
  public void testLimit() throws Exception {
    LargeByteBuffer buffer =
        LargeByteBuffer.withBytes(new byte[] {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08})
            .slice(2)
            .position(2)
            .limit(5);

    assertEquals(2, buffer.position());
    assertEquals(5, buffer.limit());
    assertEquals(3, buffer.remaining());
    assertTrue(buffer.hasRemaining());
    assertEquals((byte) 0x05, buffer.get());
  }

  public void testOutOfBoundsWithoutRead() throws Exception {
    LargeByteBuffer buffer =
        LargeByteBuffer.withBytes(new byte[] {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08})
            .slice(2)
            .position(2)
            .limit(5)
            .position(17);

    assertEquals(17, buffer.position());
    assertEquals(5, buffer.limit());
    assertEquals(-12, buffer.remaining());
    assertFalse(buffer.hasRemaining());
  }

  @Test(expected = IndexOutOfBoundsException.class)
  public void testOutOfBoundsWithRead() throws Exception {
    LargeByteBuffer buffer =
        LargeByteBuffer.withBytes(new byte[] {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08})
            .slice(2)
            .position(2)
            .limit(5)
            .position(17);

    buffer.get();
  }

  @Test
  public void testRewind() throws Exception {
    LargeByteBuffer buffer =
        LargeByteBuffer.withBytes(new byte[] {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08})
            .slice(2)
            .position(2)
            .limit(5)
            .position(17)
            .rewind();

    assertEquals(0, buffer.position());
    assertEquals(5, buffer.limit());
    assertEquals(5, buffer.remaining());
    assertTrue(buffer.hasRemaining());
    assertEquals((byte) 0x03, buffer.get());
  }
}
