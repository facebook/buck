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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import org.junit.Test;

public class UUIDCommandUtilsTest {

  @Test
  public void testUpdatingUuidCommandInByteBuffer() {
    UUIDCommand command =
        UUIDCommandUtils.createFromBuffer(
            ByteBuffer.wrap(UUIDCommandTestData.getBigEndian()).order(ByteOrder.BIG_ENDIAN));

    UUID newValue = UUID.randomUUID();
    UUIDCommand updated = command.withUuid(newValue);

    ByteBuffer buffer =
        ByteBuffer.allocate(command.getLoadCommandCommonFields().getCmdsize().intValue())
            .order(ByteOrder.BIG_ENDIAN);
    UUIDCommandUtils.updateUuidCommand(buffer, command, updated);

    buffer.position(0);
    UUIDCommand fromBuffer = UUIDCommandUtils.createFromBuffer(buffer);

    ByteBuffer newBuffer =
        ByteBuffer.allocate(fromBuffer.getLoadCommandCommonFields().getCmdsize().intValue())
            .order(ByteOrder.BIG_ENDIAN);
    UUIDCommandUtils.writeCommandToBuffer(fromBuffer, newBuffer);

    assertThat(fromBuffer.getUuid(), equalToObject(newValue));
    assertThat(buffer.array(), equalTo(newBuffer.array()));
  }

  @Test
  public void testCreatingFromBufferPositionsItCorrectly() {
    ByteBuffer buffer =
        ByteBuffer.wrap(UUIDCommandTestData.getBigEndian()).order(ByteOrder.BIG_ENDIAN);
    UUIDCommandUtils.createFromBuffer(buffer);
    assertThat(buffer.position(), equalTo(8 + 16));
  }

  @Test
  public void testCreatingFromBytesBigEndian() {
    UUIDCommand command =
        UUIDCommandUtils.createFromBuffer(
            ByteBuffer.wrap(UUIDCommandTestData.getBigEndian()).order(ByteOrder.BIG_ENDIAN));
    assertThat(
        command.getUuid(), equalToObject(UUID.fromString("01234567-89ab-cdef-ffee-ddccbbaa1234")));
  }

  @Test
  public void testCreatingFromBytesLittleEndian() {
    UUIDCommand command =
        UUIDCommandUtils.createFromBuffer(
            ByteBuffer.wrap(UUIDCommandTestData.getLittleEndian()).order(ByteOrder.LITTLE_ENDIAN));
    assertThat(
        command.getUuid(), equalToObject(UUID.fromString("01234567-89ab-cdef-ffee-ddccbbaa1234")));
  }

  @Test
  public void testGettingBytesBigEndian() {
    UUIDCommand command =
        UUIDCommandUtils.createFromBuffer(
            ByteBuffer.wrap(UUIDCommandTestData.getBigEndian()).order(ByteOrder.BIG_ENDIAN));

    ByteBuffer bigEndian =
        ByteBuffer.allocate(command.getLoadCommandCommonFields().getCmdsize().intValue())
            .order(ByteOrder.BIG_ENDIAN);
    UUIDCommandUtils.writeCommandToBuffer(command, bigEndian);
    ByteBuffer littleEndian =
        ByteBuffer.allocate(command.getLoadCommandCommonFields().getCmdsize().intValue())
            .order(ByteOrder.LITTLE_ENDIAN);
    UUIDCommandUtils.writeCommandToBuffer(command, littleEndian);

    assertThat(bigEndian.array(), equalTo(UUIDCommandTestData.getBigEndian()));
    assertThat(littleEndian.array(), equalTo(UUIDCommandTestData.getLittleEndian()));
  }

  @Test
  public void testGettingBytesLittleEndian() {
    UUIDCommand command =
        UUIDCommandUtils.createFromBuffer(
            ByteBuffer.wrap(UUIDCommandTestData.getLittleEndian()).order(ByteOrder.LITTLE_ENDIAN));

    ByteBuffer bigEndian =
        ByteBuffer.allocate(command.getLoadCommandCommonFields().getCmdsize().intValue())
            .order(ByteOrder.BIG_ENDIAN);
    UUIDCommandUtils.writeCommandToBuffer(command, bigEndian);
    ByteBuffer littleEndian =
        ByteBuffer.allocate(command.getLoadCommandCommonFields().getCmdsize().intValue())
            .order(ByteOrder.LITTLE_ENDIAN);
    UUIDCommandUtils.writeCommandToBuffer(command, littleEndian);

    assertThat(bigEndian.array(), equalTo(UUIDCommandTestData.getBigEndian()));
    assertThat(littleEndian.array(), equalTo(UUIDCommandTestData.getLittleEndian()));
  }
}
