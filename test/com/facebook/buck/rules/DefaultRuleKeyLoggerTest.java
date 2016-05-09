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

package com.facebook.buck.rules;

import static org.junit.Assert.assertThat;

import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.hash.HashCode;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.annotation.Nullable;

public class DefaultRuleKeyLoggerTest {

  private static final Path FAKE_PATH = Paths.get("foo", "bar", "path", "1");

  private static class TestAppendable implements RuleKeyAppendable {
    @Override
    public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
      builder.setReflectively("appendableKey", "appendableValue");
      return builder;
    }
  }

  private static class TestRule extends FakeBuildRule {
    @AddToRuleKey
    @Nullable
    String stringField;

    @AddToRuleKey
    @Nullable
    SourcePath pathField;

    @AddToRuleKey
    @Nullable
    TestAppendable appendableField;

    public TestRule(
        BuildTarget buildTarget,
        SourcePathResolver resolver,
        String stringField,
        SourcePath pathField,
        TestAppendable testAppendable) {
      super(buildTarget, resolver);
      this.stringField = stringField;
      this.pathField = pathField;
      this.appendableField = testAppendable;
    }
  }

  @Test
  public void nullFieldsDontHaveKeys() {
    Fixture fixture = new Fixture();

    TestRule fakeRule = new TestRule(
        BuildTargetFactory.newInstance("//:foo"),
        fixture.getPathResolver(),
        null,
        null,
        null);

    fixture.getRuleKeyBuilderFactory().build(fakeRule);

    assertThat(
        fixture.getLogger().getCurrentLogElements(),
        Matchers.contains(
            "string(\"//:foo\"):", "key(name):",
            "string(\"test_rule\"):", "key(buck.type):",
            "string(\"N/A\"):", "key(buckVersionUid):"));
  }

  @Test
  public void stringAndPathFields() {
    Fixture fixture = new Fixture();

    TestRule fakeRule = new TestRule(
        BuildTargetFactory.newInstance("//:foo"),
        fixture.getPathResolver(),
        "testString",
        new FakeSourcePath(FAKE_PATH.toString()),
        null);

    fixture.getRuleKeyBuilderFactory().build(fakeRule);

    String expectedPath = "path(" +
        MorePaths.pathWithPlatformSeparators("foo/bar/path/1") +
        ":f1134a34c0de):";
    assertThat(
        fixture.getLogger().getCurrentLogElements(),
        Matchers.contains(
            "string(\"//:foo\"):", "key(name):",
            "string(\"test_rule\"):", "key(buck.type):",
            "string(\"N/A\"):", "key(buckVersionUid):",
            expectedPath, "key(pathField):",
            "string(\"testString\"):", "key(stringField):"));
  }

  @Test
  public void archiveMemberPathField() {
    Fixture fixture = new Fixture();

    TestRule fakeRule = new TestRule(
        BuildTargetFactory.newInstance("//:foo"),
        fixture.getPathResolver(),
        null,
        new ArchiveMemberSourcePath(new FakeSourcePath(FAKE_PATH.toString()), Paths.get("member")),
        null);

    fixture.getRuleKeyBuilderFactory().build(fakeRule);

    String expectedArchiveMember = "archiveMember(" +
        MorePaths.pathWithPlatformSeparators("foo/bar/path/1") +
        "!/member:f1134a34c0de):";
    assertThat(
        fixture.getLogger().getCurrentLogElements(),
        Matchers.contains(
            "string(\"//:foo\"):", "key(name):",
            "string(\"test_rule\"):", "key(buck.type):",
            "string(\"N/A\"):", "key(buckVersionUid):",
            expectedArchiveMember, "key(pathField):"));

  }

  @Test
  public void appendable() {
    Fixture fixture = new Fixture();

    TestRule fakeRule = new TestRule(
        BuildTargetFactory.newInstance("//:foo"),
        fixture.getPathResolver(),
        null,
        null,
        new TestAppendable());

    fixture.getRuleKeyBuilderFactory().build(fakeRule);

    assertThat(
        fixture.getLogger().getCurrentLogElements(),
        Matchers.contains(
            "string(\"//:foo\"):", "key(name):",
            "string(\"test_rule\"):", "key(buck.type):",
            "string(\"N/A\"):", "key(buckVersionUid):",
            "ruleKey(sha1=c2f62bbb5efd77b2a8b2b086824d16d03c17251d):",
                "key(appendableField.appendableSubKey):"));
  }

  private class Fixture {
    private SourcePathResolver pathResolver;
    private DefaultRuleKeyLogger logger;
    private RuleKeyBuilderFactory ruleKeyBuilderFactory;

    public Fixture() {
      pathResolver = new SourcePathResolver(
          new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));

      final FileHashCache hashCache = new FileHashCache() {
        @Override
        public boolean willGet(Path path) {
          return true;
        }

        @Override
        public boolean willGet(ArchiveMemberPath archiveMemberPath) {
          return true;
        }

        @Override
        public void invalidate(Path path) {
        }

        @Override
        public void invalidateAll() {
        }

        @Override
        public HashCode get(Path path) throws IOException {
          return HashCode.fromString("f1134a34c0de");
        }

        @Override
        public HashCode get(ArchiveMemberPath archiveMemberPath) {
          return HashCode.fromString("f1134a34c0de");
        }

        @Override
        public void set(Path path, HashCode hashCode) {
        }
      };
      logger = new DefaultRuleKeyLogger();
      ruleKeyBuilderFactory = new DefaultRuleKeyBuilderFactory(hashCache, pathResolver) {
        @Override
        protected RuleKeyBuilder newBuilder(BuildRule rule) {
          return new UncachedRuleKeyBuilder(pathResolver, hashCache, this, logger);
        }
      };
    }

    public SourcePathResolver getPathResolver() {
      return pathResolver;
    }

    public DefaultRuleKeyLogger getLogger() {
      return logger;
    }

    public RuleKeyBuilderFactory getRuleKeyBuilderFactory() {
      return ruleKeyBuilderFactory;
    }
  }
}
