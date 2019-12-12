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

import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;

// CHECKSTYLE.OFF: LocalVariableName
// CHECKSTYLE.OFF: ParameterName
public class ElfSymbolTable {

  public final ImmutableList<Entry> entries;

  public ElfSymbolTable(ImmutableList<Entry> entries) {
    this.entries = entries;
  }

  public static ElfSymbolTable parse(ElfHeader.EIClass eiClass, ByteBuffer buffer) {
    ImmutableList.Builder<Entry> entries = ImmutableList.builder();
    while (buffer.hasRemaining()) {
      entries.add(Entry.parse(eiClass, buffer));
    }
    return new ElfSymbolTable(entries.build());
  }

  public void write(ElfHeader.EIClass eiClass, ByteBuffer buffer) {
    for (Entry entry : entries) {
      entry.write(eiClass, buffer);
    }
  }

  /** Encapsulate the data in an ELF section header. */
  public static class Entry {

    // CHECKSTYLE.OFF: MemberName
    public final long st_name;
    public final Info st_info;
    public final int st_other;
    public final int st_shndx;
    public final long st_value;
    public final long st_size;
    // CHECKSTYLE.ON: MemberName

    public Entry(
        long st_name, Info st_info, int st_other, int st_shndx, long st_value, long st_size) {
      this.st_name = st_name;
      this.st_info = st_info;
      this.st_other = st_other;
      this.st_shndx = st_shndx;
      this.st_value = st_value;
      this.st_size = st_size;
    }

    /**
     * @return either a 32- or 64-bit ELF symbol table entry header parsed from the given buffer.
     */
    static Entry parse(ElfHeader.EIClass eiClass, ByteBuffer buffer) {
      if (eiClass == ElfHeader.EIClass.ELFCLASS32) {
        long st_name = Elf.Elf32.getElf32Word(buffer);
        long st_value = Elf.Elf32.getElf32Addr(buffer);
        long st_size = Elf.Elf32.getElf32Word(buffer);
        Info st_info = Info.parse(buffer);
        int st_other = buffer.get();
        int st_shndx = Elf.Elf32.getElf32Half(buffer);
        return new Entry(st_name, st_info, st_other, st_shndx, st_value, st_size);
      } else {
        long st_name = Elf.Elf64.getElf64Word(buffer);
        Info st_info = Info.parse(buffer);
        int st_other = buffer.get();
        int st_shndx = Elf.Elf64.getElf64Half(buffer);
        long st_value = Elf.Elf64.getElf64Addr(buffer);
        long st_size = Elf.Elf64.getElf64Xword(buffer);
        return new Entry(st_name, st_info, st_other, st_shndx, st_value, st_size);
      }
    }

    void write(ElfHeader.EIClass eiClass, ByteBuffer buffer) {
      if (eiClass == ElfHeader.EIClass.ELFCLASS32) {
        Elf.Elf32.putElf32Word(buffer, (int) st_name);
        Elf.Elf32.putElf32Addr(buffer, (int) st_value);
        Elf.Elf32.putElf32Word(buffer, (int) st_size);
        st_info.write(buffer);
        buffer.put((byte) st_other);
        Elf.Elf32.putElf32Half(buffer, (short) st_shndx);
      } else {
        Elf.Elf64.putElf64Word(buffer, (int) st_name);
        st_info.write(buffer);
        buffer.put((byte) st_other);
        Elf.Elf64.putElf64Half(buffer, (short) st_shndx);
        Elf.Elf64.putElf64Addr(buffer, st_value);
        Elf.Elf64.putElf64Xword(buffer, st_size);
      }
    }

    public Entry withSize(long size) {
      return new Entry(st_name, st_info, st_other, st_shndx, st_value, size);
    }

    public static class Info {

      // CHECKSTYLE.OFF: MemberName
      public final Bind st_bind;
      public final Type st_type;
      // CHECKSTYLE.ON: MemberName

      public Info(Bind st_bind, Type st_type) {
        this.st_bind = st_bind;
        this.st_type = st_type;
      }

      public static Info parse(ByteBuffer buffer) {
        int st_info = buffer.get();
        return new Info(Bind.ofIntValue(st_info >> 4), Type.ofIntValue(st_info & 0xF));
      }

      public void write(ByteBuffer buffer) {
        buffer.put((byte) ((st_bind.value << 4) + (st_type.value & 0xF)));
      }

      public enum Bind {
        STB_LOCAL(0),
        STB_GLOBAL(1),
        STB_WEAK(2),
        ;

        private int value;

        Bind(int value) {
          this.value = value;
        }

        public static Bind ofIntValue(int val) {
          for (Bind bind : values()) {
            if (bind.value == val) {
              return bind;
            }
          }
          throw new IllegalArgumentException();
        }
      }

      public enum Type {
        STT_NOTYPE(0),
        STT_OBJECT(1),
        STT_FUNC(2),
        STT_SECTION(3),
        STT_FILE(4),
        STT_COMMON(5),
        STT_TLS(6),
        ;

        private int value;

        Type(int value) {
          this.value = value;
        }

        public static Type ofIntValue(int val) {
          for (Type type : values()) {
            if (type.value == val) {
              return type;
            }
          }
          throw new IllegalArgumentException();
        }
      }
    }
  }
}
