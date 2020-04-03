/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android;

import com.facebook.buck.core.util.immutables.BuckStyleValue;

/** Information gathered from a device during buck install. */
@BuckStyleValue
public interface AndroidDeviceInfo {
  int TVDPI_DPI = 213;

  /** The display density category of the device. */
  enum DensityClass {
    LDPI(120),
    MDPI(160),
    HDPI(240),
    XHDPI(320),
    XXHDPI(480),
    XXXHDPI(640),
    OTHER_DPI(-1),
    TVDPI(-1);

    private final int maxDotsPerInch;

    DensityClass(int maxDotsPerInch) {
      this.maxDotsPerInch = maxDotsPerInch;
    }

    static DensityClass forPhysicalDensity(String dpiString) {
      try {
        int dpi = Integer.parseInt(dpiString);
        if (dpi == TVDPI_DPI) {
          return TVDPI;
        }
        for (DensityClass d : values()) {
          if (dpi <= d.maxDotsPerInch) {
            return d;
          }
        }
        return OTHER_DPI;
      } catch (NumberFormatException e) {
        return OTHER_DPI;
      }
    }
  }

  String getLocale();

  String getAbi();

  DensityClass getDensity();

  String getDpi();

  String getSdk();

  static AndroidDeviceInfo of(String locale, String abi, String dotsPerInch, String sdk) {
    return ImmutableAndroidDeviceInfo.ofImpl(
        locale, abi, DensityClass.forPhysicalDensity(dotsPerInch), dotsPerInch, sdk);
  }
}
