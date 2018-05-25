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

package com.facebook.buck.cli;

import com.facebook.buck.util.MoreStrings;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.OptionDef;
import org.kohsuke.args4j.spi.Parameters;
import org.kohsuke.args4j.spi.Setter;
import org.kohsuke.args4j.spi.SubCommand;
import org.kohsuke.args4j.spi.SubCommandHandler;
import org.kohsuke.args4j.spi.SubCommands;

public class AdditionalOptionsSubCommandHandler extends SubCommandHandler {

  private final Setter<Object> cachedSetter;

  public AdditionalOptionsSubCommandHandler(
      CmdLineParser parser, OptionDef option, Setter<Object> setter) {
    super(parser, option, setter);
    this.cachedSetter = setter;
  }

  @Override
  protected CmdLineParser configureParser(Object subCmd, SubCommand c) {
    return new AdditionalOptionsCmdLineParser(subCmd);
  }

  @Override
  public int parseArguments(Parameters params) throws CmdLineException {
    String subCmd = params.getParameter(0);
    SubCommands commands = cachedSetter.asAnnotatedElement().getAnnotation(SubCommands.class);
    HashMap<String, SubCommand> availableSubcommands = new HashMap<>();
    Arrays.stream(commands.value()).forEach(c -> availableSubcommands.put(c.name(), c));

    // Try exact matches
    SubCommand c = availableSubcommands.get(subCmd);
    if (c == null) {
      // Try alternative spellings
      List<String> suggestions = spellingSuggestions(subCmd, availableSubcommands.keySet(), 2);
      if (suggestions.size() == 1) {
        // Only use the suggestion if it's unambiguous
        String corrected = suggestions.get(0);
        System.err.println(
            String.format("Assuming '%s' is a creative spelling of '%s'", subCmd, corrected));
        c = availableSubcommands.get(corrected);
      }
    }
    if (c != null) {
      try {
        cachedSetter.addValue(subCommand(c, params));
      } catch (CmdLineException e) {
        Object subCmdObj = instantiate(c);
        if (subCmdObj instanceof AbstractCommand) {
          ((AbstractCommand) subCmdObj).handleException(e);
        } else {
          throw e;
        }
      }
      return params.size(); // consume all the remaining tokens
    } else {
      return fallback(subCmd);
    }
  }

  private static List<String> spellingSuggestions(
      String input, Collection<String> options, int maxDistance) {
    return options
        .stream()
        .map(option -> new Pair<>(option, MoreStrings.getLevenshteinDistance(input, option)))
        .filter(pair -> pair.getSecond() <= maxDistance)
        .sorted(Comparator.comparing(Pair::getSecond))
        .map(Pair::getFirst)
        .collect(ImmutableList.toImmutableList());
  }
}
