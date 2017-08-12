// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.dex;

public class Constants {

  public static final byte[] DEX_FILE_MAGIC_PREFIX = {'d', 'e', 'x', '\n'};
  public static final byte DEX_FILE_MAGIC_SUFFIX = '\0';

  public static final byte[] VDEX_FILE_MAGIC_PREFIX = {'v', 'd', 'e', 'x'};
  public static final byte[] VDEX_FILE_VERSION = {'0', '1', '0', '\0'};

  /** vdex file version number for Android O (API level 26) */
  public static final int ANDROID_O_VDEX_VERSION = 10;

  public static final int DEX_MAGIC_SIZE = 8;

  public static final int MAGIC_OFFSET = 0;
  public static final int CHECKSUM_OFFSET = MAGIC_OFFSET + DEX_MAGIC_SIZE;
  public static final int SIGNATURE_OFFSET = CHECKSUM_OFFSET + 4;
  public static final int FILE_SIZE_OFFSET = SIGNATURE_OFFSET + 20;
  public static final int HEADER_SIZE_OFFSET = FILE_SIZE_OFFSET + 4;
  public static final int ENDIAN_TAG_OFFSET = HEADER_SIZE_OFFSET + 4;
  public static final int LINK_SIZE_OFFSET = ENDIAN_TAG_OFFSET + 4;
  public static final int LINK_OFF_OFFSET = LINK_SIZE_OFFSET + 4;
  public static final int MAP_OFF_OFFSET = LINK_OFF_OFFSET + 4;
  public static final int STRING_IDS_SIZE_OFFSET = MAP_OFF_OFFSET + 4;
  public static final int STRING_IDS_OFF_OFFSET = STRING_IDS_SIZE_OFFSET + 4;
  public static final int TYPE_IDS_SIZE_OFFSET = STRING_IDS_OFF_OFFSET + 4;
  public static final int TYPE_IDS_OFF_OFFSET = TYPE_IDS_SIZE_OFFSET + 4;
  public static final int PROTO_IDS_SIZE_OFFSET = TYPE_IDS_OFF_OFFSET + 4;
  public static final int PROTO_IDS_OFF_OFFSET = PROTO_IDS_SIZE_OFFSET + 4;
  public static final int FIELD_IDS_SIZE_OFFSET = PROTO_IDS_OFF_OFFSET + 4;
  public static final int FIELD_IDS_OFF_OFFSET = FIELD_IDS_SIZE_OFFSET + 4;
  public static final int METHOD_IDS_SIZE_OFFSET = FIELD_IDS_OFF_OFFSET + 4;
  public static final int METHOD_IDS_OFF_OFFSET = METHOD_IDS_SIZE_OFFSET + 4;
  public static final int CLASS_DEFS_SIZE_OFFSET = METHOD_IDS_OFF_OFFSET + 4;
  public static final int CLASS_DEFS_OFF_OFFSET = CLASS_DEFS_SIZE_OFFSET + 4;
  public static final int DATA_SIZE_OFFSET = CLASS_DEFS_OFF_OFFSET + 4;
  public static final int DATA_OFF_OFFSET = DATA_SIZE_OFFSET + 4;

  public static final int ENDIAN_CONSTANT = 0x12345678;
  public static final int REVERSE_ENDIAN_CONSTANT = 0x78563412;

  public static final int TYPE_HEADER_ITEM = 0x0;
  public static final int TYPE_HEADER_ITEM_SIZE = 0x70;
  public static final int TYPE_STRING_ID_ITEM = 0x0001;
  public static final int TYPE_STRING_ID_ITEM_SIZE = 0x04;
  public static final int TYPE_TYPE_ID_ITEM = 0x0002;
  public static final int TYPE_TYPE_ID_ITEM_SIZE = 0x04;
  public static final int TYPE_PROTO_ID_ITEM = 0x0003;
  public static final int TYPE_PROTO_ID_ITEM_SIZE = 0x0c;
  public static final int TYPE_FIELD_ID_ITEM = 0x0004;
  public static final int TYPE_FIELD_ID_ITEM_SIZE = 0x08;
  public static final int TYPE_METHOD_ID_ITEM = 0x0005;
  public static final int TYPE_METHOD_ID_ITEM_SIZE = 0x08;
  public static final int TYPE_CLASS_DEF_ITEM = 0x0006;
  public static final int TYPE_CLASS_DEF_ITEM_SIZE = 0x20;
  public static final int TYPE_CALL_SITE_ID_ITEM = 0x0007;
  public static final int TYPE_CALL_SITE_ID_ITEM_SIZE = 0x04;
  public static final int TYPE_METHOD_HANDLE_ITEM = 0x0008;
  public static final int TYPE_METHOD_HANDLE_ITEM_SIZE = 0x0008;
  public static final int TYPE_MAP_LIST = 0x1000;
  public static final int TYPE_MAP_LIST_ITEM_SIZE = 0x0c;
  public static final int TYPE_TYPE_LIST = 0x1001;
  public static final int TYPE_ANNOTATION_SET_REF_LIST = 0x1002;
  public static final int TYPE_ANNOTATION_SET_ITEM = 0x1003;
  public static final int TYPE_CLASS_DATA_ITEM = 0x2000;
  public static final int TYPE_CODE_ITEM = 0x2001;
  public static final int TYPE_STRING_DATA_ITEM = 0x2002;
  public static final int TYPE_DEBUG_INFO_ITEM = 0x2003;
  public static final int TYPE_ANNOTATION_ITEM = 0x2004;
  public static final int TYPE_ENCODED_ARRAY_ITEM = 0x2005;
  public static final int TYPE_ANNOTATIONS_DIRECTORY_ITEM = 0x2006;

  public static final int DBG_START_LOCAL = 0x03;
  public static final int DBG_START_LOCAL_EXTENDED = 0x04;
  public static final int DBG_END_LOCAL = 0x05;
  public static final int DBG_RESTART_LOCAL = 0x06;
  public static final int DBG_SET_FILE = 0x09;
  public static final int DBG_END_SEQUENCE = 0x00;
  public static final int DBG_ADVANCE_PC = 0x01;
  public static final int DBG_ADVANCE_LINE = 0x02;
  public static final int DBG_SET_PROLOGUE_END = 0x07;
  public static final int DBG_SET_EPILOGUE_BEGIN = 0x08;
  public static final int DBG_FIRST_SPECIAL = 0x0a;
  public static final int DBG_LAST_SPECIAL = 0xff;
  public static final int DBG_LINE_BASE = -4;
  public static final int DBG_LINE_RANGE = 15;
  public static final int DBG_ADDRESS_RANGE = 16;

  public static final int VDEX_MAGIC_SIZE = 8;
  public static final int VDEX_MAGIC_OFFSET = 0;
  public static final int VDEX_HEADER_SIZE = 24;
  public static final int VDEX_NUMBER_OF_DEX_FILES_OFFSET = VDEX_MAGIC_SIZE;
  public static final int VDEX_CHECKSUM_SECTION_OFFSET = VDEX_HEADER_SIZE;
  public static final int VDEX_DEX_CHECKSUM_SIZE = 4;

  public static final int NO_OFFSET = 0;
  public static final int NO_INDEX = -1;

  public static final int S4BIT_SIGN_MASK = 1 << 3;
  public static final int S4BIT_MIN = -(1 << 3);
  public static final int S4BIT_MAX = (1 << 3) - 1;
  public static final int S8BIT_MIN = -(1 << 7);
  public static final int S8BIT_MAX = (1 << 7) - 1;

  public static final int U4BIT_MAX = (1 << 4) - 1;
  public static final int U8BIT_MAX = (1 << 8) - 1;
  public static final int U16BIT_MAX = (1 << 16) - 1;
  public static final long U32BIT_MAX = (1L << 32) - 1;
  public static final int ACC_PUBLIC = 0x1;
  public static final int ACC_PRIVATE = 0x2;
  public static final int ACC_PROTECTED = 0x4;
  public static final int ACC_STATIC = 0x8;
  public static final int ACC_FINAL = 0x10;
  public static final int ACC_SUPER = 0x20;
  public static final int ACC_SYNCHRONIZED = 0x20;
  public static final int ACC_VOLATILE = 0x40;
  public static final int ACC_BRIDGE = 0x40;
  public static final int ACC_TRANSIENT = 0x80;
  public static final int ACC_VARARGS = 0x80;
  public static final int ACC_NATIVE = 0x100;
  public static final int ACC_INTERFACE = 0x200;
  public static final int ACC_ABSTRACT = 0x400;
  public static final int ACC_STRICT = 0x800;
  public static final int ACC_SYNTHETIC = 0x1000;
  public static final int ACC_ANNOTATION = 0x2000;
  public static final int ACC_ENUM = 0x4000;
  public static final int ACC_CONSTRUCTOR = 0x10000;
  public static final int ACC_DECLARED_SYNCHRONIZED = 0x20000;

  public static final String JAVA_LANG_OBJECT_NAME = "java/lang/Object";
  public static final String INSTANCE_INITIALIZER_NAME = "<init>";
  public static final String CLASS_INITIALIZER_NAME = "<clinit>";

  public static final int MAX_NON_JUMBO_INDEX = U16BIT_MAX;

  public static final int KILOBYTE = 1 << 10;
}
