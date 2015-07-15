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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class CxxBinaryTest {

  @Test
  public void getExecutableCommandUsesAbsolutePath() throws IOException {
    BuildRuleResolver ruleResolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);

    BuildRuleParams linkParams = new FakeBuildRuleParamsBuilder("//:link").build();
    Path bin = Paths.get("path/to/exectuable");
    CxxLink cxxLink =
        ruleResolver.addToIndex(
            new CxxLink(
                linkParams,
                pathResolver,
                CxxPlatformUtils.DEFAULT_PLATFORM.getLd(),
                bin,
                ImmutableList.<SourcePath>of(),
                ImmutableList.<String>of(),
                ImmutableSet.<Path>of(),
                CxxPlatformUtils.DEFAULT_PLATFORM.getDebugPathSanitizer()));

    BuildRuleParams params = new FakeBuildRuleParamsBuilder("//:target").build();
    CxxBinary binary =
        ruleResolver.addToIndex(
            new CxxBinary(
                params,
                ruleResolver,
                pathResolver,
                bin,
                cxxLink,
                ImmutableList.<Path>of(),
                ImmutableList.<BuildTarget>of()));
    ImmutableList<String> command = binary.getExecutableCommand().getCommandPrefix(pathResolver);
    assertTrue(Paths.get(command.get(0)).isAbsolute());
  }

}
