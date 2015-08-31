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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.NullFileHashCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;

import javax.annotation.Nullable;

// There are tons of unused fields in this class.
@SuppressWarnings("unused")
public class DefaultRuleKeyBuilderFactoryTest {

  @Test
  public void shouldNotAddUnannotatedFieldsToRuleKey() {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildRule rule = new EmptyRule(target);

    DefaultRuleKeyBuilderFactory factory =
        new DefaultRuleKeyBuilderFactory(new NullFileHashCache(), pathResolver);
    RuleKeyBuilder builder = factory.newInstance(rule);

    RuleKey expected = builder.build();

    class UndecoratedFields extends EmptyRule {

      private String field = "cake-walk";

      public UndecoratedFields(BuildTarget target) {
        super(target);
      }
    }

    RuleKeyBuilder seen = factory.newInstance(new UndecoratedFields(target));

    assertEquals(expected, seen.build());
  }

  @Test
  public void shouldAddASingleAnnotatedFieldToRuleKey() {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildRule rule = new EmptyRule(target);

    DefaultRuleKeyBuilderFactory factory =
        new DefaultRuleKeyBuilderFactory(new NullFileHashCache(), pathResolver);
    RuleKeyBuilder builder = factory.newInstance(rule);

    builder.setReflectively("field", "cake-walk");
    RuleKey expected = builder.build();

    class DecoratedFields extends EmptyRule {

      @AddToRuleKey
      private String field = "cake-walk";

      public DecoratedFields(BuildTarget target) {
        super(target);
      }
    }

    RuleKeyBuilder seen = factory.newInstance(new DecoratedFields(target));

    assertEquals(expected, seen.build());
  }



  @Test
  public void shouldAllowAFieldToBeStringified() {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildRule rule = new EmptyRule(target);

    DefaultRuleKeyBuilderFactory factory =
        new DefaultRuleKeyBuilderFactory(new NullFileHashCache(), pathResolver);
    RuleKeyBuilder builder = factory.newInstance(rule);

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

    RuleKeyBuilder seen = factory.newInstance(new StringifiedField(target));

    assertEquals(expected, seen.build());
  }

  @Test
  public void shouldAllowRuleKeyAppendablesToAppendToRuleKey() {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildRule rule = new EmptyRule(target);

    FileHashCache fileHashCache = new NullFileHashCache();
    DefaultRuleKeyBuilderFactory factory =
        new DefaultRuleKeyBuilderFactory(fileHashCache, pathResolver);

    RuleKey subKey =
        new RuleKeyBuilder(pathResolver, fileHashCache)
            .setReflectively("cheese", "brie")
            .build();

    RuleKeyBuilder builder = factory.newInstance(rule);
    builder.setReflectively("field.appendableSubKey", subKey);
    RuleKey expected = builder.build();

    class AppendingField extends EmptyRule {

      @AddToRuleKey
      private Appender field = new Appender();

      public AppendingField(BuildTarget target) {
        super(target);
      }
    }

    RuleKeyBuilder seen = factory.newInstance(new AppendingField(target));

    assertEquals(expected, seen.build());
  }

  @Test
  public void annotatedAppendableBuildRulesIncludeTheirRuleKey() {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    BuildTarget depTarget = BuildTargetFactory.newInstance("//cheese:more-peas");
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildRule rule = new EmptyRule(target);

    FileHashCache fileHashCache = new NullFileHashCache();
    DefaultRuleKeyBuilderFactory factory =
        new DefaultRuleKeyBuilderFactory(fileHashCache, pathResolver);

    class AppendableRule extends EmptyRule implements RuleKeyAppendable {
      public AppendableRule(BuildTarget target) {
        super(target);
      }

      @Override
      public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
        return builder.setReflectively("cheese", "brie");
      }

      @Override
      public RuleKey getRuleKey() {
        return new RuleKey("abcd");
      }
    }

    AppendableRule appendableRule = new AppendableRule(depTarget);

    RuleKey subKey =
        new RuleKeyBuilder(pathResolver, fileHashCache)
            .setReflectively("cheese", "brie")
            .build();

    RuleKeyBuilder builder = factory.newInstance(rule);
    builder.setReflectively("field.appendableSubKey", subKey);
    builder.setReflectively("field", appendableRule.getRuleKey());
    RuleKey expected = builder.build();

    class RuleContainingAppendableRule extends EmptyRule {
      @AddToRuleKey
      private final AppendableRule field;

      public RuleContainingAppendableRule(BuildTarget target, AppendableRule appendableRule) {
        super(target);
        this.field = appendableRule;
      }
    }

    RuleKeyBuilder seen = factory.newInstance(
        new RuleContainingAppendableRule(target, appendableRule));

    assertEquals(expected, seen.build());
  }

  @Test
  public void stringifiedRuleKeyAppendablesGetAddedToRuleKeyAsStrings() {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildRule rule = new EmptyRule(target);

    DefaultRuleKeyBuilderFactory factory =
        new DefaultRuleKeyBuilderFactory(new NullFileHashCache(), pathResolver);
    RuleKeyBuilder builder = factory.newInstance(rule);

    builder.setReflectively("field", "cheddar");
    RuleKey expected = builder.build();

    class AppendingField extends EmptyRule {

      @AddToRuleKey(stringify = true)
      private Appender field = new Appender();

      public AppendingField(BuildTarget target) {
        super(target);
      }
    }

    RuleKeyBuilder seen = factory.newInstance(new AppendingField(target));

    assertEquals(expected, seen.build());
  }

  @Test
  public void fieldsAreAddedInAlphabeticalOrder() {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildRule rule = new EmptyRule(target);

    DefaultRuleKeyBuilderFactory factory =
        new DefaultRuleKeyBuilderFactory(new NullFileHashCache(), pathResolver);
    RuleKeyBuilder builder = factory.newInstance(rule);

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

    RuleKeyBuilder seen = factory.newInstance(new UnsortedFields(target));

    assertEquals(expected, seen.build());
  }

  @Test
  public void fieldsFromParentClassesShouldBeAddedAndFieldsRetainOverallAlphabeticalOrdering() {
    BuildTarget topLevelTarget = BuildTargetFactory.newInstance("//cheese:peas");
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildRule rule = new EmptyRule(topLevelTarget);

    DefaultRuleKeyBuilderFactory factory =
        new DefaultRuleKeyBuilderFactory(new NullFileHashCache(), pathResolver);
    RuleKeyBuilder builder = factory.newInstance(rule);

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

    RuleKeyBuilder seen = factory.newInstance(new Child(topLevelTarget));

    assertEquals(expected, seen.build());
  }

  @Test
  public void fieldsFromParentClassesAreAlsoAdded() {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildRule rule = new EmptyRule(target);

    DefaultRuleKeyBuilderFactory factory =
        new DefaultRuleKeyBuilderFactory(new NullFileHashCache(), pathResolver);
    RuleKeyBuilder builder = factory.newInstance(rule);

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

    RuleKeyBuilder seen = factory.newInstance(new Child(target));

    assertEquals(expected, seen.build());
  }

  private static class Appender implements RuleKeyAppendable {
    @Override
    public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
      return builder.setReflectively("cheese", "brie");
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
    public RuleKey getRuleKey() {
      throw new UnsupportedOperationException("getRuleKey");
    }

    @Override
    public RuleKey getRuleKeyWithoutDeps() {
      throw new UnsupportedOperationException("getRuleKeyWithoutDeps");
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
  }
}
