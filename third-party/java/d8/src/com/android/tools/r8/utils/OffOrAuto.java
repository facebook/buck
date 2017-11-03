// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.utils;

import joptsimple.ValueConversionException;
import joptsimple.ValueConverter;

public enum OffOrAuto {
  Off, Auto;

  static final ValueConverter<OffOrAuto> CONVERTER = new ValueConverter<OffOrAuto>() {
    @Override
    public OffOrAuto convert(String input) {
      try {
        input = Character.toUpperCase(input.charAt(0)) + input.substring(1).toLowerCase();
        return Enum.valueOf(OffOrAuto.class, input);
      } catch (Exception e) {
        throw new ValueConversionException("Value must be one of: " + valuePattern());
      }
    }

    @Override
    public Class<OffOrAuto> valueType() {
      return OffOrAuto.class;
    }

    @Override
    public String valuePattern() {
      return "off|auto";
    }
  };
}
