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

/**
 * Encapsulate the data in an ELF section header.
 */
public class ElfSectionHeader {

  public final long sh_name;
  public final long sh_type;
  public final long sh_flags;
  public final long sh_addr;
  public final long sh_off;
  public final long sh_size;
  public final long sh_link;
  public final long sh_info;
  public final long sh_addralign;
  public final long sh_entsize;

  ElfSectionHeader(
      long sh_name,
      long sh_type,
      long sh_flags,
      long sh_addr,
      long sh_off,
      long sh_size,
      long sh_link,
      long sh_info,
      long sh_addralign,
      long sh_entsize) {
    this.sh_name = sh_name;
    this.sh_type = sh_type;
    this.sh_flags = sh_flags;
    this.sh_addr = sh_addr;
    this.sh_off = sh_off;
    this.sh_size = sh_size;
    this.sh_link = sh_link;
    this.sh_info = sh_info;
    this.sh_addralign = sh_addralign;
    this.sh_entsize = sh_entsize;
  }

  /**
   * @return either a 32- or 64-bit ELF section header parsed from the given buffer.
   */
  static ElfSectionHeader parse(ElfHeader.EIClass eiClass, ByteBuffer buffer) {
    if (eiClass == ElfHeader.EIClass.ELFCLASS32) {
      return new ElfSectionHeader(
          buffer.getInt(),
          buffer.getInt(),
          buffer.getInt(),
          buffer.getInt(),
          buffer.getInt(),
          buffer.getInt(),
          buffer.getInt(),
          buffer.getInt(),
          buffer.getInt(),
          buffer.getInt());
    } else {
      return new ElfSectionHeader(
          buffer.getInt(),
          buffer.getInt(),
          buffer.getLong(),
          buffer.getLong(),
          buffer.getLong(),
          buffer.getLong(),
          buffer.getInt(),
          buffer.getInt(),
          buffer.getLong(),
          buffer.getLong());
    }
  }

}
