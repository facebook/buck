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
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleTuple
abstract class AbstractLinkEditDataCommand implements LoadCommand {

  public static final UnsignedInteger LC_CODE_SIGNATURE = UnsignedInteger.fromIntBits(0x1d);
  public static final UnsignedInteger LC_SEGMENT_SPLIT_INFO = UnsignedInteger.fromIntBits(0x1e);
  public static final UnsignedInteger LC_FUNCTION_STARTS = UnsignedInteger.fromIntBits(0x26);
  public static final UnsignedInteger LC_DATA_IN_CODE = UnsignedInteger.fromIntBits(0x29);
  public static final UnsignedInteger LC_DYLIB_CODE_SIGN_DRS = UnsignedInteger.fromIntBits(0x2B);
  public static final UnsignedInteger LC_LINKER_OPTIMIZATION_HINT =
      UnsignedInteger.fromIntBits(0x2E);
  public static final ImmutableSet<UnsignedInteger> VALID_CMD_VALUES =
      ImmutableSet.of(
          LC_CODE_SIGNATURE,
          LC_SEGMENT_SPLIT_INFO,
          LC_FUNCTION_STARTS,
          LC_DATA_IN_CODE,
          LC_DYLIB_CODE_SIGN_DRS,
          LC_LINKER_OPTIMIZATION_HINT);

  public static final int SIZE_IN_BYTES = 16;

  @Override
  public abstract LoadCommandCommonFields getLoadCommandCommonFields();

  public abstract UnsignedInteger getDataoff(); // 32 bit

  public abstract UnsignedInteger getDatasize(); // 32 bit

  @Value.Check
  protected void check() {
    UnsignedInteger cmd = getLoadCommandCommonFields().getCmd();
    Preconditions.checkArgument(VALID_CMD_VALUES.contains(cmd));
    Preconditions.checkArgument(
        getLoadCommandCommonFields()
            .getCmdsize()
            .equals(UnsignedInteger.fromIntBits(SIZE_IN_BYTES)));
  }
}
