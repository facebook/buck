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

import com.zaxxer.nuprocess.codec.NuCharsetDecoder;
import com.zaxxer.nuprocess.codec.NuCharsetDecoderHandler;

import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;

public final class ListeningCharsetDecoder {
  private final NuCharsetDecoder decoder;

  public interface DecoderListener {
    void onDecode(CharBuffer buffer, boolean closed, CoderResult decoderResult);
  }

  public ListeningCharsetDecoder(DecoderListener listener, Charset charset) {
    this(listener, charset.newDecoder());
  }

  public ListeningCharsetDecoder(final DecoderListener listener, CharsetDecoder charsetDecoder) {
    this.decoder = new NuCharsetDecoder(
        new NuCharsetDecoderHandler() {
          @Override
          public void onDecode(CharBuffer buffer, boolean closed, CoderResult decoderResult) {
            listener.onDecode(buffer, closed, decoderResult);
          }
        },
        charsetDecoder);
  }

  public void onOutput(ByteBuffer buffer, boolean closed) {
    decoder.onOutput(buffer, closed);
  }
}
