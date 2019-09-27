/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.util.zip;

import java.util.Calendar;

public class ZipConstants {

  // The fake time we use: 12:00:00 AM February 1, 1985
  public static final int DOS_FAKE_TIME = 172032000;

  private ZipConstants() {}

  /**
   * {@link java.util.zip.ZipEntry#setTime(long)} is timezone-sensitive. Use this value instead of a
   * hardcoded constant to produce timzeone-agnostic .zip files.
   *
   * @return time in milliseconds that represents a fixed date in the current timezone.
   */
  public static long getFakeTime() {
    // Using any timestamp before 1980 (which is supposedly the earliest time supported by the .zip
    // format) causes files created by JRE 1.7 to be different than JRE 1.8. This is because 1.8
    // actually supports setting timestamps to earlier than 1980, whereas 1.7 rounds up to 1980.
    // We also need to resort to the deprecated date constructor as the ZipEntry uses deprecated
    // Date methods that depend on the current timezone.
    // Finally 1980.01.01 doesn't work across all timezones across Java 1.7 and 1.8. Fun times.
    Calendar c = Calendar.getInstance();
    c.set(1985, Calendar.FEBRUARY, 1, 0, 0, 0);
    return c.getTimeInMillis();
  }
}
