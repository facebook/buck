/*
 * Copyright 2018-present Facebook, Inc.
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

import com.facebook.buck.util.MoreSuppliers;
import com.google.common.collect.ImmutableSet;
import java.util.function.Supplier;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.OptionDef;
import org.kohsuke.args4j.spi.OptionHandler;
import org.kohsuke.args4j.spi.Parameters;
import org.kohsuke.args4j.spi.Setter;

/**
 * An option handler that allows an option to be specified multiple times and coaleced into a set of
 * strings, but only allows one value for each specification of the option. This is useful to not
 * cause ambiguity when used in conjunction with other options that consume multiple values
 */
public class SingleStringSetOptionHandler extends OptionHandler<Supplier<ImmutableSet<String>>> {

  private final ImmutableSet.Builder<String> builder = ImmutableSet.builder();
  private final Supplier<ImmutableSet<String>> supplier = MoreSuppliers.memoize(builder::build);

  public SingleStringSetOptionHandler(
      CmdLineParser parser, OptionDef option, Setter<? super Supplier<ImmutableSet<String>>> setter)
      throws CmdLineException {
    super(parser, option, setter);
    setter.addValue(supplier);
  }

  @Override
  public int parseArguments(Parameters params) throws CmdLineException {
    String p = params.getParameter(0);
    if (p.isEmpty()) {
      throw new CmdLineException(String.format("Option \"%s\" takes one operand", option));
    }
    if (p.startsWith("-")) {
      throw new CmdLineException(String.format("Option \"%s\" takes one operand", option));
    }
    builder.add(p);
    return 1;
  }

  @Override
  public String getDefaultMetaVariable() {
    return "SET<STRING>";
  }
}
