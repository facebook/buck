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
import com.google.common.collect.ImmutableList;

import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * ResTableTypeSpec is a ResChunk specifying the flags for each resource of a given type. These
 * flags specify which types of configuration contain multiple values for a given resource. A
 * ResTableTypeSpec consists of:
 *   ResChunk_header
 *     u16 type
 *     u16 header_size
 *     u32 chunk_size
 *   u8  id
 *   u8  0x00
 *   u16 0x0000
 *   u32 entry_count
 *
 * This is then followed by entry_count u32s giving the flags for each resource of this type.
 *
 * In practice, this is then followed by a ResTableType for each configuration that has resources
 * of this type. For convenience, those are considered to be part of the ResTableTypeSpec.
 */
public class ResTableTypeSpec extends ResChunk {
  private static final int HEADER_SIZE = 16;

  private final int id;
  private final int entryCount;
  private final List<ResTableType> configs;
  private final int totalSize;
  private final ByteBuffer entryFlags;

  @Override
  public void put(ByteBuffer output) {
    Preconditions.checkState(output.remaining() >= totalSize);
    int start = output.position();
    putChunkHeader(output);
    output.put((byte) (id + 1));
    output.put((byte) 0);
    output.putShort((short) 0);
    output.putInt(entryCount);
    output.put(slice(entryFlags, 0));
    configs.forEach(c -> c.put(output));
    Preconditions.checkState(output.position() == start + totalSize);
  }

  public static ResTableTypeSpec get(ByteBuffer buf) {
    int type = buf.getShort();
    int headerSize = buf.getShort();
    int chunkSize = buf.getInt();
    int id = (buf.get() & 0xFF) - 1;
    buf.get();
    buf.getShort();
    int entryCount = buf.getInt();
    // ignored u8
    // ignored u16
    Preconditions.checkState(type == CHUNK_RES_TABLE_TYPE_SPEC);
    Preconditions.checkState(headerSize == HEADER_SIZE);
    Preconditions.checkState(chunkSize == HEADER_SIZE + 4 * entryCount);

    return new ResTableTypeSpec(
        id,
        entryCount,
        slice(buf, HEADER_SIZE, 4 * entryCount),
        getConfigsFromBuffer(slice(buf, chunkSize)));
  }

  private ResTableTypeSpec(
      int id,
      int entryCount,
      ByteBuffer entryFlags,
      List<ResTableType> configs) {
    super(CHUNK_RES_TABLE_TYPE_SPEC, HEADER_SIZE, HEADER_SIZE + 4 * entryCount);
    this.id = id;
    this.entryCount = entryCount;
    this.entryFlags = entryFlags;
    this.configs = configs;
    int configsSize = 0;
    for (ResTableType config : configs) {
      Preconditions.checkState(getResourceType() == config.getResourceType());
      configsSize += config.getChunkSize();
    }
    this.totalSize = getChunkSize() + configsSize;

  }

  private static List<ResTableType> getConfigsFromBuffer(ByteBuffer buf) {
    ImmutableList.Builder<ResTableType> configs = ImmutableList.builder();
    while (buf.position() < buf.limit() && buf.getShort(buf.position()) == 0x201) {
      ResTableType config = ResTableType.get(slice(buf, buf.position()));
      configs.add(config);
      buf.position(buf.position() + config.getChunkSize());
    }
    return configs.build();
  }

  @Override
  public int getTotalSize() {
    return totalSize;
  }

  String getResourceName(ResTablePackage resPackage, int id) {
    // We need to find an actual entry in one of the configs to find the name of this resource.
    for (ResTableType t : configs) {
      int refId = t.getResourceRef(id);
      if (refId >= 0) {
        return resPackage.getKeys().getString(refId);
      }
    }
    throw new RuntimeException();
  }

  public String getResourceTypeName(ResTablePackage resPackage) {
    return resPackage.getTypes().getString(id);
  }

  public void dump(StringPool strings, ResTablePackage resPackage, PrintStream out) {
    if (entryCount == 0) {
      return;
    }
    out.format("    type %d configCount=%d entryCount=%d\n", id, configs.size(), entryCount);
    for (int i = 0; i < entryCount; i++) {
      out.format(
        "      spec resource 0x7f%02x%04x %s:%s/%s: flags=0x%08x\n",
        getResourceType(),
        i,
        resPackage.getPackageName(),
        getResourceTypeName(resPackage),
        getResourceName(resPackage, i),
        entryFlags.getInt(i * 4));
    }
    for (ResTableType type : configs) {
      type.dump(strings, resPackage, out);
    }
  }

  public int getResourceType() {
    return id + 1;
  }

  public List<ResTableType> getConfigs() {
    return configs;
  }
}
