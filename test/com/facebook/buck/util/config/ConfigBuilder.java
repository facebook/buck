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

package com.facebook.buck.util.config;

import com.google.common.base.Joiner;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;

public class ConfigBuilder {
  // Utility class, do not instantiate.
  private ConfigBuilder() {}

  public static Config createFromText(String... lines) {
    return new Config(rawFromLines(lines));
  }

  public static RawConfig rawFromLines(String... lines) {
    try {
      return rawFromReader(new StringReader(Joiner.on('\n').join(lines)));
    } catch (IOException e) {
      throw new AssertionError("Ini read from StringReader should not throw", e);
    }
  }

  public static RawConfig rawFromReader(Reader reader) throws IOException {
    return RawConfig.of(Inis.read(reader));
  }
}
