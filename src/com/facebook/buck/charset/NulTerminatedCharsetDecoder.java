/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.charset;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;

import java.util.Objects;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * Wrapper for {@link CharsetDecoder} to provide decoding of NUL-terminated
 * bytestrings to Unicode Strings.
 *
 * Instances of this object are not thread-safe. If you want to re-use this
 * object, make sure to synchronize access and invoke {@link #reset()} between uses.
 */
@NotThreadSafe
public class NulTerminatedCharsetDecoder {
  private final CharsetDecoder decoder;

  public static class Result {
    public final boolean nulTerminatorReached;
    public final CoderResult coderResult;

    public Result(boolean nulTerminatorReached, CoderResult coderResult) {
      this.nulTerminatorReached = nulTerminatorReached;
      this.coderResult = coderResult;
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof NulTerminatedCharsetDecoder.Result)) {
        return false;
      }

      if (other == this) {
        return true;
      }

      NulTerminatedCharsetDecoder.Result that = (NulTerminatedCharsetDecoder.Result) other;
      return
          this.nulTerminatorReached == that.nulTerminatorReached &&
          Objects.equals(this.coderResult, that.coderResult);
    }

    @Override
    public int hashCode() {
      return Objects.hash(nulTerminatorReached, coderResult);
    }

    @Override
    public String toString() {
      return String.format(
          "%s nulTerminatorReached=%s coderResult=%s",
          super.toString(),
          nulTerminatorReached,
          coderResult);
    }
  }

  public NulTerminatedCharsetDecoder(CharsetDecoder decoder) {
    this.decoder = decoder;
  }

  public static String decodeUTF8String(ByteBuffer in) throws CharacterCodingException {
    return new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder()).decodeString(in);
  }

  public String decodeString(ByteBuffer in) throws CharacterCodingException {
    int nulOffset = findNulOffset(in);
    if (nulOffset == in.limit()) {
      throw new BufferUnderflowException();
    }
    CharBuffer out = CharBuffer.allocate((int) (nulOffset * decoder.averageCharsPerByte()));
    StringBuilder sb = new StringBuilder();

    while (true) {
      Result result = decodeChunk(in, nulOffset, out, true);
      if (result.coderResult.isError()) {
        result.coderResult.throwException();
      }
      out.flip();
      sb.append(out);
      out.compact();
      if (result.nulTerminatorReached || !in.hasRemaining()) {
        break;
      }
    }

    return sb.toString();
  }

  public Result decode(ByteBuffer in, CharBuffer out, boolean endOfInput) {
    return decodeChunk(in, findNulOffset(in), out, endOfInput);
  }

  private int findNulOffset(ByteBuffer in) {
    int i;
    for (i = in.position(); i < in.limit(); i++) {
      if (in.get(i) == (byte) 0x00) {
        break;
      }
    }
    return i;
  }

  private Result decodeChunk(ByteBuffer in, int nulOffset, CharBuffer out, boolean endOfInput) {
    Result result;
    if (nulOffset == in.limit()) {
      // We didn't find a NUL terminator. Decode what we can, but tell
      // the caller we need to keep going.
      CoderResult decoderResult = decoder.decode(in, out, endOfInput);
      result = new Result(false, decoderResult);
    } else {
      // We found a NUL terminator, but we don't know if out has enough capacity
      // to hold the values up to that point.
      //
      // Temporarily limit the buffer to exclude the NUL we found,
      // decode as much as we can, and check if we made it to the NUL.
      int oldLimit = in.limit();
      in.limit(nulOffset);
      CoderResult decoderResult = decoder.decode(in, out, true /* endOfInput */);
      boolean nulTerminatorReached = !in.hasRemaining();
      result = new Result(nulTerminatorReached, decoderResult);
      in.limit(oldLimit);
      if (nulTerminatorReached) {
        // We consumed the entire buffer, so move past the NUL terminator.
        in.position(nulOffset + 1);
      }
    }

    return result;
  }

  public void reset() {
    decoder.reset();
  }
}
