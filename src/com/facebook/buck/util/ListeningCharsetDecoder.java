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

package com.facebook.buck.util;

import com.zaxxer.nuprocess.codec.NuCharsetDecoder;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;

public final class ListeningCharsetDecoder {
  private final NuCharsetDecoder decoder;

  public interface DecoderListener {
    void onDecode(CharBuffer buffer, boolean closed, CoderResult decoderResult);
  }

  public ListeningCharsetDecoder(
      DecoderListener listener, // NOPMD confused by method reference
      CharsetDecoder charsetDecoder) {
    this.decoder = new NuCharsetDecoder(listener::onDecode, charsetDecoder);
  }

  public void onOutput(ByteBuffer buffer, boolean closed) {
    decoder.onOutput(buffer, closed);
  }
}
