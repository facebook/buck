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

import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

public class ArchiveTest {

  private static final Archiver DEFAULT_ARCHIVER = new GnuArchiver(
      new HashedFileTool(Paths.get("ar")));
  private static final Path DEFAULT_OUTPUT = Paths.get("foo/libblah.a");
  private static final ImmutableList<SourcePath> DEFAULT_INPUTS =
      ImmutableList.<SourcePath>of(
          new TestSourcePath("a.o"),
          new TestSourcePath("b.o"),
          new TestSourcePath("c.o"));

  private RuleKey generateRuleKey(
      RuleKeyBuilderFactory factory,
      AbstractBuildRule rule) {

    RuleKeyBuilder builder = factory.newInstance(rule);
    return builder.build();
  }

  @Test
  public void testThatInputChangesCauseRuleKeyChanges() {
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    RuleKeyBuilderFactory ruleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(
            FakeFileHashCache.createFromStrings(
              ImmutableMap.of(
                  "ar", Strings.repeat("0", 40),
                  "a.o", Strings.repeat("a", 40),
                  "b.o", Strings.repeat("b", 40),
                  "c.o", Strings.repeat("c", 40),
                  "different", Strings.repeat("d", 40))),
            pathResolver);

    // Generate a rule key for the defaults.
    RuleKey defaultRuleKey = generateRuleKey(
        ruleKeyBuilderFactory,
        new Archive(
            params,
            pathResolver,
            DEFAULT_ARCHIVER,
            DEFAULT_OUTPUT,
            DEFAULT_INPUTS));

    // Verify that changing the archiver causes a rulekey change.
    RuleKey archiverChange = generateRuleKey(
        ruleKeyBuilderFactory,
        new Archive(
            params,
            pathResolver,
            new GnuArchiver(new HashedFileTool(Paths.get("different"))),
            DEFAULT_OUTPUT,
            DEFAULT_INPUTS));
    assertNotEquals(defaultRuleKey, archiverChange);

    // Verify that changing the output path causes a rulekey change.
    RuleKey outputChange = generateRuleKey(
        ruleKeyBuilderFactory,
        new Archive(
            params,
            pathResolver,
            DEFAULT_ARCHIVER,
            Paths.get("different"),
            DEFAULT_INPUTS));
    assertNotEquals(defaultRuleKey, outputChange);

    // Verify that changing the inputs causes a rulekey change.
    RuleKey inputChange = generateRuleKey(
        ruleKeyBuilderFactory,
        new Archive(
            params,
            pathResolver,
            DEFAULT_ARCHIVER,
            DEFAULT_OUTPUT,
            ImmutableList.<SourcePath>of(new TestSourcePath("different"))));
    assertNotEquals(defaultRuleKey, inputChange);

    // Verify that changing the type of archiver causes a rulekey change.
    RuleKey archiverTypeChange = generateRuleKey(
        ruleKeyBuilderFactory,
        new Archive(
            params,
            pathResolver,
            new BsdArchiver(new HashedFileTool(Paths.get("ar"))),
            DEFAULT_OUTPUT,
            DEFAULT_INPUTS));
    assertNotEquals(defaultRuleKey, archiverTypeChange);

  }

}
