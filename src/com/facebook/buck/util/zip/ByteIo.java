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

package com.facebook.buck.util.zip;

import java.io.IOException;
import java.io.OutputStream;

class ByteIo {
  private ByteIo() {
    // utility class.
  }

  public static long writeShort(OutputStream out, int value) throws IOException {
    out.write((value & 0xff));
    out.write(((value >>> 8) & 0xff));
    return 2;
  }

  protected static long writeInt(OutputStream out, long value) throws IOException {
    out.write((int) (value & 0xff));
    out.write((int) ((value >>> 8) & 0xff));
    out.write((int) ((value >>> 16) & 0xff));
    out.write((int) ((value >>> 24) & 0xff));
    return 4;
  }

  protected static long writeLong(OutputStream out, long value) throws IOException {
    out.write((int) (value & 0xff));
    out.write((int) ((value >>> 8) & 0xff));
    out.write((int) ((value >>> 16) & 0xff));
    out.write((int) ((value >>> 24) & 0xff));
    out.write((int) ((value >>> 32) & 0xff));
    out.write((int) ((value >>> 40) & 0xff));
    out.write((int) ((value >>> 48) & 0xff));
    out.write((int) ((value >>> 56) & 0xff));
    return 8;
  }
}
