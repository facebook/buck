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
import com.google.common.primitives.UnsignedInteger;
import com.google.common.primitives.UnsignedLong;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleTuple
abstract class AbstractSection {
  public static final int SIZE_IN_BYTES_32_BIT = 68;
  public static final int SIZE_IN_BYTES_64_BIT = 80;
  public static final int LENGTH_OF_STRING_FIELDS_IN_BYTES = 16;

  public abstract int getOffsetInBinary();

  public abstract String getSectname(); // 128 bit / 16 bytes

  public abstract String getSegname(); // 128 bit / 16 bytes

  public abstract UnsignedLong getAddr(); // 32 bit on 32 bit arch / 64 bit on 64 bit arch

  public abstract UnsignedLong getSize(); // 32 bit on 32 bit arch / 64 bit on 64 bit arch

  public abstract UnsignedInteger getOffset(); // 32 bit

  public abstract UnsignedInteger getAlign(); // 32 bit

  public abstract UnsignedInteger getReloff(); // 32 bit

  public abstract UnsignedInteger getNreloc(); // 32 bit

  public abstract UnsignedInteger getFlags(); // 32 bit

  public abstract UnsignedInteger getReserved1(); // 32 bit

  public abstract UnsignedInteger getReserved2(); // 32 bit

  public abstract Optional<UnsignedInteger> getReserved3(); // 32 bit, only present on 64 bit arch
}
