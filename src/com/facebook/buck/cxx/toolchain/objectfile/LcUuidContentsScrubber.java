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

package com.facebook.buck.cxx.toolchain.objectfile;

import com.facebook.buck.io.file.FileContentsScrubber;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

public class LcUuidContentsScrubber implements FileContentsScrubber {

  private static final byte[] ZERO_UUID = new byte[16];

  @Override
  public void scrubFile(FileChannel file) throws IOException, ScrubException {
    if (!Machos.isMacho(file)) {
      return;
    }

    long size = file.size();
    MappedByteBuffer map = file.map(FileChannel.MapMode.READ_WRITE, 0, size);

    try {
      Machos.setUuid(map, ZERO_UUID);
    } catch (Machos.MachoException e) {
      throw new ScrubException(e.getMessage());
    }
    map.rewind();

    Hasher hasher = Hashing.sha1().newHasher();
    while (map.hasRemaining()) {
      hasher.putByte(map.get());
    }

    map.rewind();
    try {
      Machos.setUuid(map, Arrays.copyOf(hasher.hash().asBytes(), 16));
    } catch (Machos.MachoException e) {
      throw new ScrubException(e.getMessage());
    }
  }
}
