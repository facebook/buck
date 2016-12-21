/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.rules.keys;

import static com.facebook.buck.rules.BuildableProperties.Kind.LIBRARY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Either;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourceRoot;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.UncachedRuleKeyBuilder;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.NullFileHashCache;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

// There are tons of unused fields in this class.
@SuppressWarnings("unused")
public class DefaultRuleKeyFactoryTest {

  @Test
  public void shouldNotAddUnannotatedFieldsToRuleKey() {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    BuildRule rule = new EmptyRule(target);

    DefaultRuleKeyFactory factory =
        new DefaultRuleKeyFactory(0, new NullFileHashCache(), pathResolver);
    RuleKey expected = factory.build(rule);

    class UndecoratedFields extends EmptyRule {

      private String field = "cake-walk";

      public UndecoratedFields(BuildTarget target) {
        super(target);
      }
    }

    RuleKey seen = factory.build(new UndecoratedFields(target));

    assertEquals(expected, seen);
  }

  @Test
  public void shouldAddASingleAnnotatedFieldToRuleKey() {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    BuildRule rule = new EmptyRule(target);

    DefaultRuleKeyFactory factory =
        new DefaultRuleKeyFactory(0, new NullFileHashCache(), pathResolver);
    RuleKeyBuilder<RuleKey> builder = factory.newInstance(rule);

    builder.setReflectively("field", "cake-walk");
    RuleKey expected = builder.build();

    class DecoratedFields extends EmptyRule {

      @AddToRuleKey
      private String field = "cake-walk";

      public DecoratedFields(BuildTarget target) {
        super(target);
      }
    }

    RuleKey seen = factory.build(new DecoratedFields(target));

    assertEquals(expected, seen);
  }



  @Test
  public void shouldAllowAFieldToBeStringified() {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    BuildRule rule = new EmptyRule(target);

    DefaultRuleKeyFactory factory =
        new DefaultRuleKeyFactory(0, new NullFileHashCache(), pathResolver);
    RuleKeyBuilder<RuleKey> builder = factory.newInstance(rule);

    builder.setReflectively("field", "sausages");
    RuleKey expected = builder.build();

    class Stringifiable {
      @Override
      public String toString() {
        return "sausages";
      }
    }

    class StringifiedField extends EmptyRule {

      @AddToRuleKey(stringify = true)
      private Stringifiable field = new Stringifiable();

      public StringifiedField(BuildTarget target) {
        super(target);
      }
    }

    RuleKey seen = factory.build(new StringifiedField(target));

    assertEquals(expected, seen);
  }

  @Test
  public void shouldAllowRuleKeyAppendablesToAppendToRuleKey() {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    BuildRule rule = new EmptyRule(target);

    FileHashCache fileHashCache = new NullFileHashCache();
    DefaultRuleKeyFactory factory =
        new DefaultRuleKeyFactory(0, fileHashCache, pathResolver);

    RuleKey subKey =
        new UncachedRuleKeyBuilder(pathResolver, fileHashCache, factory)
            .setReflectively("cheese", "brie")
            .build();

    RuleKeyBuilder<RuleKey> builder = factory.newInstance(rule);
    builder.setReflectively("field.appendableSubKey", subKey);
    RuleKey expected = builder.build();

    class AppendingField extends EmptyRule {

      @AddToRuleKey
      private Appender field = new Appender();

      public AppendingField(BuildTarget target) {
        super(target);
      }
    }

    RuleKey seen = factory.build(new AppendingField(target));

    assertEquals(expected, seen);
  }

  @Test
  public void annotatedAppendableBuildRulesIncludeTheirRuleKey() {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    BuildTarget depTarget = BuildTargetFactory.newInstance("//cheese:more-peas");
    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    BuildRule rule = new EmptyRule(target);

    FileHashCache fileHashCache = new NullFileHashCache();
    DefaultRuleKeyFactory factory =
        new DefaultRuleKeyFactory(0, fileHashCache, pathResolver);

    class AppendableRule extends EmptyRule implements RuleKeyAppendable {
      public AppendableRule(BuildTarget target) {
        super(target);
      }

      @Override
      public void appendToRuleKey(RuleKeyObjectSink sink) {
        sink.setReflectively("cheese", "brie");
      }

    }

    AppendableRule appendableRule = new AppendableRule(depTarget);

    RuleKey subKey =
        new UncachedRuleKeyBuilder(pathResolver, fileHashCache, factory)
            .setReflectively("cheese", "brie")
            .build();

    RuleKeyBuilder<RuleKey> builder = factory.newInstance(rule);
    builder.setReflectively("field.appendableSubKey", subKey);
    builder.setReflectively("field", factory.build(appendableRule));
    RuleKey expected = builder.build();

    class RuleContainingAppendableRule extends EmptyRule {
      @AddToRuleKey
      private final AppendableRule field;

      public RuleContainingAppendableRule(BuildTarget target, AppendableRule appendableRule) {
        super(target);
        this.field = appendableRule;
      }
    }

    RuleKey seen = factory.build(
        new RuleContainingAppendableRule(target, appendableRule));

    assertEquals(expected, seen);
  }

  @Test
  public void stringifiedRuleKeyAppendablesGetAddedToRuleKeyAsStrings() {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    BuildRule rule = new EmptyRule(target);

    DefaultRuleKeyFactory factory =
        new DefaultRuleKeyFactory(0, new NullFileHashCache(), pathResolver);
    RuleKeyBuilder<RuleKey> builder = factory.newInstance(rule);

    builder.setReflectively("field", "cheddar");
    RuleKey expected = builder.build();

    class AppendingField extends EmptyRule {

      @AddToRuleKey(stringify = true)
      private Appender field = new Appender();

      public AppendingField(BuildTarget target) {
        super(target);
      }
    }

    RuleKey seen = factory.build(new AppendingField(target));

    assertEquals(expected, seen);
  }

  @Test
  public void fieldsAreAddedInAlphabeticalOrder() {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    BuildRule rule = new EmptyRule(target);

    DefaultRuleKeyFactory factory =
        new DefaultRuleKeyFactory(0, new NullFileHashCache(), pathResolver);
    RuleKeyBuilder<RuleKey> builder = factory.newInstance(rule);

    builder.setReflectively("alpha", "stilton");
    builder.setReflectively("beta", 1);
    builder.setReflectively("gamma", "stinking bishop");
    RuleKey expected = builder.build();

    class UnsortedFields extends EmptyRule {

      @AddToRuleKey
      private String gamma = "stinking bishop";
      @AddToRuleKey
      private int beta = 1;
      @AddToRuleKey
      private String alpha = "stilton";

      public UnsortedFields(BuildTarget target) {
        super(target);
      }
    }

    RuleKey seen = factory.build(new UnsortedFields(target));

    assertEquals(expected, seen);
  }

  @Test
  public void fieldsFromParentClassesShouldBeAddedAndFieldsRetainOverallAlphabeticalOrdering() {
    BuildTarget topLevelTarget = BuildTargetFactory.newInstance("//cheese:peas");
    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    BuildRule rule = new EmptyRule(topLevelTarget);

    DefaultRuleKeyFactory factory =
        new DefaultRuleKeyFactory(0, new NullFileHashCache(), pathResolver);
    RuleKeyBuilder<RuleKey> builder = factory.newInstance(rule);

    builder.setReflectively("exoticCheese", "bavarian smoked");
    builder.setReflectively("target", topLevelTarget.getFullyQualifiedName());
    RuleKey expected = builder.build();

    class Parent extends EmptyRule {

      @AddToRuleKey
      private BuildTarget target;

      public Parent(BuildTarget target) {
        super(target);
        this.target = target;
      }
    }

    class Child extends Parent {

      @AddToRuleKey
      private String exoticCheese = "bavarian smoked";

      public Child(BuildTarget target) {
        super(target);
      }
    }

    RuleKey seen = factory.build(new Child(topLevelTarget));

    assertEquals(expected, seen);
  }

  @Test
  public void fieldsFromParentClassesAreAlsoAdded() {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    BuildRule rule = new EmptyRule(target);

    DefaultRuleKeyFactory factory =
        new DefaultRuleKeyFactory(0, new NullFileHashCache(), pathResolver);
    RuleKeyBuilder<RuleKey> builder = factory.newInstance(rule);

    builder.setReflectively("key", "child");
    builder.setReflectively("key", "parent");
    RuleKey expected = builder.build();

    class Parent extends EmptyRule {
      @AddToRuleKey
      private String key = "parent";

      public Parent(BuildTarget target) {
        super(target);
      }
    }

    class Child extends Parent {
      @AddToRuleKey
      private String key = "child";

      public Child(BuildTarget target) {
        super(target);
      }
    }

    RuleKey seen = factory.build(new Child(target));

    assertEquals(expected, seen);
  }

  @Test
  public void testBothKeysAndValuesGetHashed() {
    assertKeysGetHashed(null);
    assertBothKeysAndValuesGetHashed(true, false);
    assertBothKeysAndValuesGetHashed((double) 123, (double) 42);
    assertBothKeysAndValuesGetHashed((float) 123, (float) 42);
    assertBothKeysAndValuesGetHashed(123, 42); // (int)
    assertBothKeysAndValuesGetHashed((long) 123, (long) 42);
    assertBothKeysAndValuesGetHashed((short) 123, (short) 42);
    assertBothKeysAndValuesGetHashed((byte) 123, (byte) 42);
    assertBothKeysAndValuesGetHashed(new byte[] {1, 2, 3}, new byte[] {4, 2});
    assertBothKeysAndValuesGetHashed(DummyEnum.BLACK, DummyEnum.WHITE);
    assertBothKeysAndValuesGetHashed("abc", "def");
    assertBothKeysAndValuesGetHashed(Pattern.compile("regex1"), Pattern.compile("regex2"));
    assertBothKeysAndValuesGetHashed(
        Sha1HashCode.of("a002b39af204cdfaa5fdb67816b13867c32ac52c"),
        Sha1HashCode.of("b67816b13867c32ac52ca002b39af204cdfaa5fd"));
    assertBothKeysAndValuesGetHashed(
        new RuleKey("a002b39af204cdfaa5fdb67816b13867c32ac52c"),
        new RuleKey("b67816b13867c32ac52ca002b39af204cdfaa5fd"));
    assertBothKeysAndValuesGetHashed(BuildRuleType.of("rule_type"), BuildRuleType.of("type2"));
    assertBothKeysAndValuesGetHashed(
        BuildTargetFactory.newInstance(Paths.get("/root"), "//example/base:one"),
        BuildTargetFactory.newInstance(Paths.get("/root"), "//example/base:two"));
    assertBothKeysAndValuesGetHashed(new SourceRoot("root1"), new SourceRoot("root2"));

    // wrapper types
    assertBothKeysAndValuesGetHashed(Optional.of("abc"), Optional.of("def"));
    assertBothKeysAndValuesGetHashed(Either.ofLeft("abc"), Either.ofLeft("def"));
    assertBothKeysAndValuesGetHashed(Either.ofRight("def"), Either.ofRight("ghi"));
    assertBothKeysAndValuesGetHashed(Suppliers.ofInstance("abc"), Suppliers.ofInstance("def"));

    // iterables & maps
    assertBothKeysAndValuesGetHashed(Arrays.asList(1, 2, 3), Arrays.asList(4, 2));
    assertBothKeysAndValuesGetHashed(ImmutableList.of("abc", "xy"), ImmutableList.of("xy", "abc"));
    assertBothKeysAndValuesGetHashed(ImmutableMap.of("key", "v1"), ImmutableMap.of("key", "v2"));

    // nested
    assertBothKeysAndValuesGetHashed(
        ImmutableMap.of("key", Optional.of(ImmutableList.of(1, 2, 3))),
        ImmutableMap.of("key", Optional.of(ImmutableList.of(1, 2, 4))));
  }

  private void assertBothKeysAndValuesGetHashed(@Nullable Object val1, @Nullable Object val2) {
    assertKeysGetHashed(val1);
    assertValuesGetHashed(val1, val2);
  }

  private void assertKeysGetHashed(@Nullable Object val) {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    BuildRule rule = new EmptyRule(target);

    DefaultRuleKeyFactory factory =
        new DefaultRuleKeyFactory(0, new NullFileHashCache(), pathResolver);

    RuleKey key1 = factory.newInstance(rule).setReflectively("key1", val).build();
    RuleKey key1again = factory.newInstance(rule).setReflectively("key1", val).build();
    RuleKey key2 = factory.newInstance(rule).setReflectively("key2", val).build();
    assertEquals("Rule keys should be same! " + val, key1, key1again);
    assertNotEquals("Rule keys should be different! " + val, key1, key2);
  }

  private void assertValuesGetHashed(@Nullable Object val1, @Nullable Object val2) {
    Preconditions.checkArgument(!Objects.equal(val1, val2), "Values should be different!");
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    BuildRule rule = new EmptyRule(target);

    DefaultRuleKeyFactory factory =
        new DefaultRuleKeyFactory(0, new NullFileHashCache(), pathResolver);

    RuleKey key1 = factory.newInstance(rule).setReflectively("key", val1).build();
    RuleKey key1again = factory.newInstance(rule).setReflectively("key", val1).build();
    RuleKey key2 = factory.newInstance(rule).setReflectively("key", val2).build();
    assertEquals("Rule keys should be same! " + val1, key1, key1again);
    assertNotEquals("Rule keys should be different! " + val1 + " != " + val2, key1, key2);
  }

  private static class Appender implements RuleKeyAppendable {
    @Override
    public void appendToRuleKey(RuleKeyObjectSink sink) {
      sink.setReflectively("cheese", "brie");
    }

    @Override
    public String toString() {
      return "cheddar";
    }
  }

  /**
   * A hollow shell of a build rule containing absolutely no marked up fields.
   */
  private static class EmptyRule implements BuildRule {

    private final BuildTarget target;

    public EmptyRule(BuildTarget target) {
      this.target = target;
    }

    @Override
    public BuildTarget getBuildTarget() {
      return target;
    }

    @Override
    public String getFullyQualifiedName() {
      return target.getFullyQualifiedName();
    }

    @Override
    public String getType() {
      return "empty";
    }

    @Override
    public BuildableProperties getProperties() {
      return new BuildableProperties(LIBRARY);
    }

    @Override
    public ImmutableSortedSet<BuildRule> getDeps() {
      return ImmutableSortedSet.of();
    }

    @Override
    public ProjectFilesystem getProjectFilesystem() {
      return new FakeProjectFilesystem();
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext context, BuildableContext buildableContext) {
      throw new UnsupportedOperationException("getBuildSteps");
    }

    @Nullable
    @Override
    public Path getPathToOutput() {
      return null;
    }

    @Override
    public int compareTo(BuildRule o) {
      throw new UnsupportedOperationException("compareTo");
    }

    @Override
    public boolean isCacheable() {
      return true;
    }
  }

  private enum DummyEnum {
    BLACK,
    WHITE,
  }
}
