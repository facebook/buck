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

import java.io.IOException;
import java.io.OutputStream;

/** An OutputStream which forwards to two OutputStreams. */
public class TeeOutputStream extends OutputStream {
  private final OutputStream one;
  private final OutputStream two;

  public TeeOutputStream(OutputStream one, OutputStream two) {
    this.one = one;
    this.two = two;
  }

  @Override
  public void write(int b) throws IOException {
    one.write(b);
    two.write(b);
  }

  @Override
  public void flush() throws IOException {
    one.flush();
    two.flush();
  }

  @Override
  public void close() throws IOException {
    one.close();
    two.close();
  }
}
