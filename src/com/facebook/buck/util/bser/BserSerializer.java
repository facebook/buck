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

package com.facebook.buck.util.bser;

import static com.facebook.buck.util.bser.BserConstants.BSER_ARRAY;
import static com.facebook.buck.util.bser.BserConstants.BSER_FALSE;
import static com.facebook.buck.util.bser.BserConstants.BSER_INT16;
import static com.facebook.buck.util.bser.BserConstants.BSER_INT32;
import static com.facebook.buck.util.bser.BserConstants.BSER_INT64;
import static com.facebook.buck.util.bser.BserConstants.BSER_INT8;
import static com.facebook.buck.util.bser.BserConstants.BSER_NULL;
import static com.facebook.buck.util.bser.BserConstants.BSER_OBJECT;
import static com.facebook.buck.util.bser.BserConstants.BSER_REAL;
import static com.facebook.buck.util.bser.BserConstants.BSER_STRING;
import static com.facebook.buck.util.bser.BserConstants.BSER_TRUE;

import com.google.common.collect.Iterables;
import com.google.common.io.BaseEncoding;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Encoder for the BSER binary JSON format used by the Watchman service:
 *
 * <p>https://facebook.github.io/watchman/docs/bser.html
 */
public class BserSerializer {
  private static final int INITIAL_BUFFER_SIZE = 8192;
  private static final byte[] EMPTY_HEADER = BaseEncoding.base16().decode("00010500000000");

  private enum BserIntegralEncodedSize {
    INT8(1),
    INT16(2),
    INT32(4),
    INT64(8);

    public final int size;

    BserIntegralEncodedSize(int size) {
      this.size = size;
    }
  }

  private final CharsetEncoder utf8Encoder;

  public BserSerializer() {
    this.utf8Encoder =
        StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT);
  }

  /** Serializes an object using BSER encoding to the stream. */
  public void serializeToStream(Object value, OutputStream outputStream) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(INITIAL_BUFFER_SIZE).order(ByteOrder.nativeOrder());

    buffer = serializeToBuffer(value, buffer);
    buffer.flip();

    outputStream.write(buffer.array(), 0, buffer.limit());
  }

  /**
   * Serializes an object using BSER encoding. If possible, writes the object to the provided byte
   * buffer and returns it. If the buffer is not big enough to hold the object, returns a new
   * buffer.
   *
   * <p>After returning, buffer.position() is advanced past the last encoded byte.
   */
  public ByteBuffer serializeToBuffer(Object value, ByteBuffer buffer) throws IOException {
    buffer = increaseBufferCapacityIfNeeded(buffer, EMPTY_HEADER.length);
    buffer.put(EMPTY_HEADER);
    buffer = appendRecursive(buffer, value, utf8Encoder);

    int encodedLength = buffer.position() - EMPTY_HEADER.length;

    // Overwrite the 32-bit length field at position 3 with the actual length of the object.
    buffer.putInt(3, encodedLength);

    return buffer;
  }

  @SuppressWarnings("unchecked")
  private static ByteBuffer appendRecursive(
      ByteBuffer buffer, Object value, CharsetEncoder utf8Encoder) throws IOException {
    if (value instanceof Boolean) {
      buffer = increaseBufferCapacityIfNeeded(buffer, 1);
      buffer.put(((boolean) value) ? BSER_TRUE : BSER_FALSE);
    } else if (value == null) {
      buffer = increaseBufferCapacityIfNeeded(buffer, 1);
      buffer.put(BSER_NULL);
    } else if (value instanceof String) {
      buffer = appendString(buffer, (String) value, utf8Encoder);
    } else if (value instanceof Double || value instanceof Float) {
      buffer = increaseBufferCapacityIfNeeded(buffer, 9);
      buffer.put(BSER_REAL);
      buffer.putDouble((double) value);
    } else if (value instanceof Long) {
      buffer = appendLong(buffer, (long) value);
    } else if (value instanceof Integer) {
      buffer = appendLong(buffer, (int) value);
    } else if (value instanceof Short) {
      buffer = appendLong(buffer, (short) value);
    } else if (value instanceof Byte) {
      buffer = appendLong(buffer, (byte) value);
    } else if (value instanceof Map<?, ?>) {
      Map<Object, Object> map = (Map<Object, Object>) value;
      int mapLen = map.size();
      BserIntegralEncodedSize encodedSize = getEncodedSize(mapLen);
      buffer = increaseBufferCapacityIfNeeded(buffer, 2 + encodedSize.size);
      buffer.put(BSER_OBJECT);
      buffer = appendLongWithSize(buffer, mapLen, encodedSize);
      for (Map.Entry<Object, Object> entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String)) {
          throw new IOException(
              String.format(
                  "Unrecognized map key type %s, expected string", entry.getKey().getClass()));
        }
        buffer = appendString(buffer, (String) entry.getKey(), utf8Encoder);
        buffer = appendRecursive(buffer, entry.getValue(), utf8Encoder);
      }
    } else if (value instanceof Iterable<?>) {
      Iterable<Object> iterable = (Iterable<Object>) value;
      int len = Iterables.size(iterable);
      BserIntegralEncodedSize encodedSize = getEncodedSize(len);
      buffer = increaseBufferCapacityIfNeeded(buffer, 2 + encodedSize.size);
      buffer.put(BSER_ARRAY);
      buffer = appendLongWithSize(buffer, len, encodedSize);
      for (Object obj : iterable) {
        buffer = appendRecursive(buffer, obj, utf8Encoder);
      }
    } else {
      throw new RuntimeException("Cannot encode object: " + value);
    }

    return buffer;
  }

  private static ByteBuffer appendString(
      ByteBuffer buffer, String value, CharsetEncoder utf8Encoder) throws CharacterCodingException {
    CharBuffer valueBuffer = CharBuffer.wrap(value);
    ByteBuffer utf8String = utf8Encoder.encode(valueBuffer);
    int utf8StringLenBytes = utf8String.remaining();
    BserIntegralEncodedSize utf8StringLenSize = getEncodedSize(utf8StringLenBytes);
    buffer =
        increaseBufferCapacityIfNeeded(buffer, 2 + utf8StringLenSize.size + utf8StringLenBytes);
    buffer.put(BSER_STRING);
    buffer = appendLongWithSize(buffer, utf8StringLenBytes, utf8StringLenSize);
    buffer.put(utf8String);
    return buffer;
  }

  private static ByteBuffer appendLong(ByteBuffer buffer, long value) {
    BserIntegralEncodedSize encodedSize = getEncodedSize(value);
    buffer = increaseBufferCapacityIfNeeded(buffer, 1 + encodedSize.size);
    return appendLongWithSize(buffer, value, encodedSize);
  }

  private static BserIntegralEncodedSize getEncodedSize(long value) {
    if (value >= -0x80 && value <= 0x7F) {
      return BserIntegralEncodedSize.INT8;
    } else if (value >= -0x8000 && value <= 0x7FFF) {
      return BserIntegralEncodedSize.INT16;
    } else if (value >= -0x80000000 && value <= 0x7FFFFFFF) {
      return BserIntegralEncodedSize.INT32;
    } else if (value >= -0x8000000000000000L && value <= 0x7FFFFFFFFFFFFFFFL) {
      return BserIntegralEncodedSize.INT64;
    } else {
      // We shouldn't be able to reach here.
      throw new RuntimeException("Unhandled long value: " + value);
    }
  }

  private static ByteBuffer appendLongWithSize(
      ByteBuffer buffer, long value, BserIntegralEncodedSize encodedSize) {
    // We assume we've already increased the size of the buffer to hold
    // the encoded size.
    switch (encodedSize) {
      case INT8:
        buffer.put(BSER_INT8);
        buffer.put((byte) value);
        break;
      case INT16:
        buffer.put(BSER_INT16);
        buffer.putShort((short) value);
        break;
      case INT32:
        buffer.put(BSER_INT32);
        buffer.putInt((int) value);
        break;
      case INT64:
        buffer.put(BSER_INT64);
        buffer.putLong(value);
        break;
    }
    return buffer;
  }

  private static ByteBuffer increaseBufferCapacityIfNeeded(ByteBuffer buffer, int amount) {
    int remaining = buffer.remaining();
    if (remaining < amount) {
      int capacity = buffer.capacity();
      while (remaining < amount) {
        remaining += capacity;
        capacity *= 2;
      }
      buffer = resizeBufferWithCapacity(buffer, capacity);
    }
    return buffer;
  }

  private static ByteBuffer resizeBufferWithCapacity(ByteBuffer buffer, int capacity) {
    buffer.flip();
    return ByteBuffer.allocate(capacity).order(buffer.order()).put(buffer);
  }
}
