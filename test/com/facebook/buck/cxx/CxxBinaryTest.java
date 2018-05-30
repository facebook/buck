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

import com.facebook.buck.core.cell.TestCellPathResolver;
import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.toolchain.tool.impl.CommandTool;
import com.facebook.buck.cxx.toolchain.CxxPlatformUtils;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.TestBuildRuleParams;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Test;

public class CxxBinaryTest {

  @Test
  public void getExecutableCommandUsesAbsolutePath() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(graphBuilder);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);

    BuildTarget linkTarget = BuildTargetFactory.newInstance("//:link");
    Path bin = Paths.get("path/to/exectuable");
    FakeProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    CxxLink cxxLink =
        graphBuilder.addToIndex(
            new CxxLink(
                linkTarget,
                projectFilesystem,
                ruleFinder,
                TestCellPathResolver.get(projectFilesystem),
                CxxPlatformUtils.DEFAULT_PLATFORM.getLd().resolve(graphBuilder),
                bin,
                ImmutableMap.of(),
                ImmutableList.of(),
                Optional.empty(),
                Optional.empty(),
                /* cacheable */ true,
                /* thinLto */ false));
    BuildTarget target = BuildTargetFactory.newInstance("//:target");
    BuildRuleParams params = TestBuildRuleParams.create();
    CxxBinary binary =
        graphBuilder.addToIndex(
            new CxxBinary(
                target,
                projectFilesystem,
                params.copyAppendingExtraDeps(ImmutableSortedSet.<BuildRule>of(cxxLink)),
                CxxPlatformUtils.DEFAULT_PLATFORM,
                cxxLink,
                new CommandTool.Builder()
                    .addArg(SourcePathArg.of(cxxLink.getSourcePathToOutput()))
                    .build(),
                ImmutableSortedSet.of(),
                ImmutableList.of(),
                target,
                false));
    ImmutableList<String> command = binary.getExecutableCommand().getCommandPrefix(pathResolver);
    assertTrue(Paths.get(command.get(0)).isAbsolute());
  }
}
