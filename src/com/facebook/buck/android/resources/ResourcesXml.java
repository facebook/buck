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
import com.google.common.base.Strings;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import javax.annotation.Nullable;

/**
 * ResourcesXml handles Android's compiled xml format. It consists of: ResChunk_header u16
 * chunk_type u16 header_size u32 chunk_size
 *
 * <p>The header is followed by a StringPool containing all the strings used in the xml. This is
 * then followed by an optional RefMap. After the StringPool/RefMap comes a list of xml nodes of the
 * form: ResChunk_header u16 chunk_type u16 header_size u32 chunk_size u32 lineNumber StringRef
 * comment
 *
 * <p>Each node contains extra information depending on the type:
 *
 * <p>XML_START_NS: StringRef prefix StringRef uri
 *
 * <p>XML_END_NS: StringRef prefix StringRef uri
 *
 * <p>XML_CDATA: StringRef data Res_value typedData
 *
 * <p>XML_END_ELEMENT: StringRef namespace StringRef name
 *
 * <p>XML_START_ELEMENT: StringRef namespace StringRef name u16 attrStart u16 attrSize u16 attrCount
 * u16 idIndex u16 classIndex u16 styleIndex
 *
 * <p>XML_START_ELEMENT is then followed by attrCount attributes of the form: StringRef namespace
 * StringRef name StringRef rawValue Res_value typedValue
 */
public class ResourcesXml extends ResChunk {
  private static final boolean DEBUG = true;

  public static final int HEADER_SIZE = 8;

  private static final int XML_FIRST_TYPE = 0x100;
  private static final int XML_START_NS = 0x100;
  private static final int XML_END_NS = 0x101;
  private static final int XML_START_ELEMENT = 0x102;
  private static final int XML_END_ELEMENT = 0x103;
  private static final int XML_CDATA = 0x104;
  private static final int XML_LAST_TYPE = 0x104;
  public static final int ATTRIBUTE_SIZE = 20;

  private final StringPool strings;
  private final Optional<RefMap> refMap;
  private final ByteBuffer nodeBuf;

  public static ResourcesXml get(ByteBuffer buf) {
    int type = buf.getShort();
    int headerSize = buf.getShort();
    int chunkSize = buf.getInt();

    Preconditions.checkState(type == CHUNK_XML_TREE);
    Preconditions.checkState(headerSize == HEADER_SIZE);
    Preconditions.checkState(chunkSize == buf.limit());

    // The header should be immediately followed by a string pool.
    StringPool strings = StringPool.get(slice(buf, HEADER_SIZE));
    buf.position(HEADER_SIZE + strings.getChunkSize());

    int nextType = buf.getShort(buf.position());
    Optional<RefMap> refMap = Optional.empty();
    if (nextType == ResChunk.CHUNK_XML_REF_MAP) {
      RefMap map = new RefMap(slice(buf, buf.position()));
      refMap = Optional.of(map);
      buf.position(buf.position() + map.getChunkSize());
    }

    int position = buf.position();
    while (position < buf.limit()) {
      nextType = buf.getShort(position);
      Preconditions.checkState(
          nextType >= XML_FIRST_TYPE && nextType <= XML_LAST_TYPE, "Got: " + nextType);
      position += buf.getInt(position + 4);
    }
    Preconditions.checkState(position == buf.limit());
    return new ResourcesXml(buf.limit(), strings, refMap, slice(buf, buf.position()));
  }

  @Override
  public void put(ByteBuffer buf) {
    putChunkHeader(buf);
    strings.put(buf);
    refMap.ifPresent(m -> m.put(buf));
    buf.put(slice(nodeBuf, 0));
  }

  private ResourcesXml(
      int chunkSize, StringPool strings, Optional<RefMap> refMap, ByteBuffer nodeBuf) {
    super(CHUNK_XML_TREE, HEADER_SIZE, chunkSize);
    this.strings = strings;
    this.refMap = refMap;
    this.nodeBuf = nodeBuf;
  }

  public void transformReferences(RefTransformer visitor) {
    // Make sure to rewrite the refMap first so that fixupAttrs works correctly.
    refMap.ifPresent(m -> m.visitReferences(visitor));
    int offset = 0;
    while (offset < nodeBuf.limit()) {
      int type = nodeBuf.getShort(offset);
      if (type == XML_START_ELEMENT) {
        int nodeHeaderSize = nodeBuf.getShort(offset + 2);

        int extOffset = offset + nodeHeaderSize;
        int attrStart = extOffset + nodeBuf.getShort(extOffset + 8);
        Preconditions.checkState(attrStart == extOffset + 20);
        if (DEBUG) {
          int attrSize = nodeBuf.getShort(extOffset + 10);
          Preconditions.checkState(attrSize == ATTRIBUTE_SIZE);
        }
        int attrCount = nodeBuf.getShort(extOffset + 12);
        for (int i = 0; i < attrCount; i++) {
          int attrOffset = attrStart + i * ATTRIBUTE_SIZE;
          int attrType = nodeBuf.get(attrOffset + 15);
          switch (attrType) {
            case RES_REFERENCE:
            case RES_ATTRIBUTE:
              transformEntryDataOffset(nodeBuf, attrOffset + 16, visitor);
              break;
            case RES_DYNAMIC_ATTRIBUTE:
            case RES_DYNAMIC_REFERENCE:
              throw new UnsupportedOperationException();
            default:
              break;
          }
        }
        fixupAttrs(attrStart, attrCount);
      }
      int chunkSize = nodeBuf.getInt(offset + 4);
      offset += chunkSize;
    }
  }

  private int getAttributeNameResId(int attrOffset) {
    if (!refMap.isPresent()) {
      return -1;
    }
    int id = nodeBuf.getInt(attrOffset + 4);
    return refMap.get().getRef(id);
  }

  // Android resource handling makes several assumptions about xml node attribute ordering:
  //
  // > 1) The input has the same sorting rules applied to it as
  // >    the attribute data contained by this class.
  // > 2) Attributes are grouped by package ID.
  // > 3) Among attributes with the same package ID, the attributes are
  // >    sorted by increasing resource ID.
  //
  // After reassigning ids, we need to make sure that these assumptions still
  // hold.
  //
  // See
  // https://android.googlesource.com/platform/frameworks/base/+/nougat-release/include/androidfw/AttributeFinder.h
  //
  // If there's no refmap, this doesn't ensure that the attributes are sorted correctly, but in
  // that case, Android doesn't need them sorted anyway.
  private void fixupAttrs(int attrStart, int attrCount) {
    if (!refMap.isPresent()) {
      return;
    }

    while (attrCount > 0 && getAttributeNameResId(attrStart) == -1) {
      --attrCount;
      attrStart += ATTRIBUTE_SIZE;
    }

    while (attrCount > 0
        && getAttributeNameResId(attrStart + ATTRIBUTE_SIZE * (attrCount - 1)) == -1) {
      --attrCount;
    }

    if (attrCount < 2) {
      return;
    }

    class AttrRef implements Comparable<AttrRef> {
      final int offset;
      final int resId;

      AttrRef(int offset) {
        this.offset = offset;
        this.resId = getAttributeNameResId(offset);
      }

      @Override
      public int compareTo(AttrRef other) {
        return resId - other.resId;
      }
    }

    byte[] newData = new byte[ATTRIBUTE_SIZE * attrCount];
    ByteBuffer newBuf = wrap(newData);
    int finalAttrStart = attrStart;
    IntStream.range(0, attrCount)
        .mapToObj(i -> new AttrRef(finalAttrStart + ATTRIBUTE_SIZE * i))
        .sorted()
        .forEachOrdered(ref -> newBuf.put(slice(nodeBuf, ref.offset, ATTRIBUTE_SIZE)));
    slice(nodeBuf, attrStart).put(newData);
  }

  public void visitReferences(RefVisitor visitor) {
    transformReferences(
        i -> {
          visitor.visit(i);
          return i;
        });
  }

  public void dump(PrintStream out) {
    int indent = 0;
    Map<String, String> nsMap = new HashMap<>();
    nodeBuf.position(0);
    while (nodeBuf.position() < nodeBuf.limit()) {
      int nodeSize = nodeBuf.get(nodeBuf.position() + 4) & 0xFF;
      indent =
          dumpNode(
              out, strings, refMap, indent, nsMap, slice(nodeBuf, nodeBuf.position(), nodeSize));
      nodeBuf.position(nodeBuf.position() + nodeSize);
    }
  }

  private static int dumpNode(
      PrintStream out,
      StringPool strings,
      Optional<RefMap> refMap,
      int indent,
      Map<String, String> nsMap,
      ByteBuffer buf) {
    int type = buf.getShort(0);
    Preconditions.checkState(type >= XML_FIRST_TYPE && type <= XML_LAST_TYPE);
    switch (type) {
      case XML_START_NS:
        {
          // start namespace
          int prefixId = buf.getInt(16);
          String prefix = prefixId == -1 ? "" : strings.getString(prefixId);
          int uriId = buf.getInt(20);
          String uri = strings.getString(uriId);
          out.format("%sN: %s=%s\n", Strings.padEnd("", indent * 2, ' '), prefix, uri);
          indent++;
          nsMap.put(uri, prefix);
          break;
        }
      case XML_END_NS:
        indent--;
        break;
      case XML_START_ELEMENT:
        {
          // start element
          int lineNumber = buf.getInt(8);
          int nameId = buf.getInt(20);
          String name = strings.getString(nameId);
          int attrCount = buf.getShort(28);
          ByteBuffer attrExt = slice(buf, 36);
          out.format("%sE: %s (line=%d)\n", Strings.padEnd("", indent * 2, ' '), name, lineNumber);
          indent++;
          for (int i = 0; i < attrCount; i++) {
            dumpAttribute(out, strings, refMap, slice(attrExt, attrExt.position()), indent, nsMap);
            attrExt.position(attrExt.position() + 20);
          }
          break;
        }
      case XML_END_ELEMENT:
        indent--;
        break;
      case XML_CDATA:
        throw new UnsupportedOperationException();
    }
    return indent;
  }

  public StringPool getStrings() {
    return strings;
  }

  private static void dumpAttribute(
      PrintStream out,
      StringPool strings,
      Optional<RefMap> refMap,
      ByteBuffer buf,
      int indent,
      Map<String, String> nsMap) {
    int nsId = buf.getInt(0);
    String namespace = nsId == -1 ? "" : strings.getString(nsId);
    String shortNs = nsMap.get(namespace);
    int nameIndex = buf.getInt(4);
    String name = strings.getString(nameIndex);
    int resValue = refMap.isPresent() ? refMap.get().getRef(nameIndex) : -1;
    int rawValueIndex = buf.getInt(8);
    String rawValue = rawValueIndex < 0 ? null : strings.getOutputNormalizedString(rawValueIndex);
    int attrType = buf.get(15);
    int data = buf.getInt(16);
    String dumpValue = getValueForDump(strings, rawValue, attrType, data);
    out.format(
        "%sA: %s%s%s=%s%s\n",
        Strings.padEnd("", indent * 2, ' '),
        shortNs == null ? "" : shortNs + ":",
        name,
        resValue == -1 ? "" : String.format("(0x%08x)", resValue),
        dumpValue,
        rawValue == null ? "" : String.format(" (Raw: \"%s\")", rawValue));
  }

  @Nullable
  private static String getValueForDump(
      StringPool strings, @Nullable String rawValue, int attrType, int data) {
    switch (attrType) {
      case RES_REFERENCE:
        return String.format("@0x%x", data);
      case RES_STRING:
        return String.format("\"%s\"", strings.getString(data).replace("\\", "\\\\"));
      case RES_FLOAT:
      case RES_DECIMAL:
      case RES_HEX:
      case RES_BOOL:
        return String.format("(type 0x%x)0x%x", attrType, data);
      case RES_DYNAMIC_ATTRIBUTE:
      case RES_DYNAMIC_REFERENCE:
        throw new UnsupportedOperationException();
      default:
        return rawValue;
    }
  }

  /**
   * A RefMap is an optional entry in a compiled xml that provides the reference ids for some of the
   * strings in the stringpool. It consists of ResTable_header u32 chunk_type u32 header_size u32
   * chunk_size
   *
   * <p>The header is followed by an array of u32 ids of size (chunk_size - header_size) / 4. Each
   * entry is the reference id for the string with the same position in the string pool.
   */
  private static class RefMap extends ResChunk {
    private final ByteBuffer buf;
    private final int refCount;

    RefMap(ByteBuffer buf) {
      super(buf.getShort(), buf.getShort(), buf.getInt());
      this.buf = slice(buf, 0, getChunkSize());
      this.refCount = (getChunkSize() - getHeaderSize()) / 4;
      Preconditions.checkState(refCount >= 0);
    }

    @Override
    public void put(ByteBuffer output) {
      output.put(slice(buf, 0));
    }

    int getRef(int index) {
      if (index < refCount) {
        return buf.getInt(getHeaderSize() + index * 4);
      }
      return -1;
    }

    public void visitReferences(RefTransformer visitor) {
      for (int i = 0; i < refCount; i++) {
        transformEntryDataOffset(buf, getHeaderSize() + i * 4, visitor);
      }
    }
  }
}
