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
import org.immutables.value.Value;

/**
 * This header file describes the structures of the file format for "fat" architecture specific file
 * (wrapper design). At the begining of the file there is one fat_header structure followed by a
 * number of fat_arch structures. For each architecture in the file, specified by a pair of cputype
 * and cpusubtype, the fat_header describes the file offset, file size and alignment in the file of
 * the architecture specific member. The padded bytes in the file to place each member on it's
 * specific alignment are defined to be read as zeros and can be left as "holes" if the file system
 * can support them as long as they read as zeros.
 *
 * <p>All structures defined here are always written and read to/from disk in big-endian order.
 */
@Value.Immutable
@BuckStyleTuple
interface AbstractFatArch {
  /** @return file offset to this object file */
  Integer getCputype(); // 32 bit

  /** @return machine specifier (int) */
  Integer getCpusubtype(); // 32 bit

  /** @return file offset to this object file */
  UnsignedInteger getOffset(); // 32 bit

  /** @return size of this object file */
  UnsignedInteger getSize(); // 32 bit

  /** @return alignment as a power of 2 */
  UnsignedInteger getAlign(); // 32 bit
}
