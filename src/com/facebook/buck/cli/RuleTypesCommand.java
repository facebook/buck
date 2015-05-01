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

package com.facebook.buck.cli;

import com.facebook.buck.rules.BuckPyFunction;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.Description;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Comparator;

public class RuleTypesCommand extends AbstractCommandRunner<RuleTypesCommandOptions> {
  private class DescriptionComparator implements Comparator<Description<?>> {
    @Override
    public int compare(Description<?> d1, Description<?> d2) {
      String name1 = d1.getBuildRuleType().getName();
      String name2 = d2.getBuildRuleType().getName();
      return name1.compareTo(name2);
    }
  }

  public RuleTypesCommand(CommandRunnerParams params) {
    super(params);
  }

  @Override
    RuleTypesCommandOptions createOptions(BuckConfig buckConfig) {
    return new RuleTypesCommandOptions(buckConfig);
  }

  @Override
    int runCommandWithOptionsInternal(RuleTypesCommandOptions options)
    throws IOException, InterruptedException {
    ImmutableSet<Description<?>> descriptions = getRepository().getKnownBuildRuleTypes().getAllDescriptions();
    ConstructorArgMarshaller inspector = new ConstructorArgMarshaller();
    BuckPyFunction function = new BuckPyFunction(inspector);
    PrintStream stdout = console.getStdOut();
    for (Description<?> description : ImmutableSortedSet.copyOf(new DescriptionComparator(),
                                                                descriptions)) {
      stdout.println(function.toPythonPrototype(
                                                description.getBuildRuleType(),
                                                description.createUnpopulatedConstructorArg()));
    }
    return 0;
  }

  @Override
    String getUsageIntro() {
    return "prints the list of internal rule types";
  }
}
