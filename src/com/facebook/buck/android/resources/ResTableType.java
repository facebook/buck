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

package com.facebook.buck.android.resources;

import com.google.common.base.Preconditions;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * ResTableType is a ResChunk holding the resource values for a given type and configuration. It
 * consists of: ResChunk_header u32 chunk_type u32 header_size u32 chunk_size u8 id u32 entry_count
 * u32 entry_start Config u32 config_size u8[config_size - 4] data
 *
 * <p>This is followed by entry_count u32s containing offsets from entry_start to the data for each
 * entry. If the offset for a resource is -1, that resource has no value in this configuration.
 *
 * <p>After the offsets comes the entry data. Each entry is of the form: u16 size u16 flags
 * StringRef key
 *
 * <p>If `(flags & FLAG_COMPLEX) == 0` this data is then followed by: ResValue value
 *
 * <p>Else it's followed by a map header: ResRef parent u32 count
 *
 * <p>and `count` map entries of the form: ResRef name ResValue value
 */
public class ResTableType extends ResChunk {
  private static final int CONFIG_OFFSET = 20;
  private static final int FLAG_COMPLEX = 0x1;

  public static final int ATTRIBUTE_NAME_REF_OFFSET = 0;
  public static final int ATTRIBUTE_SIZE_OFFSET = 4;
  public static final int ATTRIBUTE_TYPE_OFFSET = 7;
  public static final int ATTRIBUTE_DATA_OFFSET = 8;

  private final int id;
  private final int entryCount;
  private final ByteBuffer config;
  private final ByteBuffer entryOffsets;
  private final ByteBuffer entryData;

  @Nullable
  public static ResTableType slice(ResTableType type, int count) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    int currentOffset = 0;
    ByteBuffer entryOffsets = wrap(new byte[count * 4]);
    for (int i = 0; i < count; i++) {
      int offset = type.getEntryValueOffset(i);
      if (offset == -1) {
        entryOffsets.putInt(i * 4, -1);
      } else {
        entryOffsets.putInt(i * 4, currentOffset);
        int dataSize = type.getEntrySizeAtOffset(offset);
        currentOffset += dataSize;
        output.write(type.entryData.array(), type.entryData.arrayOffset() + offset, dataSize);
      }
    }

    byte[] entryData = output.toByteArray();
    if (entryData.length == 0) {
      return null;
    }
    int headerSize = type.getHeaderSize();
    int chunkSize = headerSize + count * 4 + entryData.length;

    return new ResTableType(
        headerSize, chunkSize, type.id, count, copy(type.config), entryOffsets, wrap(entryData));
  }

  private int getEntrySizeAtOffset(int offset) {
    int size = entryData.getShort(offset);
    int flags = entryData.getShort(offset + 2);
    if ((flags & FLAG_COMPLEX) == 0) {
      return size + entryData.getShort(offset + size);
    } else {
      int count = entryData.getInt(offset + 12);
      for (int i = 0; i < count; i++) {
        size += 4 + entryData.getShort(offset + size + 4);
      }
      return size;
    }
  }

  @Override
  public void put(ByteBuffer output) {
    Preconditions.checkState(output.remaining() >= getChunkSize());
    int start = output.position();
    putChunkHeader(output);
    output.put((byte) (id + 1));
    output.put((byte) 0);
    output.putShort((byte) 0);
    output.putInt(entryCount);
    output.putInt(getHeaderSize() + 4 * entryCount);
    output.put(slice(config, 0));
    output.put(slice(entryOffsets, 0));
    output.put(slice(entryData, 0));
    Preconditions.checkState(output.position() == start + getChunkSize());
  }

  public static ResTableType get(ByteBuffer buf) {
    int type = buf.getShort();
    int headerSize = buf.getShort();
    int chunkSize = buf.getInt();
    int id = (buf.get() & 0xFF) - 1;
    buf.get(); // ignored
    buf.getShort(); // ignored
    int entryCount = buf.getInt();
    int entriesOffset = buf.getInt();
    int configSize = buf.getInt(buf.position());
    int entryDataSize = chunkSize - headerSize - 4 * entryCount;

    Preconditions.checkState(type == CHUNK_RES_TABLE_TYPE);
    Preconditions.checkState(headerSize == configSize + CONFIG_OFFSET);

    return new ResTableType(
        headerSize,
        chunkSize,
        id,
        entryCount,
        slice(buf, CONFIG_OFFSET, configSize),
        slice(buf, headerSize, entryCount * 4),
        slice(buf, entriesOffset, entryDataSize));
  }

  private ResTableType(
      int headerSize,
      int chunkSize,
      int id,
      int entryCount,
      ByteBuffer config,
      ByteBuffer entryOffsets,
      ByteBuffer entryData) {
    super(CHUNK_RES_TABLE_TYPE, headerSize, chunkSize);
    this.id = id;
    this.entryCount = entryCount;
    this.config = config;
    this.entryOffsets = entryOffsets;
    this.entryData = entryData;
  }

  public void dump(StringPool strings, ResTablePackage resPackage, PrintStream out) {
    out.format("      config (unknown):\n");
    for (int entryIdx = 0; entryIdx < entryCount; entryIdx++) {
      int offset = getEntryValueOffset(entryIdx);
      if (offset != -1) {
        int size = entryData.getShort(offset);
        int flags = entryData.getShort(offset + 2);
        int refId = entryData.getInt(offset + 4);
        Preconditions.checkState(size > 0);
        // Some of the formatting of this line is shared between maps/non-maps.
        out.format(
            "        resource 0x7f%02x%04x %s:%s/%s:",
            getResourceType(),
            entryIdx,
            resPackage.getPackageName(),
            getTypeName(resPackage),
            resPackage.getKeys().getString(refId));
        if ((flags & FLAG_COMPLEX) != 0) {
          out.format(" <bag>\n");
          int parent = entryData.getInt(offset + 8);
          int count = entryData.getInt(offset + 12);
          out.format(
              "          Parent=0x%08x(Resolved=0x%08x), Count=%d\n",
              parent, parent == 0 ? 0x7F000000 : parent, count);
          int entryOffset = offset;
          for (int attrIdx = 0; attrIdx < count; attrIdx++) {
            int name = entryData.getInt(entryOffset + 16);
            int vsize = entryData.getShort(entryOffset + 20);
            int type = entryData.get(entryOffset + 23);
            int data = entryData.getInt(entryOffset + 24);
            String dataString = formatTypeForDump(strings, type, data);
            out.format("          #%d (Key=0x%08x): %s\n", attrIdx, name, dataString);
            entryOffset += 4 + vsize;
          }
        } else {
          int vsize = entryData.getShort(offset + 8);
          // empty(offset + 10)
          int type = entryData.get(offset + 11);
          int data = entryData.getInt(offset + 12);
          out.format(" t=0x%02x d=0x%08x (s=0x%04x r=0x00)\n", type, data, vsize);
          String dataString = formatTypeForDump(strings, type, data);
          out.format("          %s\n", dataString);
        }
      }
    }
  }

  private String getTypeName(ResTablePackage resPackage) {
    return resPackage.getTypes().getString(id);
  }

  private String formatTypeForDump(StringPool strings, int type, int data) {
    String typeString;
    String dataString;
    switch (type) {
      case RES_REFERENCE:
        typeString = "reference";
        dataString = String.format("0x%08x", data);
        break;
      case RES_ATTRIBUTE:
        typeString = "attribute";
        dataString = String.format("0x%08x", data);
        break;
      case RES_STRING:
        typeString = "string" + (strings.isUtf8() ? "8" : "16");
        dataString = String.format("\"%s\"", strings.getOutputNormalizedString(data));
        break;
      case RES_FLOAT:
        typeString = "float";
        dataString = new DecimalFormat("0.######").format(Float.intBitsToFloat(data));
        break;
      case RES_DIMENSION:
        typeString = "dimension";
        dataString = formatComplex(data, false);
        break;
      case RES_FRACTION:
        typeString = "fraction";
        dataString = formatComplex(data, true);
        break;
      case RES_DECIMAL:
      case RES_HEX:
      case RES_BOOL:
      case RES_COLOR_ARGB4:
      case RES_COLOR_RGB4:
      case RES_COLOR_ARGB8:
      case RES_COLOR_RGB8:
        typeString = "color";
        dataString = String.format("#%08x", data);
        break;
      default:
        typeString = String.format("unknown %02x", type);
        dataString = String.format("xxx 0x%08x", data);
    }
    return String.format("(%s) %s", typeString, dataString);
  }

  private static final int RADIX_SHIFT = 4;
  private static final int RADIX_MASK = 0x3;
  private static final int MANTISSA_SHIFT = 8;
  private static final int MANTISSA_MASK = 0xFFFFFF;
  private static final int[] RADIX_MULTS = {0, 7, 15, 23};

  private static final int UNIT_MASK = 0xF;

  private static final int UNIT_PX = 0;
  private static final int UNIT_DIP = 1;
  private static final int UNIT_SP = 2;
  private static final int UNIT_PT = 3;
  private static final int UNIT_IN = 4;
  private static final int UNIT_MM = 5;
  private static final int UNIT_FRACTION = 0;
  private static final int UNIT_FRACTION_PARENT = 1;

  // See print_complex() at
  // https://android.googlesource.com/platform/frameworks/base/+/kitkat-release/libs/androidfw/ResourceTypes.cpp
  // The implementation there is really silly and results in different values if implemented in Java
  // directly.
  private String formatComplex(int data, boolean isFraction) {
    int mantissa = (data >> MANTISSA_SHIFT) & MANTISSA_MASK;
    int exp = RADIX_MULTS[(data >> RADIX_SHIFT) & RADIX_MASK];
    float value = mantissa;
    if (exp != 0) {
      value = value / (1 << exp);
    }
    String unit;
    if (isFraction) {
      switch (data & UNIT_MASK) {
        case UNIT_FRACTION:
          unit = "%";
          break;
        case UNIT_FRACTION_PARENT:
          unit = "%p";
          break;
        default:
          unit = " (unknown unit)";
          break;
      }
    } else {
      switch (data & UNIT_MASK) {
        case UNIT_PX:
          unit = "px";
          break;
        case UNIT_DIP:
          unit = "dp";
          break;
        case UNIT_SP:
          unit = "sp";
          break;
        case UNIT_PT:
          unit = "pt";
          break;
        case UNIT_IN:
          unit = "in";
          break;
        case UNIT_MM:
          unit = "mm";
          break;
        default:
          unit = " (unknown unit)";
          break;
      }
    }
    return String.format("%f%s", value, unit);
  }

  int getResourceType() {
    return id + 1;
  }

  public int getResourceRef(int id) {
    int offset = getEntryValueOffset(id);
    if (offset == -1) {
      return -1;
    }
    return entryData.getInt(offset + 4);
  }

  public int getEntryValueOffset(int i) {
    return entryOffsets.getInt(i * 4);
  }

  public int getEntryCount() {
    return entryCount;
  }

  public void transformKeyReferences(RefTransformer visitor) {
    for (int i = 0; i < entryCount; i++) {
      int offset = getEntryValueOffset(i);
      if (offset != -1) {
        transformEntryDataOffset(entryData, offset + 4, visitor);
      }
    }
  }

  public void visitKeyReferences(RefVisitor visitor) {
    transformKeyReferences(
        i -> {
          visitor.visit(i);
          return i;
        });
  }

  private void transformStringReferencesAt(RefTransformer visitor, int offset) {
    int flags = entryData.getShort(offset + 2);
    if ((flags & FLAG_COMPLEX) != 0) {
      int count = entryData.getInt(offset + 12);
      int entryOffset = offset;
      for (int j = 0; j < count; j++) {
        int name = entryData.getInt(entryOffset + 16);
        if ((name >> 24) == ResTablePackage.APP_PACKAGE_ID) {
          Preconditions.checkState(((name >> 16) & 0xFF) != 0);
        }
        int vsize = entryData.getShort(entryOffset + 20);
        int type = entryData.get(entryOffset + 23);
        if (type == RES_STRING) {
          transformEntryDataOffset(entryData, entryOffset + 24, visitor);
        }
        entryOffset += 4 + vsize;
      }
    } else {
      int type = entryData.get(offset + 11);
      if (type == RES_STRING) {
        transformEntryDataOffset(entryData, offset + 12, visitor);
      }
    }
  }

  public void transformStringReferences(RefTransformer visitor) {
    for (int i = 0; i < entryCount; i++) {
      int offset = getEntryValueOffset(i);
      if (offset != -1) {
        transformStringReferencesAt(visitor, offset);
      }
    }
  }

  public void transformStringReferences(int[] idsToVisit, RefTransformer visitor) {
    for (int i : idsToVisit) {
      int offset = getEntryValueOffset(i);
      if (offset != -1) {
        transformStringReferencesAt(visitor, offset);
      }
    }
  }

  public void visitStringReferences(RefVisitor visitor) {
    transformStringReferences(
        i -> {
          visitor.visit(i);
          return i;
        });
  }

  public void visitStringReferences(int[] idsToVisit, RefVisitor visitor) {
    transformStringReferences(
        idsToVisit,
        i -> {
          visitor.visit(i);
          return i;
        });
  }

  private void transformReferencesAt(RefTransformer visitor, int offset) {
    int flags = entryData.getShort(offset + 2);
    if ((flags & FLAG_COMPLEX) != 0) {
      int parent = entryData.getInt(offset + 8);
      if (parent != 0) {
        // An attribute map can derive from another. If it does, visit that parent.
        transformEntryDataOffset(entryData, offset + 8, visitor);
      }
      int count = entryData.getInt(offset + 12);
      int entryStart = offset + 16;
      int entryOffset = entryStart;
      for (int j = 0; j < count; j++) {
        // Visit the name attribute reference.
        transformEntryDataOffset(entryData, entryOffset + ATTRIBUTE_NAME_REF_OFFSET, visitor);
        int type = entryData.get(entryOffset + ATTRIBUTE_TYPE_OFFSET);
        if (type == RES_REFERENCE || type == RES_ATTRIBUTE) {
          // Visit the value if it's a reference.
          transformEntryDataOffset(entryData, entryOffset + ATTRIBUTE_DATA_OFFSET, visitor);
        } else if (type == RES_DYNAMIC_REFERENCE || type == RES_DYNAMIC_ATTRIBUTE) {
          throw new UnsupportedOperationException();
        }
        int size = entryData.getShort(entryOffset + ATTRIBUTE_SIZE_OFFSET);
        entryOffset += 4 + size;
      }
      sortAttributesAt(entryData, count, entryStart);
    } else {
      int type = entryData.get(offset + 11);
      if (type == RES_REFERENCE || type == RES_ATTRIBUTE) {
        // Visit the value if it's a reference.
        transformEntryDataOffset(entryData, offset + 12, visitor);
      } else if (type == RES_DYNAMIC_REFERENCE || type == RES_DYNAMIC_ATTRIBUTE) {
        throw new UnsupportedOperationException();
      }
    }
  }

  private void sortAttributesAt(ByteBuffer entryData, int attrCount, int attrStart) {
    class AttrRef implements Comparable<AttrRef> {
      final int offset;
      final int size;
      final int resId;

      AttrRef(int offset) {
        this.offset = offset;
        this.resId = entryData.getInt(offset + ATTRIBUTE_NAME_REF_OFFSET);
        this.size = 4 + entryData.getShort(offset + ATTRIBUTE_SIZE_OFFSET);
      }

      @Override
      public int compareTo(AttrRef other) {
        return resId - other.resId;
      }
    }
    Stream.Builder<AttrRef> builder = Stream.builder();
    int entryOffset = attrStart;
    for (int j = 0; j < attrCount; j++) {
      AttrRef ref = new AttrRef(entryOffset);
      builder.add(ref);
      entryOffset += ref.size;
    }

    byte[] newData = new byte[entryOffset - attrStart];
    ByteBuffer newBuf = wrap(newData);
    builder
        .build()
        .sorted()
        .forEachOrdered(ref -> newBuf.put(slice(entryData, ref.offset, ref.size)));
    slice(entryData, attrStart).put(newData);
  }

  public void transformReferences(RefTransformer visitor) {
    for (int i = 0; i < entryCount; i++) {
      int offset = getEntryValueOffset(i);
      if (offset != -1) {
        transformReferencesAt(visitor, offset);
      }
    }
  }

  public void transformReferences(int[] ids, RefTransformer visitor) {
    for (int i : ids) {
      int offset = getEntryValueOffset(i);
      if (offset != -1) {
        transformReferencesAt(visitor, offset);
      }
    }
  }

  public void visitReferences(int[] ids, RefVisitor visitor) {
    transformReferences(
        ids,
        i -> {
          visitor.visit(i);
          return i;
        });
  }

  public void reassignIds(ReferenceMapper refMapping) {
    transformReferences(refMapping::map);
    refMapping.rewrite(getResourceType(), entryOffsets.asIntBuffer());
  }
}
