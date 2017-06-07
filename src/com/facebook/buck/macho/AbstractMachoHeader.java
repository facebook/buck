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
package com.facebook.buck.macho;

import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.base.Preconditions;
import com.google.common.primitives.UnsignedInteger;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleTuple
abstract class AbstractMachoHeader {
  public static final int MACH_HEADER_SIZE_32 = 28;
  public static final int MACH_HEADER_SIZE_64 = 32;
  public static final UnsignedInteger MH_MAGIC = UnsignedInteger.fromIntBits(0xfeedface);
  public static final UnsignedInteger MH_CIGAM = UnsignedInteger.fromIntBits(0xcefaedfe);
  public static final UnsignedInteger MH_MAGIC_64 = UnsignedInteger.fromIntBits(0xfeedfacf);
  public static final UnsignedInteger MH_CIGAM_64 = UnsignedInteger.fromIntBits(0xcffaedfe);

  public abstract UnsignedInteger getMagic(); // 32 bit

  public abstract Integer getCputype(); // 32 bit

  public abstract Integer getCpusubtype(); // 32 bit

  public abstract UnsignedInteger getFiletype(); // 32 bit

  public abstract UnsignedInteger getNcmds(); // 32 bit

  public abstract UnsignedInteger getSizeofcmds(); // 32 bit

  public abstract UnsignedInteger getFlags(); // 32 bit

  public abstract Optional<UnsignedInteger> getReserved(); // 32 bit, only present for 64 bit arch

  @Value.Check
  protected void check() {
    Preconditions.checkArgument(getMagic().equals(MH_MAGIC) || getMagic().equals(MH_MAGIC_64));
    if (getMagic().equals(MH_MAGIC)) {
      Preconditions.checkArgument(!getReserved().isPresent());
    } else {
      Preconditions.checkArgument(getReserved().isPresent());
    }
  }
}
