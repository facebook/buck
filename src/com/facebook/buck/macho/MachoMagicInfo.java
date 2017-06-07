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

import com.google.common.primitives.UnsignedInteger;

public class MachoMagicInfo {
  private final UnsignedInteger magic;

  public MachoMagicInfo(UnsignedInteger magic) {
    this.magic = magic;
  }

  public boolean isValidMachMagic() {
    return isMachObjectHeaderMagic() || isFatBinaryHeaderMagic();
  }

  public boolean isMachObjectHeaderMagic() {
    return magic.equals(MachoHeader.MH_MAGIC)
        || magic.equals(MachoHeader.MH_CIGAM)
        || magic.equals(MachoHeader.MH_MAGIC_64)
        || magic.equals(MachoHeader.MH_CIGAM_64);
  }

  public boolean isFatBinaryHeaderMagic() {
    return FatHeaderUtils.isFatHeaderMagic(magic);
  }

  public boolean is64Bit() {
    return magic.equals(MachoHeader.MH_MAGIC_64) || magic.equals(MachoHeader.MH_CIGAM_64);
  }

  public boolean isSwapped() {
    return magic.equals(MachoHeader.MH_CIGAM)
        || magic.equals(MachoHeader.MH_CIGAM_64)
        || FatHeaderUtils.isLittleEndian(magic);
  }
}
