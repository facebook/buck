/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.parcelable;

import java.io.IOException;

final class PrintfStringBuilder implements Appendable {

  private final StringBuilder builder;

  public PrintfStringBuilder() {
    builder = new StringBuilder();
  }

  public PrintfStringBuilder appendLine(String format, Object... args) {
    builder.append(String.format(format, args)).append('\n');
    return this;
  }

  public PrintfStringBuilder appendLines(String... lines) {
    for (String line : lines) {
      builder.append(line).append('\n');
    }
    return this;
  }

  @Override
  public String toString() {
    return builder.toString();
  }

  @Override
  public PrintfStringBuilder append(CharSequence csq) throws IOException {
    builder.append(csq);
    return this;
  }

  @Override
  public PrintfStringBuilder append(CharSequence csq, int start, int end)
      throws IOException {
    builder.append(csq, start, end);
    return this;
  }

  @Override
  public PrintfStringBuilder append(char c) throws IOException {
    builder.append(c);
    return this;
  }

}
