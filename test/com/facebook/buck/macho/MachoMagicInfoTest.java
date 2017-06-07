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

import com.google.common.primitives.UnsignedInteger;
import org.junit.Test;

public class MachoMagicInfoTest {
  @Test
  public void testInfoFor64BitAndBigEndian() {
    MachoMagicInfo info = new MachoMagicInfo(UnsignedInteger.fromIntBits(0xFEEDFACF));
    assertThat(info.isValidMachMagic(), equalTo(true));
    assertThat(info.is64Bit(), equalTo(true));
    assertThat(info.isSwapped(), equalTo(false));
    assertThat(info.isMachObjectHeaderMagic(), equalTo(true));
    assertThat(info.isFatBinaryHeaderMagic(), equalTo(false));
  }

  @Test
  public void testInfoFor64BitAndLittleEndian() {
    MachoMagicInfo info = new MachoMagicInfo(UnsignedInteger.fromIntBits(0xCFFAEDFE));
    assertThat(info.isValidMachMagic(), equalTo(true));
    assertThat(info.is64Bit(), equalTo(true));
    assertThat(info.isSwapped(), equalTo(true));
    assertThat(info.isMachObjectHeaderMagic(), equalTo(true));
    assertThat(info.isFatBinaryHeaderMagic(), equalTo(false));
  }

  @Test
  public void testInfoFor32BitAndBigEndian() {
    MachoMagicInfo info = new MachoMagicInfo(UnsignedInteger.fromIntBits(0xFEEDFACE));
    assertThat(info.isValidMachMagic(), equalTo(true));
    assertThat(info.is64Bit(), equalTo(false));
    assertThat(info.isSwapped(), equalTo(false));
    assertThat(info.isMachObjectHeaderMagic(), equalTo(true));
    assertThat(info.isFatBinaryHeaderMagic(), equalTo(false));
  }

  @Test
  public void testInfoFor32BitAndLittleEndian() {
    MachoMagicInfo info = new MachoMagicInfo(UnsignedInteger.fromIntBits(0xCEFAEDFE));
    assertThat(info.isValidMachMagic(), equalTo(true));
    assertThat(info.is64Bit(), equalTo(false));
    assertThat(info.isSwapped(), equalTo(true));
    assertThat(info.isMachObjectHeaderMagic(), equalTo(true));
    assertThat(info.isFatBinaryHeaderMagic(), equalTo(false));
  }

  @Test
  public void testInfoForFatBinaryAndBigEndian() {
    MachoMagicInfo info = new MachoMagicInfo(UnsignedInteger.fromIntBits(0xCAFEBABE));
    assertThat(info.isValidMachMagic(), equalTo(true));
    assertThat(info.is64Bit(), equalTo(false));
    assertThat(info.isSwapped(), equalTo(false));
    assertThat(info.isMachObjectHeaderMagic(), equalTo(false));
    assertThat(info.isFatBinaryHeaderMagic(), equalTo(true));
  }

  @Test
  public void testInfoForFatBinaryAndLittleEndian() {
    MachoMagicInfo info = new MachoMagicInfo(UnsignedInteger.fromIntBits(0xBEBAFECA));
    assertThat(info.isValidMachMagic(), equalTo(true));
    assertThat(info.is64Bit(), equalTo(false));
    assertThat(info.isSwapped(), equalTo(true));
    assertThat(info.isMachObjectHeaderMagic(), equalTo(false));
    assertThat(info.isFatBinaryHeaderMagic(), equalTo(true));
  }

  @Test
  public void testInvalidMagic() {
    MachoMagicInfo info = new MachoMagicInfo(UnsignedInteger.fromIntBits(0x12345678));
    assertThat(info.isValidMachMagic(), equalTo(false));
  }
}
