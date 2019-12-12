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

package com.facebook.buck.cli;

import java.util.Map;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.OptionDef;
import org.kohsuke.args4j.spi.MapOptionHandler;
import org.kohsuke.args4j.spi.Setter;

/** OptionHandler used for specifying environment overrides. */
public class EnvironmentOverrideOptionHandler extends MapOptionHandler {
  public EnvironmentOverrideOptionHandler(
      CmdLineParser parser, OptionDef option, Setter<? super Map<?, ?>> setter) {
    super(parser, option, setter);
  }

  @Override
  protected void addToMap(@SuppressWarnings("rawtypes") Map m, String key, String value) {
    super.addToMap(m, key, value == null ? "" : value);
  }
}
