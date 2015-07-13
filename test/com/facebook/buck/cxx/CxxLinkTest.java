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
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleParamsFactory;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.junit.Test;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

public class CxxLinkTest {

  private static final Tool DEFAULT_LINKER = new HashedFileTool(Paths.get("ld"));
  private static final Path DEFAULT_OUTPUT = Paths.get("test.exe");
  private static final ImmutableList<SourcePath> DEFAULT_INPUTS = ImmutableList.<SourcePath>of(
      new TestSourcePath("a.o"),
      new TestSourcePath("b.o"),
      new TestSourcePath("libc.a"));
  private static final ImmutableList<String> DEFAULT_ARGS = ImmutableList.of(
      "-rpath",
      "/lib",
      "libc.a");
  private static final ImmutableSet<Path> DEFAULT_FRAMEWORK_ROOTS = ImmutableSet.of(
      Paths.get("/System/Frameworks"));
  private static final DebugPathSanitizer DEFAULT_SANITIZER =
      CxxPlatforms.DEFAULT_DEBUG_PATH_SANITIZER;

  private RuleKey generateRuleKey(
      RuleKeyBuilderFactory factory,
      AbstractBuildRule rule) {

    RuleKey.Builder builder = factory.newInstance(rule);
    return builder.build();
  }

  @Test
  public void testThatInputChangesCauseRuleKeyChanges() {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(
            FakeFileHashCache.createFromStrings(
                ImmutableMap.of(
                    "ld", Strings.repeat("0", 40),
                    "a.o", Strings.repeat("a", 40),
                    "b.o", Strings.repeat("b", 40),
                    "libc.a", Strings.repeat("c", 40),
                    "different", Strings.repeat("d", 40))),
            pathResolver);

    // Generate a rule key for the defaults.
    RuleKey defaultRuleKey = generateRuleKey(
        ruleKeyBuilderFactory,
        new CxxLink(
            params,
            pathResolver,
            DEFAULT_LINKER,
            DEFAULT_OUTPUT,
            DEFAULT_INPUTS,
            DEFAULT_ARGS,
            DEFAULT_FRAMEWORK_ROOTS,
            DEFAULT_SANITIZER));

    // Verify that changing the archiver causes a rulekey change.
    RuleKey linkerChange = generateRuleKey(
        ruleKeyBuilderFactory,
        new CxxLink(
            params,
            pathResolver,
            new HashedFileTool(Paths.get("different")),
            DEFAULT_OUTPUT,
            DEFAULT_INPUTS,
            DEFAULT_ARGS,
            DEFAULT_FRAMEWORK_ROOTS,
            DEFAULT_SANITIZER));
    assertNotEquals(defaultRuleKey, linkerChange);

    // Verify that changing the output path causes a rulekey change.
    RuleKey outputChange = generateRuleKey(
        ruleKeyBuilderFactory,
        new CxxLink(
            params,
            pathResolver,
            DEFAULT_LINKER,
            Paths.get("different"),
            DEFAULT_INPUTS,
            DEFAULT_ARGS,
            DEFAULT_FRAMEWORK_ROOTS,
            DEFAULT_SANITIZER));
    assertNotEquals(defaultRuleKey, outputChange);

    // Verify that changing the inputs causes a rulekey change.
    RuleKey inputChange = generateRuleKey(
        ruleKeyBuilderFactory,
        new CxxLink(
            params,
            pathResolver,
            DEFAULT_LINKER,
            DEFAULT_OUTPUT,
            ImmutableList.<SourcePath>of(new TestSourcePath("different")),
            DEFAULT_ARGS,
            DEFAULT_FRAMEWORK_ROOTS,
            DEFAULT_SANITIZER));
    assertNotEquals(defaultRuleKey, inputChange);

    // Verify that changing the flags causes a rulekey change.
    RuleKey flagsChange = generateRuleKey(
        ruleKeyBuilderFactory,
        new CxxLink(
            params,
            pathResolver,
            DEFAULT_LINKER,
            DEFAULT_OUTPUT,
            DEFAULT_INPUTS,
            ImmutableList.of("-different"),
            DEFAULT_FRAMEWORK_ROOTS,
            DEFAULT_SANITIZER));
    assertNotEquals(defaultRuleKey, flagsChange);


    // Verify that changing the framework roots causes a rulekey change.
    RuleKey frameworkRootsChange = generateRuleKey(
        ruleKeyBuilderFactory,
        new CxxLink(
            params,
            pathResolver,
            DEFAULT_LINKER,
            DEFAULT_OUTPUT,
            DEFAULT_INPUTS,
            DEFAULT_ARGS,
            ImmutableSet.of(Paths.get("/System/DifferentFrameworks")),
            DEFAULT_SANITIZER));
    assertNotEquals(defaultRuleKey, frameworkRootsChange);

  }

  @Test
  public void sanitizedPathsInFlagsDoNotAffectRuleKey() {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = BuildRuleParamsFactory.createTrivialBuildRuleParams(target);
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(
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
        new DebugPathSanitizer(
            pathSize,
            File.separatorChar,
            Paths.get("PWD"),
            ImmutableBiMap.of(Paths.get("something"), Paths.get("A")));
    DebugPathSanitizer sanitizer2 =
        new DebugPathSanitizer(
            pathSize,
            File.separatorChar,
            Paths.get("PWD"),
            ImmutableBiMap.of(Paths.get("different"), Paths.get("A")));

    // Generate a rule with a path we need to sanitize to a consistent value.
    ImmutableList<String> args1 = ImmutableList.of("-Lsomething/foo");
    RuleKey ruleKey1 = generateRuleKey(
        ruleKeyBuilderFactory,
        new CxxLink(
            params,
            pathResolver,
            DEFAULT_LINKER,
            DEFAULT_OUTPUT,
            DEFAULT_INPUTS,
            args1,
            ImmutableSet.of(Paths.get("something/Frameworks")),
            sanitizer1));

    // Generate another rule with a different path we need to sanitize to the
    // same consistent value as above.
    ImmutableList<String> args2 = ImmutableList.of("-Ldifferent/foo");
    RuleKey ruleKey2 = generateRuleKey(
        ruleKeyBuilderFactory,
        new CxxLink(
            params,
            pathResolver,
            DEFAULT_LINKER,
            DEFAULT_OUTPUT,
            DEFAULT_INPUTS,
            args2,
            ImmutableSet.of(Paths.get("different/Frameworks")),
            sanitizer2));

    assertEquals(ruleKey1, ruleKey2);
  }

}
