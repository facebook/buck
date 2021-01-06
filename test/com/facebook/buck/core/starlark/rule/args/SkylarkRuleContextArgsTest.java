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

package com.facebook.buck.core.starlark.rule.args;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.artifact.ArtifactFilesystem;
import com.facebook.buck.core.rules.actions.lib.args.CommandLineArgs;
import com.facebook.buck.core.rules.actions.lib.args.ExecCompatibleCommandLineBuilder;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkList;
import org.junit.Test;

public class SkylarkRuleContextArgsTest {

  @Test
  public void addAddsArg() throws EvalException, LabelSyntaxException {
    CommandLineArgs args =
        new CommandLineArgsBuilder()
            .add(1, Runtime.UNBOUND, CommandLineArgs.DEFAULT_FORMAT_STRING, Location.BUILTIN)
            .add(
                "--foo",
                Label.parseAbsolute("//foo:bar", ImmutableMap.of()),
                CommandLineArgs.DEFAULT_FORMAT_STRING,
                Location.BUILTIN)
            .build();
    ImmutableList<String> stringified =
        new ExecCompatibleCommandLineBuilder(new ArtifactFilesystem(new FakeProjectFilesystem()))
            .build(args)
            .getCommandLineArgs();

    assertEquals(ImmutableList.of("1", "--foo", "//foo:bar"), stringified);
  }

  @Test
  public void addAllAddsArgs() throws EvalException, LabelSyntaxException {
    CommandLineArgs args =
        new CommandLineArgsBuilder()
            .addAll(
                SkylarkList.createImmutable(
                    ImmutableList.of(
                        1, "--foo", Label.parseAbsolute("//foo:bar", ImmutableMap.of()))),
                CommandLineArgs.DEFAULT_FORMAT_STRING,
                Location.BUILTIN)
            .build();

    ImmutableList<String> stringified =
        new ExecCompatibleCommandLineBuilder(new ArtifactFilesystem(new FakeProjectFilesystem()))
            .build(args)
            .getCommandLineArgs();

    assertEquals(ImmutableList.of("1", "--foo", "//foo:bar"), stringified);
  }
}
