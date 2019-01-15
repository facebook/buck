/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.cxx.toolchain.elf;

import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import java.util.Optional;

public class ElfDynamicSection {

  public final ImmutableList<Entry> entries;

  public ElfDynamicSection(ImmutableList<Entry> entries) {
    this.entries = entries;
  }

  public static ElfDynamicSection parse(ElfHeader.EIClass eiClass, ByteBuffer buffer) {
    ImmutableList.Builder<Entry> entries = ImmutableList.builder();
    while (buffer.hasRemaining()) {
      entries.add(Entry.parse(eiClass, buffer));
    }
    return new ElfDynamicSection(entries.build());
  }

  public void write(ElfHeader.EIClass eiClass, ByteBuffer buffer) {
    for (Entry entry : entries) {
      entry.write(eiClass, buffer);
    }
  }

  public Optional<Long> lookup(DTag dTag) {
    return entries.stream().filter(e -> e.d_tag == dTag).map(e -> e.d_un).findFirst();
  }

  public enum DTag {
    DT_NULL(0, Type.OTHER),
    DT_NEEDED(1, Type.STRING),
    DT_PLTRELSZ(2, Type.OTHER),
    DT_PLTGOT(3, Type.OTHER),
    DT_HASH(4, Type.OTHER),
    DT_STRTAB(5, Type.OTHER),
    DT_SYMTAB(6, Type.OTHER),
    DT_RELA(7, Type.OTHER),
    DT_RELASZ(8, Type.OTHER),
    DT_RELAENT(9, Type.OTHER),
    DT_STRSZ(10, Type.OTHER),
    DT_SYMENT(11, Type.OTHER),
    DT_INIT(12, Type.OTHER),
    DT_FINI(13, Type.OTHER),
    DT_SONAME(14, Type.STRING),
    DT_RPATH(15, Type.STRING),
    DT_SYMBOLIC(16, Type.OTHER),
    DT_REL(17, Type.OTHER),
    DT_RELSZ(18, Type.OTHER),
    DT_RELENT(19, Type.OTHER),
    DT_PLTREL(20, Type.OTHER),
    DT_DEBUG(21, Type.OTHER),
    DT_TEXTREL(22, Type.OTHER),
    DT_JMPREL(23, Type.OTHER),
    DT_BIND_NOW(24, Type.OTHER),
    DT_INIT_ARRAY(25, Type.OTHER),
    DT_FINI_ARRAY(26, Type.OTHER),
    DT_INIT_ARRAYSZ(27, Type.OTHER),
    DT_FINI_ARRAYSZ(28, Type.OTHER),
    DT_RUNPATH(29, Type.STRING),
    DT_FLAGS(30, Type.OTHER),
    DT_PREINIT_ARRAY(32, Type.OTHER),
    DT_PREINIT_ARRAYSZ(33, Type.OTHER),
    DT_GNU_HASH(0x6ffffef5, Type.OTHER),
    DT_TLSDESC_PLT(0x6ffffef6, Type.OTHER),
    DT_TLSDESC_GOT(0x6ffffef7, Type.OTHER),
    DT_VERSYM(0x6ffffff0, Type.OTHER),
    DT_RELACOUNT(0x6ffffff9, Type.OTHER),
    DT_RELCOUNT(0x6ffffffa, Type.OTHER),
    DT_FLAGS_1(0x6ffffffb, Type.OTHER),
    DT_VERDEF(0x6ffffffc, Type.OTHER),
    DT_VERDEFNUM(0x6ffffffd, Type.OTHER),
    DT_VERNEED(0x6ffffffe, Type.OTHER),
    DT_VERNEEDNUM(0x6fffffff, Type.OTHER),
    ;

    private final int value;
    private final Type type;

    DTag(int value, Type type) {
      this.value = value;
      this.type = type;
    }

    public static DTag valueOf(int val) {
      for (DTag clazz : DTag.values()) {
        if (clazz.value == val) {
          return clazz;
        }
      }
      throw new IllegalArgumentException(String.format("unknown dynamic tag: %s", val));
    }

    public int getValue() {
      return value;
    }

    public Type getType() {
      return type;
    }

    public enum Type {
      STRING,
      OTHER,
    }
  }

  public static class Entry {

    public final DTag d_tag;
    public final long d_un;

    public Entry(DTag d_tag, long d_un) {
      this.d_tag = d_tag;
      this.d_un = d_un;
    }

    private static Entry parse(ElfHeader.EIClass eiClass, ByteBuffer buffer) {
      if (eiClass == ElfHeader.EIClass.ELFCLASS32) {
        return new Entry(
            DTag.valueOf(Elf.Elf32.getElf32Sword(buffer)), Elf.Elf32.getElf32Addr(buffer));
      } else {
        return new Entry(
            DTag.valueOf((int) Elf.Elf64.getElf64Sxword(buffer)), Elf.Elf64.getElf64Addr(buffer));
      }
    }

    private void write(ElfHeader.EIClass eiClass, ByteBuffer buffer) {
      if (eiClass == ElfHeader.EIClass.ELFCLASS32) {
        Elf.Elf32.putElf32Sword(buffer, d_tag.value);
        Elf.Elf32.putElf32Addr(buffer, (int) d_un);
      } else {
        Elf.Elf64.putElf64Sxword(buffer, d_tag.value);
        Elf.Elf64.putElf64Addr(buffer, d_un);
      }
    }
  }
}
