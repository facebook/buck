/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx.elf;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Encapsulate the data found in an ELF header.
 */
public class ElfHeader {

  public static final int EI_MAG0 = 0;
  public static final int EI_MAG1 = 1;
  public static final int EI_MAG2 = 2;
  public static final int EI_MAG3 = 3;
  public static final int EI_CLASS = 4;
  public static final int EI_DATA = 5;
  public static final int EI_VERSION = 6;
  public static final int EI_PAD = 7;
  public static final int EI_NIDENT = 16;

  public static final byte ELFMAG0 = 0x7f;
  public static final byte ELFMAG1 = 'E';
  public static final byte ELFMAG2 = 'L';
  public static final byte ELFMAG3 = 'F';

  public final EIClass ei_class;
  public final EIData ei_data;
  public final short e_type;
  public final short e_machine;
  public final int e_version;
  public final long e_entry;
  public final long e_phoff;
  public final long e_shoff;
  public final int e_flags;
  public final short e_ehsize;
  public final short e_phentsize;
  public final short e_phnum;
  public final short e_shentsize;
  public final short e_shnum;
  public final short e_shstrndx;

  ElfHeader(
      EIClass ei_class,
      EIData ei_data,
      short e_type,
      short e_machine,
      int e_version,
      long e_entry,
      long e_phoff,
      long e_shoff,
      int e_flags,
      short e_ehsize,
      short e_phentsize,
      short e_phnum,
      short e_shentsize,
      short e_shnum,
      short e_shstrndx) {
    this.ei_class = ei_class;
    this.ei_data = ei_data;
    this.e_type = e_type;
    this.e_machine = e_machine;
    this.e_version = e_version;
    this.e_entry = e_entry;
    this.e_phoff = e_phoff;
    this.e_shoff = e_shoff;
    this.e_flags = e_flags;
    this.e_ehsize = e_ehsize;
    this.e_phentsize = e_phentsize;
    this.e_phnum = e_phnum;
    this.e_shentsize = e_shentsize;
    this.e_shnum = e_shnum;
    this.e_shstrndx = e_shstrndx;
  }

  /**
   * @return a {@link ElfHeader} parsed from the given buffer.
   */
  static ElfHeader parse(ByteBuffer buffer) {
    byte[] e_ident = new byte[EI_NIDENT];
    buffer.get(e_ident);

    // Read the data field to determine endian-ness.  We use this to set the byte order
    // for the underlying buffer.
    EIData ei_data = EIData.valueOf(e_ident[EI_DATA]);
    ei_data.setOrder(buffer);

    // Read the class field to determine whether we're parsing for 32-bit or 64-bit.
    EIClass ei_class = EIClass.valueOf(e_ident[EI_CLASS]);
    return ei_class.parseHeader(ei_data, buffer);
  }

  public static enum EIClass {

    ELFCLASSNONE(0) {
      @Override
      ElfHeader parseHeader(EIData ei_data, ByteBuffer buffer) {
        throw new IllegalStateException();
      }
    },

    ELFCLASS32(1) {
      @Override
      ElfHeader parseHeader(EIData ei_data, ByteBuffer buffer) {
        return new ElfHeader(
            this,
            ei_data,
            buffer.getShort(),
            buffer.getShort(),
            buffer.getInt(),
            buffer.getInt(),
            buffer.getInt(),
            buffer.getInt(),
            buffer.getInt(),
            buffer.getShort(),
            buffer.getShort(),
            buffer.getShort(),
            buffer.getShort(),
            buffer.getShort(),
            buffer.getShort());
      }
    },

    ELFCLASS64(2) {
      @Override
      ElfHeader parseHeader(EIData ei_data, ByteBuffer buffer) {
        return new ElfHeader(
            this,
            ei_data,
            buffer.getShort(),
            buffer.getShort(),
            buffer.getInt(),
            buffer.getLong(),
            buffer.getLong(),
            buffer.getLong(),
            buffer.getInt(),
            buffer.getShort(),
            buffer.getShort(),
            buffer.getShort(),
            buffer.getShort(),
            buffer.getShort(),
            buffer.getShort());
      }
    }

    ;

    private final int value;

    private EIClass(int value) {
      this.value = value;
    }

    static EIClass valueOf(int val) {
      for (EIClass clazz : EIClass.values()) {
        if (clazz.value == val) {
          return clazz;
        }
      }
      throw new IllegalStateException();
    }

    abstract ElfHeader parseHeader(EIData ei_data, ByteBuffer buffer);

  }

  public static enum EIData {

    ELFDATANONE(0) {
      @Override
      void setOrder(ByteBuffer buffer) {
        throw new IllegalStateException();
      }
    },

    ELFDATA2LSB(1) {
      @Override
      void setOrder(ByteBuffer buffer) {
        buffer.order(ByteOrder.LITTLE_ENDIAN);
      }
    },

    ELFDATA2MSB(2) {
      @Override
      void setOrder(ByteBuffer buffer) {
        buffer.order(ByteOrder.BIG_ENDIAN);
      }
    },

    ;

    private final int value;

    private EIData(int value) {
      this.value = value;
    }

    abstract void setOrder(ByteBuffer buffer);

    static EIData valueOf(int val) {
      for (EIData data : EIData.values()) {
        if (data.value == val) {
          return data;
        }
      }
      throw new IllegalStateException();
    }

  }

}
