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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.equalToObject;
import static org.junit.Assert.assertThat;

import com.google.common.io.BaseEncoding;
import com.google.common.primitives.UnsignedInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

public class UnknownCommandUtilsTest {

  @Test
  public void testCreatingFromBuffer() {
    byte[] commandBytes = BaseEncoding.base16().decode("FFFF000000AA00000008");

    ByteBuffer buffer = ByteBuffer.wrap(commandBytes).order(ByteOrder.BIG_ENDIAN);
    buffer.position(2);
    UnknownCommand command = UnknownCommandUtils.createFromBuffer(buffer);
    assertThat(
        command.getLoadCommandCommonFields().getCmd(),
        equalToObject(UnsignedInteger.fromIntBits(0xAA)));
    assertThat(
        command.getLoadCommandCommonFields().getCmdsize(),
        equalToObject(UnsignedInteger.fromIntBits(0x08)));
    assertThat(command.getLoadCommandCommonFields().getOffsetInBinary(), equalTo(2));
  }
}
