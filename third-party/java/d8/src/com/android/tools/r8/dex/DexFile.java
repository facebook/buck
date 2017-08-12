// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.dex;

import static com.android.tools.r8.dex.Constants.DEX_FILE_MAGIC_PREFIX;

import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.utils.DexVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class DexFile extends BaseFile {

  final String name;
  private final int version;

  DexFile(String name) throws IOException {
    super(name);
    this.name = name;
    version = parseMagic(buffer);
  }

  public DexFile(InputStream input) throws IOException {
    super(input);
    // TODO(zerny): Remove dependencies on file names.
    name = "input-stream.dex";
    version = parseMagic(buffer);
  }

  /**
   * Returns a File that contains the bytes provided as argument. Used for testing.
   *
   * @param bytes contents of the file
   */
  DexFile(byte[] bytes) {
    super(bytes);
    this.name = "mockfile.dex";
    version = parseMagic(buffer);
  }

  // Parse the magic header and determine the dex file version.
  private int parseMagic(ByteBuffer buffer) {
    int index = 0;
    for (byte prefixByte : DEX_FILE_MAGIC_PREFIX) {
      if (buffer.get(index++) != prefixByte) {
        throw new CompilationError("Dex file has invalid header: " + name);
      }
    }
    if (buffer.get(index++) != '0' || buffer.get(index++) != '3') {
      throw new CompilationError("Dex file has invalid version number: " + name);
    }
    byte versionByte = buffer.get(index++);
    int version;
    switch (versionByte) {
      case '9':
        version = DexVersion.V39.getIntValue();
        break;
      case '8':
        version =  DexVersion.V38.getIntValue();
        break;
      case '7':
        version =  DexVersion.V37.getIntValue();
        break;
      case '5':
        version =  DexVersion.V35.getIntValue();
        break;
      default:
        throw new CompilationError("Dex file has invalid version number: " + name);
    }
    if (buffer.get(index++) != '\0') {
      throw new CompilationError("Dex file has invalid header: " + name);
    }
    return version;
  }

  @Override
  void setByteOrder() {
    // Make sure we set the right endian for reading.
    buffer.order(ByteOrder.LITTLE_ENDIAN);
    int endian = buffer.getInt(Constants.ENDIAN_TAG_OFFSET);
    if (endian == Constants.REVERSE_ENDIAN_CONSTANT) {
      buffer.order(ByteOrder.BIG_ENDIAN);
    } else {
      if (endian != Constants.ENDIAN_CONSTANT) {
        throw new CompilationError("Unable to determine endianess for reading dex file.");
      }
    }
  }

  int getDexVersion() {
    return version;
  }
}
