/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.cxx.toolchain.DebugPathSanitizer;
import com.facebook.buck.cxx.toolchain.MungingDebugPathSanitizer;
import com.facebook.buck.rules.args.SanitizedArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import java.io.File;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
@RunWith(Enclosed.class)
public class PreprocessorFlagsTest {

  @RunWith(Parameterized.class)
  public static class FieldsAffectingRuleKeys {
    private static final PreprocessorFlags defaultFlags = PreprocessorFlags.builder().build();

    @Parameterized.Parameters(name = "field: {0} shouldAffectRuleKey: {2}")
    public static Collection<Object[]> data() {
      return Arrays.asList(
          new Object[][] {
            {
              "otherFlags (platform)",
              defaultFlags.withOtherFlags(
                  CxxToolFlags.explicitBuilder().addPlatformFlags(StringArg.of("-DFOO")).build()),
              true,
            },
            {
              "otherFlags (rule)",
              defaultFlags.withOtherFlags(
                  CxxToolFlags.explicitBuilder().addRuleFlags(StringArg.of("-DFOO")).build()),
              true,
            },
            {
              "frameworkPaths",
              defaultFlags.withFrameworkPaths(
                  FrameworkPath.ofSourcePath(FakeSourcePath.of("different"))),
              true,
            },
            {
              "prefixHeader", defaultFlags.withPrefixHeader(FakeSourcePath.of("different")), true,
            }
          });
    }

    @Parameterized.Parameter(0)
    public String caseName;

    @Parameterized.Parameter(1)
    public PreprocessorFlags alteredFlags;

    @Parameterized.Parameter(2)
    public boolean shouldDiffer;

    @Test
    public void shouldAffectRuleKey() {
      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
      SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
      BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
      FakeFileHashCache hashCache =
          FakeFileHashCache.createFromStrings(
              ImmutableMap.of("different", Strings.repeat("d", 40)));
      BuildRule fakeBuildRule = new FakeBuildRule(target);

      DefaultRuleKeyFactory.Builder<HashCode> builder;
      builder =
          new TestDefaultRuleKeyFactory(hashCache, pathResolver, ruleFinder)
              .newBuilderForTesting(fakeBuildRule);
      builder.setReflectively("flags", defaultFlags);
      RuleKey defaultRuleKey = builder.build(RuleKey::new);

      builder =
          new TestDefaultRuleKeyFactory(hashCache, pathResolver, ruleFinder)
              .newBuilderForTesting(fakeBuildRule);
      builder.setReflectively("flags", alteredFlags);
      RuleKey alteredRuleKey = builder.build(RuleKey::new);

      if (shouldDiffer) {
        Assert.assertNotEquals(defaultRuleKey, alteredRuleKey);
      } else {
        Assert.assertEquals(defaultRuleKey, alteredRuleKey);
      }
    }
  }

  public static class OtherTests {
    @Test
    public void flagsAreSanitized() {
      SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
      SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
      BuildTarget target = BuildTargetFactory.newInstance("//foo:bar");
      FakeFileHashCache hashCache = FakeFileHashCache.createFromStrings(ImmutableMap.of());
      BuildRule fakeBuildRule = new FakeBuildRule(target);

      class TestData {
        public RuleKey generate(String prefix) {
          DebugPathSanitizer sanitizer =
              new MungingDebugPathSanitizer(
                  10,
                  File.separatorChar,
                  Paths.get("PWD"),
                  ImmutableBiMap.of(Paths.get(prefix), "A"));

          CxxToolFlags flags =
              CxxToolFlags.explicitBuilder()
                  .addAllPlatformFlags(
                      SanitizedArg.from(
                          sanitizer.sanitize(Optional.empty()),
                          ImmutableList.of("-I" + prefix + "/foo")))
                  .addAllRuleFlags(
                      SanitizedArg.from(
                          sanitizer.sanitize(Optional.empty()),
                          ImmutableList.of("-I" + prefix + "/bar")))
                  .build();

          DefaultRuleKeyFactory.Builder<HashCode> builder =
              new TestDefaultRuleKeyFactory(hashCache, pathResolver, ruleFinder)
                  .newBuilderForTesting(fakeBuildRule);
          builder.setReflectively(
              "flags", PreprocessorFlags.builder().setOtherFlags(flags).build());
          return builder.build(RuleKey::new);
        }
      }

      TestData testData = new TestData();
      Assert.assertEquals(testData.generate("something"), testData.generate("different"));
    }
  }
}
