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

import com.facebook.buck.util.MoreSuppliers;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import java.util.Set;
import java.util.function.Supplier;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.OptionDef;
import org.kohsuke.args4j.spi.OptionHandler;
import org.kohsuke.args4j.spi.Parameters;
import org.kohsuke.args4j.spi.Setter;

/**
 * {@link OptionHandler} that collects multiple arguments passed to an option in a {@link Set}. In a
 * list of command-line arguments:
 *
 * <ul>
 *   <li>The option may be specified multiple times.
 *   <li>When the option is specified, it must have at least one value (multiple values must be
 *       delimited by spaces).
 *   <li>The same value may be specified multiple times for the option. However, even though a value
 *       may be specified more than once, only one instance of it will be present in the resulting
 *       {@link Set}.
 * </ul>
 */
public class StringSetOptionHandler extends OptionHandler<Supplier<ImmutableSet<String>>> {

  private final ImmutableSet.Builder<String> builder = ImmutableSet.builder();
  private final Supplier<ImmutableSet<String>> supplier = MoreSuppliers.memoize(builder::build);

  public StringSetOptionHandler(
      CmdLineParser parser, OptionDef option, Setter<? super Supplier<ImmutableSet<String>>> setter)
      throws CmdLineException {
    super(parser, option, setter);
    setter.addValue(supplier);
  }

  @Override
  public String getDefaultMetaVariable() {
    return "SET<STRING>";
  }

  @Override
  public int parseArguments(Parameters params) throws CmdLineException {
    int counter = 0;
    boolean hasValues = false;

    while (counter < params.size()) {
      String param = params.getParameter(counter);
      if (!param.isEmpty() && param.charAt(0) == '-') {
        break;
      }
      hasValues = true;
      builder.add(param);
      counter++;
    }

    Preconditions.checkArgument(hasValues, "Option \"%s\" takes one or more operands", option);
    return counter;
  }
}
