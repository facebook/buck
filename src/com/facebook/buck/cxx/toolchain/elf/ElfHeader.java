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

package com.facebook.buck.cxx.toolchain.elf;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Encapsulate the data found in an ELF header. */
// CHECKSTYLE.OFF: LocalVariableName
// CHECKSTYLE.OFF: ParameterName
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

  // CHECKSTYLE.OFF: MemberName
  public final EIClass ei_class;
  public final EIData ei_data;
  public final byte[] e_ident;
  public final int e_type;
  public final int e_machine;
  public final long e_version;
  public final long e_entry;
  public final long e_phoff;
  public final long e_shoff;
  public final long e_flags;
  public final int e_ehsize;
  public final int e_phentsize;
  public final int e_phnum;
  public final int e_shentsize;
  public final int e_shnum;
  public final int e_shstrndx;
  // CHECKSTYLE.ON: MemberName

  ElfHeader(
      EIClass ei_class,
      EIData ei_data,
      byte[] e_ident,
      int e_type,
      int e_machine,
      long e_version,
      long e_entry,
      long e_phoff,
      long e_shoff,
      long e_flags,
      int e_ehsize,
      int e_phentsize,
      int e_phnum,
      int e_shentsize,
      int e_shnum,
      int e_shstrndx) {
    this.ei_class = ei_class;
    this.ei_data = ei_data;
    this.e_ident = e_ident;
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

  private static long getUnsignedInt(ByteBuffer buffer) {
    return (buffer.getInt() & 0xffffffffL);
  }

  private static int getUnsignedShort(ByteBuffer buffer) {
    return (buffer.getShort() & 0xffff);
  }

  /** @return a {@link ElfHeader} parsed from the given buffer. */
  static ElfHeader parse(ByteBuffer buffer) {
    byte[] e_ident = new byte[EI_NIDENT];
    buffer.get(e_ident);

    // Read the data field to determine endian-ness.  We use this to set the byte order
    // for the underlying buffer.
    EIData ei_data = EIData.valueOf(e_ident[EI_DATA]);
    ei_data.setOrder(buffer);

    // Read the class field to determine whether we're parsing for 32-bit or 64-bit.
    EIClass ei_class = EIClass.valueOf(e_ident[EI_CLASS]);
    return ei_class.parseHeader(ei_data, e_ident, buffer);
  }

  public void write(ByteBuffer buffer) {

    // Write out the identifier array.
    buffer.put(e_ident);

    if (ei_class == EIClass.ELFCLASS32) {
      Elf.Elf32.putElf32Half(buffer, (short) e_type);
      Elf.Elf32.putElf32Half(buffer, (short) e_machine);
      Elf.Elf32.putElf32Word(buffer, (int) e_version);
      Elf.Elf32.putElf32Addr(buffer, (int) e_entry);
      Elf.Elf32.putElf32Addr(buffer, (int) e_phoff);
      Elf.Elf32.putElf32Addr(buffer, (int) e_shoff);
      Elf.Elf32.putElf32Word(buffer, (int) e_flags);
      Elf.Elf32.putElf32Half(buffer, (short) e_ehsize);
      Elf.Elf32.putElf32Half(buffer, (short) e_phentsize);
      Elf.Elf32.putElf32Half(buffer, (short) e_phnum);
      Elf.Elf32.putElf32Half(buffer, (short) e_shentsize);
      Elf.Elf32.putElf32Half(buffer, (short) e_shnum);
      Elf.Elf32.putElf32Half(buffer, (short) e_shstrndx);
    } else {
      Elf.Elf64.putElf64Half(buffer, (short) e_type);
      Elf.Elf64.putElf64Half(buffer, (short) e_machine);
      Elf.Elf64.putElf64Word(buffer, (int) e_version);
      Elf.Elf64.putElf64Addr(buffer, e_entry);
      Elf.Elf64.putElf64Addr(buffer, e_phoff);
      Elf.Elf64.putElf64Addr(buffer, e_shoff);
      Elf.Elf64.putElf64Word(buffer, (int) e_flags);
      Elf.Elf64.putElf64Half(buffer, (short) e_ehsize);
      Elf.Elf64.putElf64Half(buffer, (short) e_phentsize);
      Elf.Elf64.putElf64Half(buffer, (short) e_phnum);
      Elf.Elf64.putElf64Half(buffer, (short) e_shentsize);
      Elf.Elf64.putElf64Half(buffer, (short) e_shnum);
      Elf.Elf64.putElf64Half(buffer, (short) e_shstrndx);
    }
  }

  public ElfHeader withEntry(long e_entry) {
    return new ElfHeader(
        ei_class,
        ei_data,
        e_ident,
        e_type,
        e_machine,
        e_version,
        e_entry,
        e_phoff,
        e_shoff,
        e_flags,
        e_ehsize,
        e_phentsize,
        e_phnum,
        e_shentsize,
        e_shnum,
        e_shstrndx);
  }

  public enum EIClass {
    ELFCLASSNONE(0) {
      @Override
      ElfHeader parseHeader(EIData ei_data, byte[] e_ident, ByteBuffer buffer) {
        throw new IllegalStateException();
      }
    },

    ELFCLASS32(1) {
      @Override
      ElfHeader parseHeader(EIData ei_data, byte[] e_ident, ByteBuffer buffer) {
        return new ElfHeader(
            this,
            ei_data,
            e_ident,
            getUnsignedShort(buffer),
            getUnsignedShort(buffer),
            getUnsignedInt(buffer),
            getUnsignedInt(buffer),
            getUnsignedInt(buffer),
            getUnsignedInt(buffer),
            getUnsignedInt(buffer),
            getUnsignedShort(buffer),
            getUnsignedShort(buffer),
            getUnsignedShort(buffer),
            getUnsignedShort(buffer),
            getUnsignedShort(buffer),
            getUnsignedShort(buffer));
      }
    },

    ELFCLASS64(2) {
      @Override
      ElfHeader parseHeader(EIData ei_data, byte[] e_ident, ByteBuffer buffer) {
        return new ElfHeader(
            this,
            ei_data,
            e_ident,
            getUnsignedShort(buffer),
            getUnsignedShort(buffer),
            getUnsignedInt(buffer),
            buffer.getLong(),
            buffer.getLong(),
            buffer.getLong(),
            getUnsignedInt(buffer),
            getUnsignedShort(buffer),
            getUnsignedShort(buffer),
            getUnsignedShort(buffer),
            getUnsignedShort(buffer),
            getUnsignedShort(buffer),
            getUnsignedShort(buffer));
      }
    };

    private final int value;

    EIClass(int value) {
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

    abstract ElfHeader parseHeader(EIData ei_data, byte[] e_ident, ByteBuffer buffer);
  }

  public enum EIData {
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

    EIData(int value) {
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

// CHECKSTYLE.ON: ParameterName
// CHECKSTYLE.ON: LocalVariableName
