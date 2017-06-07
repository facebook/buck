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
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SanitizedArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Test;

public class CxxLinkTest {

  private static final Linker DEFAULT_LINKER = new GnuLinker(new HashedFileTool(Paths.get("ld")));
  private static final Path DEFAULT_OUTPUT = Paths.get("test.exe");
  private static final ImmutableList<Arg> DEFAULT_ARGS =
      ImmutableList.of(
          StringArg.of("-rpath"),
          StringArg.of("/lib"),
          StringArg.of("libc.a"),
          SourcePathArg.of(new FakeSourcePath("a.o")),
          SourcePathArg.of(new FakeSourcePath("b.o")),
          SourcePathArg.of(new FakeSourcePath("libc.a")),
          StringArg.of("-L"),
          StringArg.of("/System/Libraries/libz.dynlib"),
          StringArg.of("-llibz.dylib"));

  @Test
  public void testThatInputChangesCauseRuleKeyChanges() {
    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    FakeFileHashCache hashCache =
        FakeFileHashCache.createFromStrings(
            ImmutableMap.of(
                "ld", Strings.repeat("0", 40),
                "a.o", Strings.repeat("a", 40),
                "b.o", Strings.repeat("b", 40),
                "libc.a", Strings.repeat("c", 40),
                "different", Strings.repeat("d", 40)));

    // Generate a rule key for the defaults.

    RuleKey defaultRuleKey =
        new DefaultRuleKeyFactory(0, hashCache, pathResolver, ruleFinder)
            .build(
                new CxxLink(
                    params,
                    DEFAULT_LINKER,
                    DEFAULT_OUTPUT,
                    DEFAULT_ARGS,
                    Optional.empty(),
                    Optional.empty(),
                    /* cacheable */ true,
                    /* thinLto */ false));

    // Verify that changing the archiver causes a rulekey change.

    RuleKey linkerChange =
        new DefaultRuleKeyFactory(0, hashCache, pathResolver, ruleFinder)
            .build(
                new CxxLink(
                    params,
                    new GnuLinker(new HashedFileTool(Paths.get("different"))),
                    DEFAULT_OUTPUT,
                    DEFAULT_ARGS,
                    Optional.empty(),
                    Optional.empty(),
                    /* cacheable */ true,
                    /* thinLto */ false));
    assertNotEquals(defaultRuleKey, linkerChange);

    // Verify that changing the output path causes a rulekey change.

    RuleKey outputChange =
        new DefaultRuleKeyFactory(0, hashCache, pathResolver, ruleFinder)
            .build(
                new CxxLink(
                    params,
                    DEFAULT_LINKER,
                    Paths.get("different"),
                    DEFAULT_ARGS,
                    Optional.empty(),
                    Optional.empty(),
                    /* cacheable */ true,
                    /* thinLto */ false));
    assertNotEquals(defaultRuleKey, outputChange);

    // Verify that changing the flags causes a rulekey change.

    RuleKey flagsChange =
        new DefaultRuleKeyFactory(0, hashCache, pathResolver, ruleFinder)
            .build(
                new CxxLink(
                    params,
                    DEFAULT_LINKER,
                    DEFAULT_OUTPUT,
                    ImmutableList.of(SourcePathArg.of(new FakeSourcePath("different"))),
                    Optional.empty(),
                    Optional.empty(),
                    /* cacheable */ true,
                    /* thinLto */ false));
    assertNotEquals(defaultRuleKey, flagsChange);
  }

  @Test
  public void sanitizedPathsInFlagsDoNotAffectRuleKey() {
    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    DefaultRuleKeyFactory ruleKeyFactory =
        new DefaultRuleKeyFactory(
            0,
            FakeFileHashCache.createFromStrings(
                ImmutableMap.of(
                    "ld", Strings.repeat("0", 40),
                    "a.o", Strings.repeat("a", 40),
                    "b.o", Strings.repeat("b", 40),
                    "libc.a", Strings.repeat("c", 40),
                    "different", Strings.repeat("d", 40))),
            pathResolver,
            ruleFinder);

    // Set up a map to sanitize the differences in the flags.
    int pathSize = 10;
    DebugPathSanitizer sanitizer1 =
        new MungingDebugPathSanitizer(
            pathSize,
            File.separatorChar,
            Paths.get("PWD"),
            ImmutableBiMap.of(Paths.get("something"), Paths.get("A")));
    DebugPathSanitizer sanitizer2 =
        new MungingDebugPathSanitizer(
            pathSize,
            File.separatorChar,
            Paths.get("PWD"),
            ImmutableBiMap.of(Paths.get("different"), Paths.get("A")));

    // Generate a rule with a path we need to sanitize to a consistent value.
    ImmutableList<Arg> args1 =
        ImmutableList.of(
            new SanitizedArg(sanitizer1.sanitize(Optional.empty()), "-Lsomething/foo"));

    RuleKey ruleKey1 =
        ruleKeyFactory.build(
            new CxxLink(
                params,
                DEFAULT_LINKER,
                DEFAULT_OUTPUT,
                args1,
                Optional.empty(),
                Optional.empty(),
                /* cacheable */ true,
                /* thinLto */ false));

    // Generate another rule with a different path we need to sanitize to the
    // same consistent value as above.
    ImmutableList<Arg> args2 =
        ImmutableList.of(
            new SanitizedArg(sanitizer2.sanitize(Optional.empty()), "-Ldifferent/foo"));

    RuleKey ruleKey2 =
        ruleKeyFactory.build(
            new CxxLink(
                params,
                DEFAULT_LINKER,
                DEFAULT_OUTPUT,
                args2,
                Optional.empty(),
                Optional.empty(),
                /* cacheable */ true,
                /* thinLto */ false));

    assertEquals(ruleKey1, ruleKey2);
  }
}
