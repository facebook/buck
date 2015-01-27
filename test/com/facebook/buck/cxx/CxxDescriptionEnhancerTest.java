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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.facebook.buck.cli.FakeBuckConfig;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.shell.Genrule;
import com.facebook.buck.shell.GenruleBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class CxxDescriptionEnhancerTest {

  @Test
  public void createLexYaccBuildRules() throws IOException {
    BuildRuleResolver resolver = new BuildRuleResolver();

    // Setup our C++ buck config with the paths to the lex/yacc binaries.
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    Path lexPath = Paths.get("lex");
    filesystem.touch(lexPath);
    Path yaccPath = Paths.get("yacc");
    filesystem.touch(yaccPath);
    FakeBuckConfig buckConfig = new FakeBuckConfig(
        ImmutableMap.<String, Map<String, String>>of(
            "cxx", ImmutableMap.of(
                "lex", lexPath.toString(),
                "yacc", yaccPath.toString())),
        filesystem);
    CxxPlatform cxxBuckConfig = DefaultCxxPlatforms.build(buckConfig);

    // Setup the target name and build params.
    BuildTarget target = BuildTargetFactory.newInstance("//:test");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);

    // Setup a genrule that generates our lex source.
    String lexSourceName = "test.ll";
    BuildTarget genruleTarget = BuildTargetFactory.newInstance("//:genrule_lex");
    Genrule genrule = (Genrule) GenruleBuilder
        .newGenruleBuilder(genruleTarget)
        .setOut(lexSourceName)
        .build(resolver);
    SourcePath lexSource = new BuildTargetSourcePath(genrule.getBuildTarget());

    // Use a regular path for our yacc source.
    String yaccSourceName = "test.yy";
    SourcePath yaccSource = new TestSourcePath(yaccSourceName);

    // Build the rules.
    CxxHeaderSourceSpec actual = CxxDescriptionEnhancer.createLexYaccBuildRules(
        params,
        resolver,
        cxxBuckConfig,
        ImmutableList.<String>of(),
        ImmutableMap.of(lexSourceName, lexSource),
        ImmutableList.<String>of(),
        ImmutableMap.of(yaccSourceName, yaccSource));

    // Grab the generated lex rule and verify it has the genrule as a dep.
    Lex lex = (Lex) resolver.getRule(
        CxxDescriptionEnhancer.createLexBuildTarget(target, lexSourceName));
    assertNotNull(lex);
    assertEquals(
        ImmutableSortedSet.<BuildRule>of(genrule),
        lex.getDeps());

    // Grab the generated yacc rule and verify it has no deps.
    Yacc yacc = (Yacc) resolver.getRule(
        CxxDescriptionEnhancer.createYaccBuildTarget(target, yaccSourceName));
    assertNotNull(yacc);
    assertEquals(
        ImmutableSortedSet.<BuildRule>of(),
        yacc.getDeps());

    // Check the header/source spec is correct.
    Path lexOutputSource = CxxDescriptionEnhancer.getLexSourceOutputPath(target, lexSourceName);
    Path lexOutputHeader = CxxDescriptionEnhancer.getLexHeaderOutputPath(target, lexSourceName);
    Path yaccOutputPrefix = CxxDescriptionEnhancer.getYaccOutputPrefix(target, yaccSourceName);
    Path yaccOutputSource = Yacc.getSourceOutputPath(yaccOutputPrefix);
    Path yaccOutputHeader = Yacc.getHeaderOutputPath(yaccOutputPrefix);
    CxxHeaderSourceSpec expected = ImmutableCxxHeaderSourceSpec.of(
        ImmutableMap.<Path, SourcePath>of(
            target.getBasePath().resolve(lexSourceName + ".h"),
            new BuildTargetSourcePath(lex.getBuildTarget(), lexOutputHeader),
            target.getBasePath().resolve(yaccSourceName + ".h"),
            new BuildTargetSourcePath(yacc.getBuildTarget(), yaccOutputHeader)),
        ImmutableMap.<String, CxxSource>of(
            lexSourceName + ".cc",
            ImmutableCxxSource.of(
                CxxSource.Type.CXX,
                new BuildTargetSourcePath(lex.getBuildTarget(), lexOutputSource)),
            yaccSourceName + ".cc",
            ImmutableCxxSource.of(
                CxxSource.Type.CXX,
                new BuildTargetSourcePath(yacc.getBuildTarget(), yaccOutputSource))));
    assertEquals(expected, actual);
  }

}
