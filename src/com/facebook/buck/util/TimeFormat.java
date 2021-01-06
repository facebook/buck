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

package com.facebook.buck.util;

import java.util.Locale;

public class TimeFormat {

  private TimeFormat() {}

  /** @return a six-character string, so it is a fixed width */
  public static String formatForConsole(Locale locale, long durationInMillis, Ansi ansi) {
    if (durationInMillis < 100) {
      return ansi.asSuccessText("<100ms");
    } else if (durationInMillis < 1000) {
      return ansi.asWarningText(String.format(locale, " %dms", durationInMillis));
    } else {
      double seconds = durationInMillis / 1000.0;
      return ansi.asErrorText(String.format(locale, "%5.1fs", seconds));
    }
  }
}
