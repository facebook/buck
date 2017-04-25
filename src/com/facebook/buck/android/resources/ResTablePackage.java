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

import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * A Package consists of a header: ResTable_header u16 chunk_type u16 header_size u32 chunk_size u32
 * package_id u16[128] name u32 typeStringOffset u32 lastPublicTypeOffset u32 keyStringOffset u32
 * lastPublicKeyOffset u32 typeIdOffset
 *
 * <p>The header is followed by a types string pool (containing types like "id", "attr", "drawable",
 * ...) and a keys string pool (containing the names of resources). Then follows a TypeSpec for each
 * type (# of types can be derived from the size of the types stringpool).
 */
public class ResTablePackage extends ResChunk {
  public static final int HEADER_SIZE = 288;
  public static final int APP_PACKAGE_ID = 0x7F;

  private final int packageId;
  private final byte[] nameData;
  private final Supplier<String> name;
  private final StringPool types;
  private final StringPool keys;
  private final List<ResTableTypeSpec> typeSpecs;

  static ResTablePackage get(ByteBuffer buf) {
    int chunkType = buf.getShort();
    int headerSize = buf.getShort();
    int chunkSize = buf.getInt();
    int packageId = buf.getInt();
    byte[] nameData = new byte[256];
    buf.get(nameData);

    int typeStringOffset = buf.getInt();
    int lastPublicType = buf.getInt();
    int keyStringOffset = buf.getInt();
    int lastPublicKey = buf.getInt();
    int typeIdOffset = buf.getInt();

    Preconditions.checkState(chunkType == CHUNK_RES_TABLE_PACKAGE);
    Preconditions.checkState(headerSize == HEADER_SIZE);
    Preconditions.checkState(packageId == APP_PACKAGE_ID);
    Preconditions.checkState(typeIdOffset == 0);
    Preconditions.checkState(typeStringOffset == HEADER_SIZE);

    StringPool types = StringPool.get(slice(buf, typeStringOffset));
    StringPool keys = StringPool.get(slice(buf, keyStringOffset));

    Preconditions.checkState(lastPublicType == types.getStringCount());
    Preconditions.checkState(lastPublicKey == keys.getStringCount());
    Preconditions.checkState(keyStringOffset == HEADER_SIZE + types.getChunkSize());

    ImmutableList.Builder<ResTableTypeSpec> typeSpecs = ImmutableList.builder();
    buf.position(keyStringOffset + keys.getChunkSize());
    for (int i = 0; i < types.getStringCount(); i++) {
      ByteBuffer specBuf = slice(buf, buf.position());
      ResTableTypeSpec spec = ResTableTypeSpec.get(specBuf);
      typeSpecs.add(spec);
      buf.position(buf.position() + spec.getTotalSize());
    }

    Preconditions.checkState(buf.position() == chunkSize);

    return new ResTablePackage(chunkSize, packageId, nameData, types, keys, typeSpecs.build());
  }

  @Override
  public void put(ByteBuffer output) {
    Preconditions.checkState(output.remaining() >= getChunkSize());
    int start = output.position();
    putChunkHeader(output);
    output.putInt(APP_PACKAGE_ID);
    output.put(nameData);
    output.putInt(HEADER_SIZE);
    output.putInt(types.getStringCount());
    output.putInt(HEADER_SIZE + types.getChunkSize());
    output.putInt(keys.getStringCount());
    output.putInt(0);
    Preconditions.checkState(output.position() == start + HEADER_SIZE);
    types.put(output);
    Preconditions.checkState(output.position() == start + HEADER_SIZE + types.getChunkSize());
    keys.put(output);
    Preconditions.checkState(
        output.position() == start + HEADER_SIZE + types.getChunkSize() + keys.getChunkSize());
    typeSpecs.forEach(s -> s.put(output));
    Preconditions.checkState(output.position() == start + getChunkSize());
  }

  public ResTablePackage(
      int chunkSize,
      int packageId,
      byte[] nameData,
      StringPool types,
      StringPool keys,
      List<ResTableTypeSpec> typeSpecs) {
    super(CHUNK_RES_TABLE_PACKAGE, HEADER_SIZE, chunkSize);
    this.packageId = packageId;
    this.nameData = nameData;
    this.types = types;
    this.keys = keys;
    this.typeSpecs = typeSpecs;

    name =
        Suppliers.<String>memoize(
            () -> {
              // Construct a string from the full name data. This will end with a bunch of \0.
              String fullData = new String(nameData, Charsets.UTF_16LE);
              return fullData.substring(0, fullData.indexOf(0));
            });
    Preconditions.checkState(this.packageId == APP_PACKAGE_ID);
  }

  public void dump(StringPool strings, PrintStream out) {
    out.format("Package Group 0 id=0x%02x packageCount=1 name=%s\n", packageId, getPackageName());
    out.format("  Package 0 id=0x%02x name=%s\n", packageId, getPackageName());
    for (ResTableTypeSpec spec : typeSpecs) {
      spec.dump(strings, this, out);
    }
  }

  String getPackageName() {
    return name.get();
  }

  public List<ResTableTypeSpec> getTypeSpecs() {
    return typeSpecs;
  }

  public StringPool getKeys() {
    return keys;
  }

  public ResTableTypeSpec getTypeSpec(int type) {
    for (ResTableTypeSpec spec : typeSpecs) {
      if (spec.getResourceType() == type) {
        return spec;
      }
    }
    throw new IllegalArgumentException();
  }

  public StringPool getTypes() {
    return types;
  }

  public byte[] getNameData() {
    return nameData;
  }

  public String getRefName(int i) {
    int type = (i >> 16) & 0xFF;
    int idx = i & 0xFFFF;
    ResTableTypeSpec spec = typeSpecs.get(type - 1);
    Preconditions.checkState(spec.getResourceType() == type);
    return spec.getResourceName(this, idx);
  }
}
