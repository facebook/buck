/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.cli;

import com.facebook.buck.util.types.Pair;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.OptionDef;
import org.kohsuke.args4j.spi.OptionHandler;
import org.kohsuke.args4j.spi.Parameters;
import org.kohsuke.args4j.spi.Setter;

/** OptionHandler which takes exactly two strings next to each other, and returns them as a Pair. */
public class PairedStringOptionHandler extends OptionHandler<Pair<String, String>> {

  public PairedStringOptionHandler(
      CmdLineParser parser, OptionDef option, Setter<? super Pair<String, String>> setter) {
    super(parser, option, setter);
  }

  @Override
  public int parseArguments(Parameters params) throws CmdLineException {
    String parameter1 = getParamAndValidate(params, 0);
    String parameter2 = getParamAndValidate(params, 1);
    setter.addValue(new Pair<>(parameter1, parameter2));
    return 2;
  }

  private String getParamAndValidate(Parameters params, int idx) throws CmdLineException {
    String param = params.getParameter(idx);

    if (param.isEmpty() || param.startsWith("-")) {
      throw new CmdLineException(
          String.format(
              "Option \"%s\" takes exactly 2 values (found \"%s\" at position %s)",
              option, param, idx));
    }

    return param;
  }

  @Override
  public String getDefaultMetaVariable() {
    return "STRING STRING";
  }
}
