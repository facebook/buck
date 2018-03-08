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
import static org.hamcrest.junit.MatcherAssert.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

public class MachoMagicInfoUtilsTest {
  @Test
  public void testReadingMachMagic() {
    ByteBuffer buffer =
        ByteBuffer.allocate(MachoHeaderUtils.MAGIC_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(MachoHeader.MH_MAGIC.intValue());

    buffer.position(0);
    MachoMagicInfo info32 = MachoMagicInfoUtils.getMachMagicInfo(buffer);
    assertThat(info32.isValidMachMagic(), equalTo(true));
    assertThat(info32.is64Bit(), equalTo(false));
    assertThat(info32.isSwapped(), equalTo(false));

    buffer.position(0);
    buffer.putInt(MachoHeader.MH_CIGAM.intValue());
    buffer.position(0);
    MachoMagicInfo swapped32 = MachoMagicInfoUtils.getMachMagicInfo(buffer);
    assertThat(swapped32.isValidMachMagic(), equalTo(true));
    assertThat(swapped32.is64Bit(), equalTo(false));
    assertThat(swapped32.isSwapped(), equalTo(true));

    buffer.position(0);
    buffer.putInt(MachoHeader.MH_MAGIC_64.intValue());
    buffer.position(0);
    MachoMagicInfo info64 = MachoMagicInfoUtils.getMachMagicInfo(buffer);
    assertThat(info64.isValidMachMagic(), equalTo(true));
    assertThat(info64.is64Bit(), equalTo(true));
    assertThat(info64.isSwapped(), equalTo(false));

    buffer.position(0);
    buffer.putInt(MachoHeader.MH_CIGAM_64.intValue());
    buffer.position(0);
    MachoMagicInfo swapped64 = MachoMagicInfoUtils.getMachMagicInfo(buffer);
    assertThat(swapped64.isValidMachMagic(), equalTo(true));
    assertThat(swapped64.is64Bit(), equalTo(true));
    assertThat(swapped64.isSwapped(), equalTo(true));

    buffer.position(0);
    buffer.putInt(1234);
    buffer.position(0);
    MachoMagicInfo invalid = MachoMagicInfoUtils.getMachMagicInfo(buffer);
    assertThat(invalid.isValidMachMagic(), equalTo(false));
  }

  @Test
  public void testReadingMachHeaderMagic() {
    byte[] bytes = {(byte) 0xFE, (byte) 0xED, (byte) 0xFA, (byte) 0xCF};
    ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
    MachoMagicInfo info = MachoMagicInfoUtils.getMachMagicInfo(buffer);
    assertThat(info.isValidMachMagic(), equalTo(true));
    assertThat(info.isFatBinaryHeaderMagic(), equalTo(false));
    assertThat(info.isMachObjectHeaderMagic(), equalTo(true));
    assertThat(info.is64Bit(), equalTo(true));
    assertThat(info.isSwapped(), equalTo(false));
  }
}
