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

import org.junit.Test;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class CxxLinkTest {

  private static final Linker DEFAULT_LINKER = new GnuLinker(new HashedFileTool(Paths.get("ld")));
  private static final Path DEFAULT_OUTPUT = Paths.get("test.exe");
  private static final SourcePathResolver DEFAULT_SOURCE_PATH_RESOLVER =
      new SourcePathResolver(
          new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
  private static final ImmutableList<Arg> DEFAULT_ARGS =
      ImmutableList.of(
          new StringArg("-rpath"),
          new StringArg("/lib"),
          new StringArg("libc.a"),
          new SourcePathArg(DEFAULT_SOURCE_PATH_RESOLVER, new FakeSourcePath("a.o")),
          new SourcePathArg(DEFAULT_SOURCE_PATH_RESOLVER, new FakeSourcePath("b.o")),
          new SourcePathArg(DEFAULT_SOURCE_PATH_RESOLVER, new FakeSourcePath("libc.a")),
          new StringArg("-L"),
          new StringArg("/System/Libraries/libz.dynlib"),
          new StringArg("-llibz.dylib"));

  @Test
  public void testThatInputChangesCauseRuleKeyChanges() {
    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    FakeFileHashCache hashCache = FakeFileHashCache.createFromStrings(
        ImmutableMap.of(
            "ld", Strings.repeat("0", 40),
            "a.o", Strings.repeat("a", 40),
            "b.o", Strings.repeat("b", 40),
            "libc.a", Strings.repeat("c", 40),
            "different", Strings.repeat("d", 40)));

    // Generate a rule key for the defaults.

    RuleKey defaultRuleKey = new DefaultRuleKeyFactory(0, hashCache, pathResolver).build(
        new CxxLink(
            params,
            pathResolver,
            DEFAULT_LINKER,
            DEFAULT_OUTPUT,
            DEFAULT_ARGS,
            Optional.empty(),
            /* cacheable */ true));

    // Verify that changing the archiver causes a rulekey change.

    RuleKey linkerChange = new DefaultRuleKeyFactory(0, hashCache, pathResolver).build(
        new CxxLink(
            params,
            pathResolver,
            new GnuLinker(new HashedFileTool(Paths.get("different"))),
            DEFAULT_OUTPUT,
            DEFAULT_ARGS,
            Optional.empty(),
            /* cacheable */ true));
    assertNotEquals(defaultRuleKey, linkerChange);

    // Verify that changing the output path causes a rulekey change.

    RuleKey outputChange = new DefaultRuleKeyFactory(0, hashCache, pathResolver).build(
        new CxxLink(
            params,
            pathResolver,
            DEFAULT_LINKER,
            Paths.get("different"),
            DEFAULT_ARGS,
            Optional.empty(),
            /* cacheable */ true));
    assertNotEquals(defaultRuleKey, outputChange);

    // Verify that changing the flags causes a rulekey change.

    RuleKey flagsChange = new DefaultRuleKeyFactory(0, hashCache, pathResolver).build(
        new CxxLink(
            params,
            pathResolver,
            DEFAULT_LINKER,
            DEFAULT_OUTPUT,
            ImmutableList.of(
                new SourcePathArg(
                    new SourcePathResolver(
                        new BuildRuleResolver(
                            TargetGraph.EMPTY,
                            new DefaultTargetNodeToBuildRuleTransformer())),
                    new FakeSourcePath("different"))),
            Optional.empty(),
            /* cacheable */ true));
    assertNotEquals(defaultRuleKey, flagsChange);
  }

  @Test
  public void sanitizedPathsInFlagsDoNotAffectRuleKey() {
    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
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
            pathResolver);

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
            new SanitizedArg(
                sanitizer1.sanitize(Optional.empty()),
                "-Lsomething/foo"));

    RuleKey ruleKey1 = ruleKeyFactory.build(
        new CxxLink(
            params,
            pathResolver,
            DEFAULT_LINKER,
            DEFAULT_OUTPUT,
            args1,
            Optional.empty(),
            /* cacheable */ true));

    // Generate another rule with a different path we need to sanitize to the
    // same consistent value as above.
    ImmutableList<Arg> args2 =
        ImmutableList.of(
            new SanitizedArg(
                sanitizer2.sanitize(Optional.empty()),
                "-Ldifferent/foo"));

    RuleKey ruleKey2 = ruleKeyFactory.build(
        new CxxLink(
            params,
            pathResolver,
            DEFAULT_LINKER,
            DEFAULT_OUTPUT,
            args2,
            Optional.empty(),
            /* cacheable */ true));

    assertEquals(ruleKey1, ruleKey2);
  }

}
