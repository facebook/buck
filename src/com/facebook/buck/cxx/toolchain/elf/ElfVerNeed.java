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

import com.facebook.buck.util.MoreIterables;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;

public class ElfVerNeed {

  public final ImmutableList<Pair<Verneed, ImmutableList<Vernaux>>> entries;

  public ElfVerNeed(ImmutableList<Pair<Verneed, ImmutableList<Vernaux>>> entries) {
    this.entries = entries;
  }

  public static ElfVerNeed parse(ElfHeader.EIClass eiClass, ByteBuffer buffer) {
    ImmutableList.Builder<Pair<Verneed, ImmutableList<Vernaux>>> entries = ImmutableList.builder();
    int vnPos = buffer.position();
    while (true) {
      buffer.position(vnPos);
      Verneed verneed = Verneed.parse(eiClass, buffer);
      int vnaPos = vnPos + (int) verneed.vn_aux;
      ImmutableList.Builder<Vernaux> vernauxEntries = ImmutableList.builder();
      for (int j = 0; j < verneed.vn_cnt; j++) {
        buffer.position(vnaPos);
        Vernaux vernaux = Vernaux.parse(eiClass, buffer);
        vernauxEntries.add(vernaux);
        vnaPos += (int) vernaux.vna_next;
      }
      entries.add(new Pair<>(verneed, vernauxEntries.build()));
      if (verneed.vn_next == 0) {
        break;
      }
      vnPos += verneed.vn_next;
    }
    return new ElfVerNeed(entries.build());
  }

  public void write(ElfHeader.EIClass eiClass, ByteBuffer buffer) {
    int vnPos = buffer.position();
    for (Pair<Verneed, ImmutableList<Vernaux>> entry : entries) {
      Verneed verneed = entry.getFirst();
      ImmutableList<Vernaux> vernauxEntries = entry.getSecond();
      buffer.position(vnPos);
      verneed.write(eiClass, buffer);
      int vnaPos = vnPos + (int) verneed.vn_aux;
      for (Vernaux vernaux : vernauxEntries) {
        buffer.position(vnaPos);
        vernaux.write(eiClass, buffer);
        vnaPos += vernaux.vna_next;
      }
      vnPos += verneed.vn_next;
    }
  }

  public ElfVerNeed compact() {
    return new ElfVerNeed(
        RichStream.from(MoreIterables.enumerate(this.entries))
            .map(
                vnp -> {
                  int verneedIndex = vnp.getFirst();
                  ElfVerNeed.Verneed verneed = vnp.getSecond().getFirst();
                  ImmutableList<ElfVerNeed.Vernaux> vernauxes = vnp.getSecond().getSecond();
                  return new Pair<>(
                      new ElfVerNeed.Verneed(
                          verneed.vn_version,
                          vernauxes.size(),
                          verneed.vn_file,
                          vernauxes.size() == 0 ? 0 : ElfVerNeed.Verneed.BYTES,
                          verneedIndex == entries.size() - 1
                              ? 0
                              : ElfVerNeed.Verneed.BYTES
                                  + ElfVerNeed.Vernaux.BYTES * vernauxes.size()),
                      RichStream.from(MoreIterables.enumerate(vernauxes))
                          .map(
                              vnxp ->
                                  new ElfVerNeed.Vernaux(
                                      vnxp.getSecond().vna_hash,
                                      vnxp.getSecond().vna_flags,
                                      vnxp.getSecond().vna_other,
                                      vnxp.getSecond().vna_name,
                                      vnxp.getFirst() == vernauxes.size() - 1
                                          ? 0
                                          : ElfVerNeed.Vernaux.BYTES))
                          .toImmutableList());
                })
            .toImmutableList());
  }

  public static class Verneed {

    private static final int BYTES = 16;

    // CHECKSTYLE.OFF: MemberName
    public final int vn_version;
    public final int vn_cnt;
    public final long vn_file;
    public final long vn_aux;
    public final long vn_next;
    // CHECKSTYLE.ON: MemberName

    // CHECKSTYLE.OFF: ParameterName
    public Verneed(int vn_version, int vn_cnt, long vn_file, long vn_aux, long vn_next) {
      this.vn_version = vn_version;
      this.vn_cnt = vn_cnt;
      this.vn_file = vn_file;
      this.vn_aux = vn_aux;
      this.vn_next = vn_next;
    }
    // CHECKSTYLE.ON: ParameterName

    private static Verneed parse(ElfHeader.EIClass eiClass, ByteBuffer buffer) {
      if (eiClass == ElfHeader.EIClass.ELFCLASS32) {
        return new Verneed(
            Elf.Elf32.getElf32Half(buffer),
            Elf.Elf32.getElf32Half(buffer),
            Elf.Elf32.getElf32Word(buffer),
            Elf.Elf32.getElf32Word(buffer),
            Elf.Elf32.getElf32Word(buffer));
      } else {
        return new Verneed(
            Elf.Elf64.getElf64Half(buffer),
            Elf.Elf64.getElf64Half(buffer),
            Elf.Elf64.getElf64Word(buffer),
            Elf.Elf64.getElf64Word(buffer),
            Elf.Elf64.getElf64Word(buffer));
      }
    }

    private void write(ElfHeader.EIClass eiClass, ByteBuffer buffer) {
      if (eiClass == ElfHeader.EIClass.ELFCLASS32) {
        Elf.Elf32.putElf32Half(buffer, (short) vn_version);
        Elf.Elf32.putElf32Half(buffer, (short) vn_cnt);
        Elf.Elf32.putElf32Word(buffer, (int) vn_file);
        Elf.Elf32.putElf32Word(buffer, (int) vn_aux);
        Elf.Elf32.putElf32Word(buffer, (int) vn_next);
      } else {
        Elf.Elf64.putElf64Half(buffer, (short) vn_version);
        Elf.Elf64.putElf64Half(buffer, (short) vn_cnt);
        Elf.Elf64.putElf64Word(buffer, (int) vn_file);
        Elf.Elf64.putElf64Word(buffer, (int) vn_aux);
        Elf.Elf64.putElf64Word(buffer, (int) vn_next);
      }
    }
  }

  public static class Vernaux {

    private static final int BYTES = 16;

    // CHECKSTYLE.OFF: MemberName
    public final long vna_hash;
    public final int vna_flags;
    public final int vna_other;
    public final long vna_name;
    public final long vna_next;
    // CHECKSTYLE.ON: MemberName

    // CHECKSTYLE.OFF: ParameterName
    public Vernaux(long vna_hash, int vna_flags, int vna_other, long vna_name, long vna_next) {
      this.vna_hash = vna_hash;
      this.vna_flags = vna_flags;
      this.vna_other = vna_other;
      this.vna_name = vna_name;
      this.vna_next = vna_next;
    }
    // CHECKSTYLE.ON: ParameterName

    private static Vernaux parse(ElfHeader.EIClass eiClass, ByteBuffer buffer) {
      if (eiClass == ElfHeader.EIClass.ELFCLASS32) {
        return new Vernaux(
            Elf.Elf32.getElf32Word(buffer),
            Elf.Elf32.getElf32Half(buffer),
            Elf.Elf32.getElf32Half(buffer),
            Elf.Elf32.getElf32Word(buffer),
            Elf.Elf32.getElf32Word(buffer));
      } else {
        return new Vernaux(
            Elf.Elf64.getElf64Word(buffer),
            Elf.Elf64.getElf64Half(buffer),
            Elf.Elf64.getElf64Half(buffer),
            Elf.Elf64.getElf64Word(buffer),
            Elf.Elf64.getElf64Word(buffer));
      }
    }

    private void write(ElfHeader.EIClass eiClass, ByteBuffer buffer) {
      if (eiClass == ElfHeader.EIClass.ELFCLASS32) {
        Elf.Elf32.putElf32Word(buffer, (int) vna_hash);
        Elf.Elf32.putElf32Half(buffer, (short) vna_flags);
        Elf.Elf32.putElf32Half(buffer, (short) vna_other);
        Elf.Elf32.putElf32Word(buffer, (int) vna_name);
        Elf.Elf32.putElf32Word(buffer, (int) vna_next);
      } else {
        Elf.Elf64.putElf64Word(buffer, (int) vna_hash);
        Elf.Elf64.putElf64Half(buffer, (short) vna_flags);
        Elf.Elf64.putElf64Half(buffer, (short) vna_other);
        Elf.Elf64.putElf64Word(buffer, (int) vna_name);
        Elf.Elf64.putElf64Word(buffer, (int) vna_next);
      }
    }
  }
}
