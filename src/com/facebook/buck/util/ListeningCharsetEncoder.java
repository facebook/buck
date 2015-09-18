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

import com.zaxxer.nuprocess.codec.NuCharsetEncoder;
import com.zaxxer.nuprocess.codec.NuCharsetEncoderHandler;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;

public final class ListeningCharsetEncoder {
  private final NuCharsetEncoder encoder;

  public interface EncoderListener {
    boolean onStdinReady(CharBuffer buffer);
    void onEncoderError(CoderResult result);
  }

  public ListeningCharsetEncoder(EncoderListener listener, Charset charset) {
    this(listener, charset.newEncoder());
  }

  public ListeningCharsetEncoder(final EncoderListener listener, CharsetEncoder charsetEncoder) {
    this.encoder = new NuCharsetEncoder(
        new NuCharsetEncoderHandler() {
          @Override
          public boolean onStdinReady(CharBuffer buffer) {
            return listener.onStdinReady(buffer);
          }

          @Override
          public void onEncoderError(CoderResult result) {
            listener.onEncoderError(result);
          }
        },
        charsetEncoder);
  }

  public boolean onStdinReady(ByteBuffer buffer) {
    return encoder.onStdinReady(buffer);
  }
}
