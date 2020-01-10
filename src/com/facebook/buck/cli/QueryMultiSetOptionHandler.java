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

import com.facebook.buck.query.QueryNormalizer;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.OptionDef;
import org.kohsuke.args4j.spi.Messages;
import org.kohsuke.args4j.spi.OptionHandler;
import org.kohsuke.args4j.spi.Parameters;
import org.kohsuke.args4j.spi.Setter;

/**
 * A simple option handler whose main job is to not freak out when it sees `--`, which is used by
 * QueryCommand as a separator between different sets. Most option handlers stop parsing as soon as
 * they see a param starting with `-`.
 */
public class QueryMultiSetOptionHandler extends OptionHandler<String> {

  public QueryMultiSetOptionHandler(CmdLineParser parser, OptionDef option, Setter<String> setter) {
    super(parser, option, setter);
  }

  /**
   * Returns {@code "STRING[]"}.
   *
   * @return return "STRING[]";
   */
  @Override
  public String getDefaultMetaVariable() {
    return Messages.DEFAULT_META_STRING_ARRAY_OPTION_HANDLER.format();
  }

  /** Tries to parse {@code String[]} argument from {@link Parameters}. */
  @Override
  public int parseArguments(Parameters params) throws CmdLineException {
    int counter = 0;
    for (; counter < params.size(); counter++) {
      String param = params.getParameter(counter);

      // Special case the -- separator
      if (param.startsWith("-") && !param.equals(QueryNormalizer.SET_SEPARATOR)) {
        break;
      }

      setter.addValue(param);
    }

    return counter;
  }
}
