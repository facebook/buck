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

import com.facebook.buck.cxx.toolchain.DebugPathSanitizer;
import com.facebook.buck.cxx.toolchain.MungingDebugPathSanitizer;
import com.facebook.buck.cxx.toolchain.linker.GnuLinker;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TestBuildRuleResolver;
import com.facebook.buck.rules.TestCellPathResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.SanitizedArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.junit.Test;

public class CxxLinkTest {

  private static final Path DEFAULT_OUTPUT = Paths.get("test.exe");
  private static final ImmutableList<Arg> DEFAULT_ARGS =
      ImmutableList.of(
          StringArg.of("-rpath"),
          StringArg.of("/lib"),
          StringArg.of("libc.a"),
          SourcePathArg.of(FakeSourcePath.of("a.o")),
          SourcePathArg.of(FakeSourcePath.of("b.o")),
          SourcePathArg.of(FakeSourcePath.of("libc.a")),
          StringArg.of("-L"),
          StringArg.of("/System/Libraries/libz.dynlib"),
          StringArg.of("-llibz.dylib"));

  private final ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
  private final Linker DEFAULT_LINKER =
      new GnuLinker(new HashedFileTool(PathSourcePath.of(projectFilesystem, Paths.get("ld"))));

  @Test
  public void testThatInputChangesCauseRuleKeyChanges() {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestBuildRuleResolver());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
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
        new TestDefaultRuleKeyFactory(hashCache, pathResolver, ruleFinder)
            .build(
                new CxxLink(
                    target,
                    projectFilesystem,
                    ImmutableSortedSet::of,
                    TestCellPathResolver.get(projectFilesystem),
                    DEFAULT_LINKER,
                    DEFAULT_OUTPUT,
                    ImmutableMap.of(),
                    DEFAULT_ARGS,
                    Optional.empty(),
                    Optional.empty(),
                    /* cacheable */ true,
                    /* thinLto */ false));

    // Verify that changing the archiver causes a rulekey change.

    RuleKey linkerChange =
        new TestDefaultRuleKeyFactory(hashCache, pathResolver, ruleFinder)
            .build(
                new CxxLink(
                    target,
                    projectFilesystem,
                    ImmutableSortedSet::of,
                    TestCellPathResolver.get(projectFilesystem),
                    new GnuLinker(
                        new HashedFileTool(
                            PathSourcePath.of(projectFilesystem, Paths.get("different")))),
                    DEFAULT_OUTPUT,
                    ImmutableMap.of(),
                    DEFAULT_ARGS,
                    Optional.empty(),
                    Optional.empty(),
                    /* cacheable */ true,
                    /* thinLto */ false));
    assertNotEquals(defaultRuleKey, linkerChange);

    // Verify that changing the output path causes a rulekey change.

    RuleKey outputChange =
        new TestDefaultRuleKeyFactory(hashCache, pathResolver, ruleFinder)
            .build(
                new CxxLink(
                    target,
                    projectFilesystem,
                    ImmutableSortedSet::of,
                    TestCellPathResolver.get(projectFilesystem),
                    DEFAULT_LINKER,
                    Paths.get("different"),
                    ImmutableMap.of(),
                    DEFAULT_ARGS,
                    Optional.empty(),
                    Optional.empty(),
                    /* cacheable */ true,
                    /* thinLto */ false));
    assertNotEquals(defaultRuleKey, outputChange);

    // Verify that changing the flags causes a rulekey change.

    RuleKey flagsChange =
        new TestDefaultRuleKeyFactory(hashCache, pathResolver, ruleFinder)
            .build(
                new CxxLink(
                    target,
                    projectFilesystem,
                    ImmutableSortedSet::of,
                    TestCellPathResolver.get(projectFilesystem),
                    DEFAULT_LINKER,
                    DEFAULT_OUTPUT,
                    ImmutableMap.of(),
                    ImmutableList.of(SourcePathArg.of(FakeSourcePath.of("different"))),
                    Optional.empty(),
                    Optional.empty(),
                    /* cacheable */ true,
                    /* thinLto */ false));
    assertNotEquals(defaultRuleKey, flagsChange);
  }

  @Test
  public void sanitizedPathsInFlagsDoNotAffectRuleKey() {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestBuildRuleResolver());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    DefaultRuleKeyFactory ruleKeyFactory =
        new TestDefaultRuleKeyFactory(
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
            ImmutableBiMap.of(Paths.get("something"), "A"));
    DebugPathSanitizer sanitizer2 =
        new MungingDebugPathSanitizer(
            pathSize,
            File.separatorChar,
            Paths.get("PWD"),
            ImmutableBiMap.of(Paths.get("different"), "A"));

    // Generate a rule with a path we need to sanitize to a consistent value.
    ImmutableList<Arg> args1 =
        ImmutableList.of(
            SanitizedArg.create(sanitizer1.sanitize(Optional.empty()), "-Lsomething/foo"));

    RuleKey ruleKey1 =
        ruleKeyFactory.build(
            new CxxLink(
                target,
                projectFilesystem,
                ImmutableSortedSet::of,
                TestCellPathResolver.get(projectFilesystem),
                DEFAULT_LINKER,
                DEFAULT_OUTPUT,
                ImmutableMap.of(),
                args1,
                Optional.empty(),
                Optional.empty(),
                /* cacheable */ true,
                /* thinLto */ false));

    // Generate another rule with a different path we need to sanitize to the
    // same consistent value as above.
    ImmutableList<Arg> args2 =
        ImmutableList.of(
            SanitizedArg.create(sanitizer2.sanitize(Optional.empty()), "-Ldifferent/foo"));

    RuleKey ruleKey2 =
        ruleKeyFactory.build(
            new CxxLink(
                target,
                projectFilesystem,
                ImmutableSortedSet::of,
                TestCellPathResolver.get(projectFilesystem),
                DEFAULT_LINKER,
                DEFAULT_OUTPUT,
                ImmutableMap.of(),
                args2,
                Optional.empty(),
                Optional.empty(),
                /* cacheable */ true,
                /* thinLto */ false));

    assertEquals(ruleKey1, ruleKey2);
  }
}
