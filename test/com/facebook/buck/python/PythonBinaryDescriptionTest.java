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

package com.facebook.buck.python;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleSourcePath;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class PythonBinaryDescriptionTest {

  private static final Path PEX_PATH = Paths.get("pex");

  @Test
  public void thatComponentSourcePathDepsPropagateProperly() {
    BuildRuleResolver resolver = new BuildRuleResolver();

    Genrule genrule = GenruleBuilder.createGenrule(BuildTargetFactory.newInstance("//:gen"))
        .setOut("blah.py")
        .build();
    BuildRuleParams libParams = BuildRuleParamsFactory.createTrivialBuildRuleParams(
        BuildTargetFactory.newInstance("//:lib"));
    PythonLibrary lib = new PythonLibrary(
        libParams,
        ImmutableMap.<Path, SourcePath>of(
            Paths.get("hello"), new BuildRuleSourcePath(genrule)),
        ImmutableMap.<Path, SourcePath>of());

    BuildRuleParams params =
        new FakeBuildRuleParamsBuilder(BuildTargetFactory.newInstance("//:bin"))
            .setDeps(ImmutableSortedSet.<BuildRule>of(lib))
            .build();
    PythonBinaryDescription desc = new PythonBinaryDescription(PEX_PATH);
    PythonBinaryDescription.Arg arg = desc.createUnpopulatedConstructorArg();
    arg.deps = Optional.of(ImmutableSortedSet.<BuildRule>of());
    arg.main = new TestSourcePath("blah.py");
    BuildRule rule = desc.createBuildRule(params, resolver, arg);

    assertEquals(
        ImmutableSortedSet.<BuildRule>of(genrule),
        rule.getDeps());
  }

  @Test
  public void thatMainSourcePathPropagatesToDeps() {
    BuildRuleResolver resolver = new BuildRuleResolver();

    Genrule genrule = GenruleBuilder.createGenrule(BuildTargetFactory.newInstance("//:gen"))
        .setOut("blah.py")
        .build();
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(
        BuildTargetFactory.newInstance("//:bin"));
    PythonBinaryDescription desc = new PythonBinaryDescription(PEX_PATH);
    PythonBinaryDescription.Arg arg = desc.createUnpopulatedConstructorArg();
    arg.deps = Optional.of(ImmutableSortedSet.<BuildRule>of());
    arg.main = new BuildRuleSourcePath(genrule);
    BuildRule rule = desc.createBuildRule(params, resolver, arg);
    assertEquals(
        ImmutableSortedSet.<BuildRule>of(genrule),
        rule.getDeps());
  }

}
