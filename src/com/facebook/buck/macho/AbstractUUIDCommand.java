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
import java.util.UUID;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleTuple
abstract class AbstractUUIDCommand implements LoadCommand {
  public static final UnsignedInteger LC_UUID = UnsignedInteger.fromIntBits(0x1b);
  public static final int SIZE_IN_BYTES = LoadCommandCommonFields.CMD_AND_CMDSIZE_SIZE + 16;

  @Override
  public abstract LoadCommandCommonFields getLoadCommandCommonFields();

  public abstract UUID getUuid();

  @Value.Check
  protected void check() {
    Preconditions.checkArgument(getLoadCommandCommonFields().getCmd().equals(LC_UUID));
    Preconditions.checkArgument(
        getLoadCommandCommonFields()
            .getCmdsize()
            .equals(UnsignedInteger.fromIntBits(SIZE_IN_BYTES)));
  }
}
