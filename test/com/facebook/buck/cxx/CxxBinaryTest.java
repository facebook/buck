/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import static org.junit.Assert.assertTrue;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.SourcePathArg;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Test;

public class CxxBinaryTest {

  @Test
  public void getExecutableCommandUsesAbsolutePath() throws IOException {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);

    BuildRuleParams linkParams = new FakeBuildRuleParamsBuilder("//:link").build();
    Path bin = Paths.get("path/to/exectuable");
    CxxLink cxxLink =
        ruleResolver.addToIndex(
            new CxxLink(
                linkParams,
                CxxPlatformUtils.DEFAULT_PLATFORM.getLd().resolve(ruleResolver),
                bin,
                ImmutableList.of(),
                Optional.empty(),
                Optional.empty(),
                /* cacheable */ true,
                /* thinLto */ false));
    BuildRuleParams params = new FakeBuildRuleParamsBuilder("//:target").build();
    CxxBinary binary =
        ruleResolver.addToIndex(
            new CxxBinary(
                params.copyAppendingExtraDeps(ImmutableSortedSet.<BuildRule>of(cxxLink)),
                ruleResolver,
                ruleFinder,
                CxxPlatformUtils.DEFAULT_PLATFORM,
                cxxLink,
                new CommandTool.Builder()
                    .addArg(SourcePathArg.of(cxxLink.getSourcePathToOutput()))
                    .build(),
                ImmutableSortedSet.of(),
                ImmutableList.of(),
                params.getBuildTarget()));
    ImmutableList<String> command = binary.getExecutableCommand().getCommandPrefix(pathResolver);
    assertTrue(Paths.get(command.get(0)).isAbsolute());
  }
}
