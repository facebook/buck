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

package com.facebook.buck.util.charset;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.google.common.io.BaseEncoding;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class NulTerminatedCharsetDecoderTest {
  @Rule public ExpectedException thrown = ExpectedException.none();

  @Test
  public void nonNulTerminatedEmptyBufferDecodesWithUnderflow() {
    NulTerminatedCharsetDecoder decoder =
        new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder());
    ByteBuffer in = ByteBuffer.allocate(0);
    CharBuffer out = CharBuffer.allocate(0);
    assertThat(in.position(), is(equalTo(0)));
    assertThat(in.limit(), is(equalTo(0)));
    assertThat(out.position(), is(equalTo(0)));
    assertThat(out.limit(), is(equalTo(0)));
    NulTerminatedCharsetDecoder.Result result = decoder.decode(in, out, true);
    assertThat(in.position(), is(equalTo(0)));
    assertThat(in.limit(), is(equalTo(0)));
    assertThat(out.position(), is(equalTo(0)));
    assertThat(out.limit(), is(equalTo(0)));
    assertThat(
        result, is(equalTo(new NulTerminatedCharsetDecoder.Result(false, CoderResult.UNDERFLOW))));
  }

  @Test
  public void nulTerminatedEmptyBufferDecodesToEmptyBuffer() {
    NulTerminatedCharsetDecoder decoder =
        new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder());
    ByteBuffer in = decodeHex("00");
    CharBuffer out = CharBuffer.allocate(0);
    assertThat(in.position(), is(equalTo(0)));
    assertThat(in.limit(), is(equalTo(1)));
    assertThat(out.position(), is(equalTo(0)));
    assertThat(out.limit(), is(equalTo(0)));
    NulTerminatedCharsetDecoder.Result result = decoder.decode(in, out, true);
    assertThat(in.position(), is(equalTo(1)));
    assertThat(in.limit(), is(equalTo(1)));
    assertThat(out.position(), is(equalTo(0)));
    assertThat(out.limit(), is(equalTo(0)));
    assertThat(
        result, is(equalTo(new NulTerminatedCharsetDecoder.Result(true, CoderResult.UNDERFLOW))));
  }

  @Test
  public void nulTerminatedBufferDecodesContentsToBuffer() {
    NulTerminatedCharsetDecoder decoder =
        new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder());
    ByteBuffer in = decodeHex("F09F92A900"); // U+1F4A9 in UTF-8
    CharBuffer out = CharBuffer.allocate(2);
    assertThat(in.position(), is(equalTo(0)));
    assertThat(in.limit(), is(equalTo(5)));
    assertThat(out.position(), is(equalTo(0)));
    assertThat(out.limit(), is(equalTo(2)));
    NulTerminatedCharsetDecoder.Result result = decoder.decode(in, out, true);
    assertThat(
        result, is(equalTo(new NulTerminatedCharsetDecoder.Result(true, CoderResult.UNDERFLOW))));
    assertThat(in.position(), is(equalTo(5)));
    assertThat(in.limit(), is(equalTo(5)));
    assertThat(out.position(), is(equalTo(2)));
    assertThat(out.limit(), is(equalTo(2)));

    out.flip();
    assertThat(out.toString(), is(equalTo("\uD83D\uDCA9"))); // U+1F4A9 in Java
  }

  @Test
  public void splittingCodePointAcrossByteBuffersDecodesContentToBuffer() {
    NulTerminatedCharsetDecoder decoder =
        new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder());
    ByteBuffer in = decodeHex("F09F92A900"); // U+1F4A9 in UTF-8

    // first half of U+1F4A9 in UTF-8
    in.limit(2);

    CharBuffer out = CharBuffer.allocate(2);
    assertThat(in.position(), is(equalTo(0)));
    assertThat(in.limit(), is(equalTo(2)));
    assertThat(out.position(), is(equalTo(0)));
    assertThat(out.limit(), is(equalTo(2)));
    NulTerminatedCharsetDecoder.Result firstHalfResult = decoder.decode(in, out, false);
    assertThat(
        firstHalfResult,
        is(equalTo(new NulTerminatedCharsetDecoder.Result(false, CoderResult.UNDERFLOW))));
    assertThat(in.position(), is(equalTo(0)));
    assertThat(in.limit(), is(equalTo(2)));
    assertThat(out.position(), is(equalTo(0)));
    assertThat(out.limit(), is(equalTo(2)));

    // second half of U+1F4A9 in UTF-8
    in.limit(5);

    NulTerminatedCharsetDecoder.Result secondHalfResult = decoder.decode(in, out, true);
    assertThat(
        secondHalfResult,
        is(equalTo(new NulTerminatedCharsetDecoder.Result(true, CoderResult.UNDERFLOW))));
    assertThat(in.position(), is(equalTo(5)));
    assertThat(in.limit(), is(equalTo(5)));
    assertThat(out.position(), is(equalTo(2)));
    assertThat(out.limit(), is(equalTo(2)));

    out.flip();
    assertThat(out.toString(), is(equalTo("\uD83D\uDCA9"))); // U+1F4A9 in Java
  }

  @Test
  public void tooSmallCharBufferResultsInOverflow() {
    NulTerminatedCharsetDecoder decoder =
        new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder());
    ByteBuffer in = decodeHex("F09F92A900"); // U+1F4A9 in UTF-8
    CharBuffer out = CharBuffer.allocate(1); // Too small to hold U+1F419 (need 2 UTF-16 code units)
    assertThat(in.position(), is(equalTo(0)));
    assertThat(in.limit(), is(equalTo(5)));
    assertThat(out.position(), is(equalTo(0)));
    assertThat(out.limit(), is(equalTo(1)));
    NulTerminatedCharsetDecoder.Result result = decoder.decode(in, out, true);
    assertThat(
        result, is(equalTo(new NulTerminatedCharsetDecoder.Result(false, CoderResult.OVERFLOW))));
    assertThat(in.position(), is(equalTo(0)));
    assertThat(in.limit(), is(equalTo(5)));
    assertThat(out.position(), is(equalTo(0)));
    assertThat(out.limit(), is(equalTo(1)));
  }

  @Test
  public void invalidUTF8BufferReturnsMalformedResult() {
    NulTerminatedCharsetDecoder decoder =
        new NulTerminatedCharsetDecoder(StandardCharsets.UTF_8.newDecoder());
    ByteBuffer in = decodeHex("C0FFEE00");
    CharBuffer out = CharBuffer.allocate(4);
    assertThat(in.position(), is(equalTo(0)));
    assertThat(in.limit(), is(equalTo(4)));
    assertThat(out.position(), is(equalTo(0)));
    assertThat(out.limit(), is(equalTo(4)));
    NulTerminatedCharsetDecoder.Result result = decoder.decode(in, out, true);
    assertThat(
        result,
        is(
            equalTo(
                new NulTerminatedCharsetDecoder.Result(false, CoderResult.malformedForLength(1)))));
    assertThat(in.position(), is(equalTo(0)));
    assertThat(in.limit(), is(equalTo(4)));
    assertThat(out.position(), is(equalTo(0)));
    assertThat(out.limit(), is(equalTo(4)));
  }

  @Test
  public void decodeValidUTF8String() throws Exception {
    assertThat(
        NulTerminatedCharsetDecoder.decodeUTF8String(decodeHex("F09F92A900")),
        is(equalTo("\uD83D\uDCA9")));
  }

  @Test
  public void decodeInvalidValidUTF8StringThrows() throws Exception {
    thrown.expect(CharacterCodingException.class);
    NulTerminatedCharsetDecoder.decodeUTF8String(decodeHex("C0FFEE00"));
  }

  @Test
  public void decodeNonNulTerminatedUTF8StringThrows() throws Exception {
    thrown.expect(BufferUnderflowException.class);
    NulTerminatedCharsetDecoder.decodeUTF8String(decodeHex("F09F92A9"));
  }

  private static ByteBuffer decodeHex(String hex) {
    return ByteBuffer.wrap(BaseEncoding.base16().decode(hex));
  }
}
