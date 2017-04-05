/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.android.resources;


import com.google.common.base.Preconditions;

import java.io.PrintStream;
import java.nio.ByteBuffer;

/**
 * A ResourceTable is the top-level representation of resources.arsc. It consists of a header:
 *   ResTable_header
 *       u32 chunk_type
 *       u32 header_size
 *       u32 chunk_size
 *   u32 package_count
 *
 * The header is followed by a StringPool and then package_count packages.
 *
 * In practice, aapt always generates .arsc files with package_count == 1.
 */
public class ResourceTable extends ResChunk {
  public static final int HEADER_SIZE = 12;

  private final StringPool strings;
  private final ResTablePackage resPackage;

  public ResourceTable(
      StringPool strings,
      ResTablePackage resPackage) {
    super(
        CHUNK_RESOURCE_TABLE,
        HEADER_SIZE,
        HEADER_SIZE + strings.getChunkSize() + resPackage.getChunkSize());
    this.strings = strings;
    this.resPackage = resPackage;
  }

  public static ResourceTable get(ByteBuffer buf) {
    int type = buf.getShort();
    int headerSize = buf.getShort();
    int chunkSize = buf.getInt();
    int packageCount = buf.getInt();

    Preconditions.checkState(type == CHUNK_RESOURCE_TABLE);
    Preconditions.checkState(headerSize == HEADER_SIZE);
    Preconditions.checkState(packageCount == 1);

    StringPool strings = StringPool.get(slice(buf, buf.position()));
    ResTablePackage resPackage = ResTablePackage.get(
        slice(buf, HEADER_SIZE + strings.getChunkSize()));

    Preconditions.checkState(
        chunkSize == HEADER_SIZE + strings.getChunkSize() + resPackage.getChunkSize());

    return new ResourceTable(strings, resPackage);
  }

  @Override
  public void put(ByteBuffer buf) {
    putChunkHeader(buf);
    buf.putInt(1); // packageCount
    Preconditions.checkState(buf.position() == HEADER_SIZE);
    strings.put(buf);
    Preconditions.checkState(buf.position() == HEADER_SIZE + strings.getChunkSize());
    resPackage.put(buf);
  }

  public void dump(PrintStream out) {
    out.format("Package Groups (1)\n");
    resPackage.dump(strings, out);
  }

  public StringPool getStrings() {
    return strings;
  }

  public ResTablePackage getPackage() {
    return resPackage;
  }

  public void dumpStrings(PrintStream out) {
    strings.dump(out);
  }
}
