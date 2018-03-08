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

import com.google.common.primitives.UnsignedInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

public class LinkEditDataCommandTest {
  @Test
  public void testCreatingFromBytesBigEndian() {
    ByteBuffer buffer =
        ByteBuffer.wrap(LinkEditCommandTestData.getCodeSignBigEndian()).order(ByteOrder.BIG_ENDIAN);
    LinkEditDataCommand command = LinkEditDataCommandUtils.createFromBuffer(buffer);
    LinkEditCommandTestData.checkValues(command);
  }

  @Test
  public void testCreatingFromBytesLittleEndian() {
    ByteBuffer buffer =
        ByteBuffer.wrap(LinkEditCommandTestData.getCodeSignLittleEndian())
            .order(ByteOrder.LITTLE_ENDIAN);
    LinkEditDataCommand command = LinkEditDataCommandUtils.createFromBuffer(buffer);
    LinkEditCommandTestData.checkValues(command);
  }

  @Test
  public void testUpdatingLinkEditDataCommandInByteBuffer() {
    LinkEditDataCommand command =
        LinkEditDataCommandUtils.createFromBuffer(
            ByteBuffer.wrap(LinkEditCommandTestData.getCodeSignBigEndian())
                .order(ByteOrder.BIG_ENDIAN));

    UnsignedInteger newValue = UnsignedInteger.fromIntBits(0xFE);
    LinkEditDataCommand updated = command.withDataoff(newValue);

    ByteBuffer buffer =
        ByteBuffer.allocate(command.getLoadCommandCommonFields().getCmdsize().intValue())
            .order(ByteOrder.BIG_ENDIAN);
    LinkEditDataCommandUtils.updateLinkEditDataCommand(buffer, command, updated);

    buffer.position(0);
    LinkEditDataCommand commandCreatedFromBuffer =
        LinkEditDataCommandUtils.createFromBuffer(buffer);

    ByteBuffer newBuffer =
        ByteBuffer.allocate(
                commandCreatedFromBuffer.getLoadCommandCommonFields().getCmdsize().intValue())
            .order(ByteOrder.BIG_ENDIAN);
    LinkEditDataCommandUtils.writeCommandToBuffer(commandCreatedFromBuffer, newBuffer);

    assertThat(commandCreatedFromBuffer.getDataoff(), equalToObject(newValue));
    assertThat(buffer.array(), equalTo(newBuffer.array()));
  }
}
