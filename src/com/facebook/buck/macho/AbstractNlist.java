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

import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.google.common.primitives.UnsignedInteger;
import com.google.common.primitives.UnsignedLong;
import org.immutables.value.Value;

// CHECKSTYLE.OFF: MethodNameCheck
@Value.Immutable
@BuckStyleTuple
interface AbstractNlist {
  int SIZE_IN_BYTES_32_BIT = 12;
  int SIZE_IN_BYTES_64_BIT = 16;

  int getOffsetInBinary();

  UnsignedInteger getN_strx(); // 32 bit

  UnsignedInteger getN_type(); // 8 bit

  UnsignedInteger getN_sect(); // 8 bit

  UnsignedInteger getN_desc(); // 16 bit

  UnsignedLong getN_value(); // 32 bit for 32 bit arch;  64 bit for 64 bit arch
}
// CHECKSTYLE.ON: MethodNameCheck
