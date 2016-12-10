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
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;

import org.hamcrest.Matcher;
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
    public void appendToRuleKey(RuleKeyObjectSink sink) {
      sink.setReflectively("appendableKey", "appendableValue");
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
    ImmutableList<Matcher<? super Iterable<? super String>>> matchers =
        ImmutableList.of(
            Matchers.hasItem("key(stringField):"),
            Matchers.hasItem("key(pathField):"),
            Matchers.hasItem("key(appendableField.appendableSubKey):"));

    // First check that these fields show up with the expected format when not null.
    Fixture fixture = new Fixture();
    TestRule rule =
        new TestRule(
            BuildTargetFactory.newInstance("//:foo"),
            fixture.getPathResolver(),
            "hello",
            new FakeSourcePath("hello"),
            new TestAppendable());
    fixture.getRuleKeyFactory().build(rule);
    assertThat(
        fixture.getLogger().getCurrentLogElements(),
        Matchers.allOf(matchers));

    // Now verify that they don't show up when null.
    Fixture nullFixture = new Fixture();
    TestRule nullRule =
        new TestRule(
            BuildTargetFactory.newInstance("//:foo"),
            nullFixture.getPathResolver(),
            null,
            null,
            null);
    nullFixture.getRuleKeyFactory().build(nullRule);
    assertThat(
        nullFixture.getLogger().getCurrentLogElements(),
        Matchers.not(Matchers.anyOf(matchers)));
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

    fixture.getRuleKeyFactory().build(fakeRule);

    String expectedPath = "path(" +
        MorePaths.pathWithPlatformSeparators("foo/bar/path/1") +
        ":f1134a34c0de):";
    assertThat(
        fixture.getLogger().getCurrentLogElements(),
        Matchers.hasItems(
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

    fixture.getRuleKeyFactory().build(fakeRule);

    String expectedArchiveMember = "archiveMember(" +
        MorePaths.pathWithPlatformSeparators("foo/bar/path/1") +
        "!/member:f1134a34c0de):";
    assertThat(
        fixture.getLogger().getCurrentLogElements(),
        Matchers.hasItems(expectedArchiveMember, "key(pathField):"));

  }

  @Test
  @SuppressWarnings("unchecked")
  public void appendable() {
    Fixture fixture = new Fixture();

    TestRule fakeRule = new TestRule(
        BuildTargetFactory.newInstance("//:foo"),
        fixture.getPathResolver(),
        null,
        null,
        new TestAppendable());

    fixture.getRuleKeyFactory().build(fakeRule);

    assertThat(
        fixture.getLogger().getCurrentLogElements(),
        Matchers.hasItems(
            Matchers.startsWith("ruleKey("),
            Matchers.equalTo("key(appendableField.appendableSubKey):")));
  }

  private class Fixture {
    private SourcePathResolver pathResolver;
    private DefaultRuleKeyLogger logger;
    private RuleKeyFactory<RuleKey> ruleKeyFactory;

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
        public long getSize(Path path) {
          return 0;
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
      ruleKeyFactory = new DefaultRuleKeyFactory(0, hashCache, pathResolver) {
        @Override
        protected RuleKeyBuilder<RuleKey> newBuilder(BuildRule rule) {
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

    public RuleKeyFactory<RuleKey> getRuleKeyFactory() {
      return ruleKeyFactory;
    }
  }
}
