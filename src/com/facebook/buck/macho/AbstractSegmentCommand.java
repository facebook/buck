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
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.UnsignedInteger;
import com.google.common.primitives.UnsignedLong;
import java.nio.charset.StandardCharsets;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleTuple
abstract class AbstractSegmentCommand implements LoadCommand {
  public static final UnsignedInteger LC_SEGMENT = UnsignedInteger.fromIntBits(0x1);
  public static final UnsignedInteger LC_SEGMENT_64 = UnsignedInteger.fromIntBits(0x19);
  public static final ImmutableSet<UnsignedInteger> VALID_CMD_VALUES =
      ImmutableSet.of(LC_SEGMENT, LC_SEGMENT_64);

  public static final int SEGNAME_SIZE_IN_BYTES = 16;
  public static final int SIZE_IN_BYTES_32_BIT =
      LoadCommandCommonFields.CMD_AND_CMDSIZE_SIZE + SEGNAME_SIZE_IN_BYTES + 32;
  public static final int SIZE_IN_BYTES_64_BIT =
      LoadCommandCommonFields.CMD_AND_CMDSIZE_SIZE + SEGNAME_SIZE_IN_BYTES + 48;

  @Override
  public abstract LoadCommandCommonFields getLoadCommandCommonFields();

  public abstract String getSegname(); // 128 bit / 16 bytes

  public abstract UnsignedLong getVmaddr(); // 32 bit on 32 bit arch / 64 bit on 64 bit arch

  public abstract UnsignedLong getVmsize(); // 32 bit on 32 bit arch / 64 bit on 64 bit arch

  public abstract UnsignedLong getFileoff(); // 32 bit on 32 bit arch / 64 bit on 64 bit arch

  public abstract UnsignedLong getFilesize(); // 32 bit on 32 bit arch / 64 bit on 64 bit arch

  public abstract Integer getMaxprot(); // 32 bit

  public abstract Integer getInitprot(); // 32 bit

  public abstract UnsignedInteger getNsects(); // 32 bit

  public abstract UnsignedInteger getFlags(); // 32 bit

  @Value.Check
  protected void check() {
    Preconditions.checkArgument(
        (getLoadCommandCommonFields().getCmd().equals(LC_SEGMENT)
                && getLoadCommandCommonFields().getCmdsize().intValue() >= SIZE_IN_BYTES_32_BIT)
            || (getLoadCommandCommonFields().getCmd().equals(LC_SEGMENT_64)
                && getLoadCommandCommonFields().getCmdsize().intValue() >= SIZE_IN_BYTES_64_BIT));
    Preconditions.checkArgument(
        getSegname().getBytes(StandardCharsets.UTF_8).length + 1 <= SEGNAME_SIZE_IN_BYTES);
  }
}
