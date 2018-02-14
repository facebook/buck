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

public class ElfVerDef {

  public final ImmutableList<Pair<Verdef, ImmutableList<Verdaux>>> entries;

  public ElfVerDef(ImmutableList<Pair<Verdef, ImmutableList<Verdaux>>> entries) {
    this.entries = entries;
  }

  public static ElfVerDef parse(ElfHeader.EIClass eiClass, ByteBuffer buffer) {
    ImmutableList.Builder<Pair<Verdef, ImmutableList<Verdaux>>> entries = ImmutableList.builder();
    int vdPos = buffer.position();
    while (buffer.hasRemaining()) {
      buffer.position(vdPos);
      Verdef verdef = Verdef.parse(eiClass, buffer);
      int vdaPos = vdPos + (int) verdef.vd_aux;
      ImmutableList.Builder<Verdaux> verdauxEntries = ImmutableList.builder();
      for (int j = 0; j < verdef.vd_cnt; j++) {
        buffer.position(vdaPos);
        Verdaux verdaux = Verdaux.parse(eiClass, buffer);
        verdauxEntries.add(verdaux);
        vdaPos += (int) verdaux.vda_next;
      }
      entries.add(new Pair<>(verdef, verdauxEntries.build()));
      if (verdef.vd_next == 0) {
        break;
      }
      vdPos += verdef.vd_next;
    }
    return new ElfVerDef(entries.build());
  }

  public void write(ElfHeader.EIClass eiClass, ByteBuffer buffer) {
    int vdPos = buffer.position();
    for (Pair<Verdef, ImmutableList<Verdaux>> entry : entries) {
      Verdef verdef = entry.getFirst();
      ImmutableList<Verdaux> verdauxEntries = entry.getSecond();
      buffer.position(vdPos);
      verdef.write(eiClass, buffer);
      int vdaPos = vdPos + (int) verdef.vd_aux;
      for (Verdaux verdaux : verdauxEntries) {
        buffer.position(vdaPos);
        verdaux.write(eiClass, buffer);
        vdaPos += verdaux.vda_next;
      }
      vdPos += verdef.vd_next;
    }
  }

  public ElfVerDef compact() {
    return new ElfVerDef(
        RichStream.from(MoreIterables.enumerate(this.entries))
            .map(
                vdp -> {
                  int verdefIndex = vdp.getFirst();
                  Verdef verdef = vdp.getSecond().getFirst();
                  ImmutableList<Verdaux> verdauxes = vdp.getSecond().getSecond();
                  return new Pair<>(
                      new Verdef(
                          verdef.vd_version,
                          verdef.vd_flags,
                          verdef.vd_ndx,
                          verdauxes.size(),
                          verdef.vd_hash,
                          verdauxes.size() == 0 ? 0 : Verdef.BYTES,
                          verdefIndex == entries.size() - 1
                              ? 0
                              : Verdef.BYTES + Verdaux.BYTES * verdauxes.size()),
                      RichStream.from(MoreIterables.enumerate(verdauxes))
                          .map(
                              vdxp ->
                                  new Verdaux(
                                      vdxp.getSecond().vda_name,
                                      vdxp.getFirst() == verdauxes.size() - 1 ? 0 : Verdaux.BYTES))
                          .toImmutableList());
                })
            .toImmutableList());
  }

  public static class Verdef {

    private static final int BYTES = 20;

    // CHECKSTYLE.OFF: MemberName
    public final int vd_version;
    public final int vd_flags;
    public final int vd_ndx;
    public final int vd_cnt;
    public final long vd_hash;
    public final long vd_aux;
    public final long vd_next;
    // CHECKSTYLE.ON: MemberName

    // CHECKSTYLE.OFF: ParameterName
    public Verdef(
        int vd_version,
        int vd_flags,
        int vd_ndx,
        int vd_cnt,
        long vd_hash,
        long vd_aux,
        long vd_next) {
      this.vd_version = vd_version;
      this.vd_flags = vd_flags;
      this.vd_ndx = vd_ndx;
      this.vd_cnt = vd_cnt;
      this.vd_hash = vd_hash;
      this.vd_aux = vd_aux;
      this.vd_next = vd_next;
    }
    // CHECKSTYLE.ON: ParameterName

    private static Verdef parse(ElfHeader.EIClass eiClass, ByteBuffer buffer) {
      if (eiClass == ElfHeader.EIClass.ELFCLASS32) {
        return new Verdef(
            Elf.Elf32.getElf32Half(buffer),
            Elf.Elf32.getElf32Half(buffer),
            Elf.Elf32.getElf32Half(buffer),
            Elf.Elf32.getElf32Half(buffer),
            Elf.Elf32.getElf32Word(buffer),
            Elf.Elf32.getElf32Word(buffer),
            Elf.Elf32.getElf32Word(buffer));
      } else {
        return new Verdef(
            Elf.Elf64.getElf64Half(buffer),
            Elf.Elf64.getElf64Half(buffer),
            Elf.Elf64.getElf64Half(buffer),
            Elf.Elf64.getElf64Half(buffer),
            Elf.Elf64.getElf64Word(buffer),
            Elf.Elf64.getElf64Word(buffer),
            Elf.Elf64.getElf64Word(buffer));
      }
    }

    private void write(ElfHeader.EIClass eiClass, ByteBuffer buffer) {
      if (eiClass == ElfHeader.EIClass.ELFCLASS32) {
        Elf.Elf32.putElf32Half(buffer, (short) vd_version);
        Elf.Elf32.putElf32Half(buffer, (short) vd_flags);
        Elf.Elf32.putElf32Half(buffer, (short) vd_ndx);
        Elf.Elf32.putElf32Half(buffer, (short) vd_cnt);
        Elf.Elf32.putElf32Word(buffer, (int) vd_hash);
        Elf.Elf32.putElf32Word(buffer, (int) vd_aux);
        Elf.Elf32.putElf32Word(buffer, (int) vd_next);
      } else {
        Elf.Elf64.putElf64Half(buffer, (short) vd_version);
        Elf.Elf64.putElf64Half(buffer, (short) vd_flags);
        Elf.Elf64.putElf64Half(buffer, (short) vd_ndx);
        Elf.Elf64.putElf64Half(buffer, (short) vd_cnt);
        Elf.Elf64.putElf64Word(buffer, (int) vd_hash);
        Elf.Elf64.putElf64Word(buffer, (int) vd_aux);
        Elf.Elf64.putElf64Word(buffer, (int) vd_next);
      }
    }
  }

  public static class Verdaux {

    private static final int BYTES = 8;

    // CHECKSTYLE.OFF: MemberName
    public final long vda_name;
    public final long vda_next;
    // CHECKSTYLE.ON: MemberName

    // CHECKSTYLE.OFF: ParameterName
    public Verdaux(long vda_name, long vda_next) {
      this.vda_name = vda_name;
      this.vda_next = vda_next;
    }
    // CHECKSTYLE.ON: ParameterName

    private static Verdaux parse(ElfHeader.EIClass eiClass, ByteBuffer buffer) {
      if (eiClass == ElfHeader.EIClass.ELFCLASS32) {
        return new Verdaux(Elf.Elf32.getElf32Word(buffer), Elf.Elf32.getElf32Word(buffer));
      } else {
        return new Verdaux(Elf.Elf64.getElf64Word(buffer), Elf.Elf64.getElf64Word(buffer));
      }
    }

    private void write(ElfHeader.EIClass eiClass, ByteBuffer buffer) {
      if (eiClass == ElfHeader.EIClass.ELFCLASS32) {
        Elf.Elf32.putElf32Word(buffer, (int) vda_name);
        Elf.Elf32.putElf32Word(buffer, (int) vda_next);
      } else {
        Elf.Elf64.putElf64Word(buffer, (int) vda_name);
        Elf.Elf64.putElf64Word(buffer, (int) vda_next);
      }
    }
  }
}
