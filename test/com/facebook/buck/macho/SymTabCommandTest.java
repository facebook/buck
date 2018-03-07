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
import static org.junit.Assert.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

public class SymTabCommandTest {
  @Test
  public void testCreatingFromBytesBigEndian() {
    ByteBuffer buffer =
        ByteBuffer.wrap(SymTabCommandTestData.getBigEndian()).order(ByteOrder.BIG_ENDIAN);
    buffer.position(0);
    SymTabCommand command = SymTabCommandUtils.createFromBuffer(buffer);
    SymTabCommandTestData.checkValues(command);
  }

  @Test
  public void testCreatingFromBytesLittleEndian() {
    ByteBuffer buffer =
        ByteBuffer.wrap(SymTabCommandTestData.getLittleEndian()).order(ByteOrder.LITTLE_ENDIAN);
    SymTabCommand command = SymTabCommandUtils.createFromBuffer(buffer);
    SymTabCommandTestData.checkValues(command);
  }

  @Test
  public void testGettingBytesBigEndian() {
    ByteBuffer buffer =
        ByteBuffer.wrap(SymTabCommandTestData.getBigEndian()).order(ByteOrder.BIG_ENDIAN);
    SymTabCommand command = SymTabCommandUtils.createFromBuffer(buffer);
    ByteBuffer bigEndian =
        ByteBuffer.allocate(command.getLoadCommandCommonFields().getCmdsize().intValue())
            .order(ByteOrder.BIG_ENDIAN);
    SymTabCommandUtils.writeCommandToBuffer(command, bigEndian);
    ByteBuffer littleEndian =
        ByteBuffer.allocate(command.getLoadCommandCommonFields().getCmdsize().intValue())
            .order(ByteOrder.LITTLE_ENDIAN);
    SymTabCommandUtils.writeCommandToBuffer(command, littleEndian);

    assertThat(SymTabCommandTestData.getBigEndian(), equalTo(bigEndian.array()));
    assertThat(SymTabCommandTestData.getLittleEndian(), equalTo(littleEndian.array()));
  }

  @Test
  public void testGettingBytesLittleEndian() {
    ByteBuffer buffer =
        ByteBuffer.wrap(SymTabCommandTestData.getLittleEndian()).order(ByteOrder.LITTLE_ENDIAN);
    SymTabCommand command = SymTabCommandUtils.createFromBuffer(buffer);
    ByteBuffer bigEndian =
        ByteBuffer.allocate(command.getLoadCommandCommonFields().getCmdsize().intValue())
            .order(ByteOrder.BIG_ENDIAN);
    SymTabCommandUtils.writeCommandToBuffer(command, bigEndian);
    ByteBuffer littleEndian =
        ByteBuffer.allocate(command.getLoadCommandCommonFields().getCmdsize().intValue())
            .order(ByteOrder.LITTLE_ENDIAN);
    SymTabCommandUtils.writeCommandToBuffer(command, littleEndian);
    assertThat(SymTabCommandTestData.getLittleEndian(), equalTo(littleEndian.array()));
    assertThat(SymTabCommandTestData.getBigEndian(), equalTo(bigEndian.array()));
  }
}
