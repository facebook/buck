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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rulekey.RuleKeyAppendable;
import com.facebook.buck.core.rulekey.RuleKeyObjectSink;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.rules.keys.RuleKeyDiagnostics.Result;
import com.facebook.buck.rules.keys.config.TestRuleKeyConfigurationFactory;
import com.facebook.buck.rules.keys.hasher.StringRuleKeyHasher;
import com.facebook.buck.testutil.DummyFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.impl.DefaultFileHashCache;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.facebook.buck.util.types.Either;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.MapMaker;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.hamcrest.Matchers;
import org.junit.Test;

// There are tons of unused fields in this class.
@SuppressWarnings("unused")
public class DefaultRuleKeyFactoryTest {

  @Test
  public void shouldNotAddUnannotatedFieldsToRuleKey() {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildRule rule = new EmptyFakeBuildRule(target);

    DefaultRuleKeyFactory factory =
        new TestDefaultRuleKeyFactory(new DummyFileHashCache(), pathResolver, ruleFinder);
    RuleKey expected = factory.build(rule);

    class UndecoratedFields extends EmptyFakeBuildRule {
      private String field = "cake-walk";

      public UndecoratedFields() {
        super(target);
      }
    }

    Result<RuleKey, String> result =
        factory.buildForDiagnostics(new UndecoratedFields(), new StringRuleKeyHasher());
    assertThat(result.diagKey, Matchers.not(Matchers.containsString("cake-walk")));
  }

  @Test
  public void shouldAddASingleAnnotatedFieldToRuleKey() {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildRule rule = new EmptyFakeBuildRule(target);

    DefaultRuleKeyFactory factory =
        new TestDefaultRuleKeyFactory(new DummyFileHashCache(), pathResolver, ruleFinder);
    DefaultRuleKeyFactory.Builder<HashCode> builder = factory.newBuilderForTesting(rule);

    builder.setReflectively("field", "cake-walk");
    RuleKey expected = builder.build(RuleKey::new);

    class DecoratedFields extends EmptyFakeBuildRule {

      @AddToRuleKey private String field = "cake-walk";

      public DecoratedFields() {
        super(target);
      }
    }

    Result<RuleKey, String> result =
        factory.buildForDiagnostics(new DecoratedFields(), new StringRuleKeyHasher());
    assertThat(result.diagKey, Matchers.containsString("cake-walk"));
  }

  @Test
  public void shouldAllowAFieldToBeStringified() {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildRule rule = new EmptyFakeBuildRule(target);

    DefaultRuleKeyFactory factory =
        new TestDefaultRuleKeyFactory(new DummyFileHashCache(), pathResolver, ruleFinder);

    class Stringifiable {
      @Override
      public String toString() {
        return "sausages";
      }
    }

    class StringifiedField extends EmptyFakeBuildRule {
      @AddToRuleKey(stringify = true)
      private Stringifiable field = new Stringifiable();

      public StringifiedField() {
        super(target);
      }
    }

    Result<RuleKey, String> result =
        factory.buildForDiagnostics(new StringifiedField(), new StringRuleKeyHasher());
    assertThat(result.diagKey, Matchers.containsString("string(\"sausages\"):key(field)"));
  }

  @Test
  public void shouldAllowAddsToRuleKeysToAppendToRuleKey() {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildRule rule = new EmptyFakeBuildRule(target);

    FileHashCache fileHashCache = new DummyFileHashCache();
    DefaultRuleKeyFactory factory =
        new TestDefaultRuleKeyFactory(fileHashCache, pathResolver, ruleFinder);

    class AppendingField extends EmptyFakeBuildRule {
      @AddToRuleKey private Adder field = new Adder();

      public AppendingField() {
        super(target);
      }
    }

    AppendingField buildRule = new AppendingField();
    Result<RuleKey, String> fieldResult =
        factory.buildForDiagnostics(buildRule.field, new StringRuleKeyHasher());
    Result<RuleKey, String> result =
        factory.buildForDiagnostics(buildRule, new StringRuleKeyHasher());
    assertThat(
        result.diagKey,
        Matchers.containsString(
            String.format("(sha1=%s):wrapper(APPENDABLE):key(field)", fieldResult.ruleKey)));
  }

  @Test
  public void shouldAllowRuleKeyAppendablesToAppendToRuleKey() {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildRule rule = new EmptyFakeBuildRule(target);

    FileHashCache fileHashCache = new DummyFileHashCache();
    DefaultRuleKeyFactory factory =
        new TestDefaultRuleKeyFactory(fileHashCache, pathResolver, ruleFinder);

    class AppendingField extends EmptyFakeBuildRule {
      @AddToRuleKey private Appender field = new Appender();

      public AppendingField() {
        super(target);
      }
    }
    AppendingField buildRule = new AppendingField();
    Result<RuleKey, String> fieldResult =
        factory.buildForDiagnostics(buildRule.field, new StringRuleKeyHasher());
    Result<RuleKey, String> result =
        factory.buildForDiagnostics(buildRule, new StringRuleKeyHasher());
    assertThat(
        result.diagKey,
        Matchers.containsString(
            String.format("(sha1=%s):wrapper(APPENDABLE):key(field)", fieldResult.ruleKey)));
  }

  @Test
  public void annotatedAppendableBuildRulesIncludeTheirRuleKey() {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    BuildTarget depTarget = BuildTargetFactory.newInstance("//cheese:more-peas");
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildRule rule = new EmptyFakeBuildRule(target);

    FileHashCache fileHashCache = new DummyFileHashCache();
    DefaultRuleKeyFactory factory =
        new TestDefaultRuleKeyFactory(fileHashCache, pathResolver, ruleFinder);

    class AppendableRule extends EmptyFakeBuildRule {
      public AppendableRule(BuildTarget target) {
        super(target);
      }

      @Override
      public void appendToRuleKey(RuleKeyObjectSink sink) {
        sink.setReflectively("cheese", "brie");
      }
    }

    AppendableRule appendableRule = new AppendableRule(depTarget);

    class RuleContainingAppendableRule extends EmptyFakeBuildRule {
      @AddToRuleKey private final AppendableRule field;

      public RuleContainingAppendableRule(BuildTarget target, AppendableRule appendableRule) {
        super(target);
        this.field = appendableRule;
      }
    }

    RuleContainingAppendableRule buildRule =
        new RuleContainingAppendableRule(target, appendableRule);
    Result<RuleKey, String> fieldResult =
        factory.buildForDiagnostics(buildRule.field, new StringRuleKeyHasher());
    Result<RuleKey, String> result =
        factory.buildForDiagnostics(buildRule, new StringRuleKeyHasher());
    assertThat(
        result.diagKey,
        Matchers.containsString(
            String.format("(sha1=%s):wrapper(BUILD_RULE):key(field)", fieldResult.ruleKey)));
  }

  @Test
  public void stringifiedAddsToRuleKeysGetAddedToRuleKeyAsStrings() {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildRule rule = new EmptyFakeBuildRule(target);

    DefaultRuleKeyFactory factory =
        new TestDefaultRuleKeyFactory(new DummyFileHashCache(), pathResolver, ruleFinder);

    class AppendingField extends EmptyFakeBuildRule {
      @AddToRuleKey(stringify = true)
      private Adder field = new Adder();

      public AppendingField() {
        super(target);
      }
    }

    AppendingField buildRule = new AppendingField();
    Result<RuleKey, String> fieldResult =
        factory.buildForDiagnostics(buildRule.field, new StringRuleKeyHasher());
    Result<RuleKey, String> result =
        factory.buildForDiagnostics(buildRule, new StringRuleKeyHasher());
    assertThat(
        result.diagKey, Matchers.not(Matchers.containsString(fieldResult.ruleKey.toString())));
    assertThat(result.diagKey, Matchers.not(Matchers.containsString("APPENDABLE")));
    assertThat(result.diagKey, Matchers.containsString("string(\"cheddar\"):key(field)"));
  }

  @Test
  public void stringifiedRuleKeyAppendablesGetAddedToRuleKeyAsStrings() {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildRule rule = new EmptyFakeBuildRule(target);

    DefaultRuleKeyFactory factory =
        new TestDefaultRuleKeyFactory(new DummyFileHashCache(), pathResolver, ruleFinder);

    class AppendingField extends EmptyFakeBuildRule {
      @AddToRuleKey(stringify = true)
      private Appender field = new Appender();

      public AppendingField() {
        super(target);
      }
    }

    AppendingField buildRule = new AppendingField();
    Result<RuleKey, String> fieldResult =
        factory.buildForDiagnostics(buildRule.field, new StringRuleKeyHasher());
    Result<RuleKey, String> result =
        factory.buildForDiagnostics(buildRule, new StringRuleKeyHasher());
    assertThat(
        result.diagKey, Matchers.not(Matchers.containsString(fieldResult.ruleKey.toString())));
    assertThat(result.diagKey, Matchers.not(Matchers.containsString("APPENDABLE")));
    assertThat(result.diagKey, Matchers.containsString("string(\"cheddar\"):key(field)"));
  }

  @Test
  public void fieldsAreAddedInAlphabeticalOrder() {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildRule rule = new EmptyFakeBuildRule(target);

    DefaultRuleKeyFactory factory =
        new TestDefaultRuleKeyFactory(new DummyFileHashCache(), pathResolver, ruleFinder);

    class UnsortedFields extends EmptyFakeBuildRule {
      @AddToRuleKey private String gamma = "stinking bishop";
      @AddToRuleKey private int beta = 1;
      @AddToRuleKey private String alpha = "stilton";

      public UnsortedFields() {
        super(target);
      }
    }

    Result<RuleKey, String> result =
        factory.buildForDiagnostics(new UnsortedFields(), new StringRuleKeyHasher());
    assertThat(
        result.diagKey,
        Matchers.containsString(
            "string(\"stilton\"):key(alpha):number(1):key(beta):string(\"stinking bishop\"):key(gamma):"));
  }

  @Test
  public void fieldsFromParentClassesShouldBeAddedAndFieldsRetainOverallAlphabeticalOrdering() {
    BuildTarget topLevelTarget = BuildTargetFactory.newInstance("//cheese:peas");
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildRule rule = new EmptyFakeBuildRule(topLevelTarget);

    DefaultRuleKeyFactory factory =
        new TestDefaultRuleKeyFactory(new DummyFileHashCache(), pathResolver, ruleFinder);

    class Parent extends EmptyFakeBuildRule {
      @AddToRuleKey private BuildTarget target;

      public Parent(BuildTarget target) {
        super(target);
        this.target = target;
      }
    }

    class Child extends Parent {
      @AddToRuleKey private String exoticCheese = "bavarian smoked";

      public Child(BuildTarget target) {
        super(target);
      }
    }

    Result<RuleKey, String> result =
        factory.buildForDiagnostics(new Child(topLevelTarget), new StringRuleKeyHasher());
    assertThat(
        result.diagKey,
        Matchers.containsString(
            "string(\"bavarian smoked\"):key(exoticCheese):target(//cheese:peas):key(target):"));
  }

  @Test
  public void fieldsFromParentClassesAreAlsoAdded() {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildRule rule = new EmptyFakeBuildRule(target);

    DefaultRuleKeyFactory factory =
        new TestDefaultRuleKeyFactory(new DummyFileHashCache(), pathResolver, ruleFinder);
    DefaultRuleKeyFactory.Builder<HashCode> builder = factory.newBuilderForTesting(rule);

    builder.setReflectively("key", "child");
    builder.setReflectively("key", "parent");
    RuleKey expected = builder.build(RuleKey::new);

    class Parent extends EmptyFakeBuildRule {
      @AddToRuleKey private String key = "parent";

      public Parent() {
        super(target);
      }
    }

    class Child extends Parent {
      @AddToRuleKey private String key = "child";

      public Child() {}
    }

    Result<RuleKey, String> result =
        factory.buildForDiagnostics(new Child(), new StringRuleKeyHasher());
    assertThat(
        result.diagKey,
        Matchers.containsString(":string(\"child\"):key(key):string(\"parent\"):key(key):"));
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
    assertBothKeysAndValuesGetHashed((char) 0, (char) 42);
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
    assertBothKeysAndValuesGetHashed(
        RuleType.of("rule_type", RuleType.Kind.BUILD), RuleType.of("type2", RuleType.Kind.BUILD));
    assertBothKeysAndValuesGetHashed(
        BuildTargetFactory.newInstance(Paths.get("/root"), "//example/base:one"),
        BuildTargetFactory.newInstance(Paths.get("/root"), "//example/base:two"));

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

  @Test
  public void testFactoryReportsInputsAndDependenciesToCacheForBuildRule() throws IOException {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    NoopRuleKeyCache<RuleKey> noopRuleKeyCache = new NoopRuleKeyCache<>();
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    DefaultRuleKeyFactory factory =
        new DefaultRuleKeyFactory(
            new RuleKeyFieldLoader(TestRuleKeyConfigurationFactory.create()),
            new StackedFileHashCache(
                ImmutableList.of(
                    DefaultFileHashCache.createDefaultFileHashCache(
                        filesystem, FileHashCacheMode.DEFAULT))),
            pathResolver,
            ruleFinder,
            noopRuleKeyCache,
            Optional.empty());

    // Create a sample input.
    PathSourcePath input = FakeSourcePath.of(filesystem, "input");
    filesystem.touch(input.getRelativePath());

    // Create a sample dep rule.
    BuildRule dep = new EmptyFakeBuildRule(BuildTargetFactory.newInstance("//:dep"));

    // Create a sample rule key appendable.
    RuleKeyAppendable appendable = sink -> {};

    // Create a dummy build rule that uses the input.
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:target");
    BuildRule rule =
        new NoopBuildRuleWithDeclaredAndExtraDeps(
            buildTarget,
            filesystem,
            TestBuildRuleParams.create().withDeclaredDeps(ImmutableSortedSet.of(dep))) {

          @AddToRuleKey private final SourcePath inputField = input;

          @AddToRuleKey private final RuleKeyAppendable appendableField = appendable;
        };

    // Build the rule key.
    factory.build(rule);

    // Verify the input was properly reported to the rule key cache.
    RuleKeyResult<RuleKey> result = noopRuleKeyCache.results.get(rule);
    assertThat(result, Matchers.notNullValue());
    assertThat(
        result.inputs,
        Matchers.containsInAnyOrder(RuleKeyInput.of(filesystem, input.getRelativePath())));
    assertThat(result.deps, Matchers.containsInAnyOrder(dep, appendable));
  }

  @Test
  public void testFactoryReportsInputsAndDependenciesToCacheForRuleKeyAppendable()
      throws IOException {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    NoopRuleKeyCache<RuleKey> noopRuleKeyCache = new NoopRuleKeyCache<>();
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    DefaultRuleKeyFactory factory =
        new DefaultRuleKeyFactory(
            new RuleKeyFieldLoader(TestRuleKeyConfigurationFactory.create()),
            new StackedFileHashCache(
                ImmutableList.of(
                    DefaultFileHashCache.createDefaultFileHashCache(
                        filesystem, FileHashCacheMode.DEFAULT))),
            pathResolver,
            ruleFinder,
            noopRuleKeyCache,
            Optional.empty());

    // Create a sample input.
    PathSourcePath input = FakeSourcePath.of(filesystem, "input");
    filesystem.touch(input.getRelativePath());

    // Create a sample dep rule.
    BuildRule dep = new EmptyFakeBuildRule(BuildTargetFactory.newInstance("//:dep"));

    // Create a sample dep appendable.
    RuleKeyAppendable depAppendable = sink -> {};

    // Create a sample rule key appendable.
    RuleKeyAppendable appendable =
        sink -> {
          sink.setReflectively("input", input);
          sink.setReflectively("dep", dep);
          sink.setReflectively("depAppendable", depAppendable);
        };

    // Create a dummy build rule that uses the input.
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//:target");
    BuildRule rule =
        new NoopBuildRuleWithDeclaredAndExtraDeps(
            buildTarget, filesystem, TestBuildRuleParams.create()) {

          @AddToRuleKey private final RuleKeyAppendable appendableField = appendable;
        };

    // Build the rule key.
    factory.build(rule);

    // Verify the input was properly reported to the rule key cache.
    RuleKeyResult<RuleKey> result = noopRuleKeyCache.results.get(appendable);
    assertThat(result, Matchers.notNullValue());
    assertThat(
        result.inputs,
        Matchers.containsInAnyOrder(RuleKeyInput.of(filesystem, input.getRelativePath())));
    assertThat(result.deps, Matchers.containsInAnyOrder(dep, depAppendable));
  }

  private void assertBothKeysAndValuesGetHashed(@Nullable Object val1, @Nullable Object val2) {
    assertKeysGetHashed(val1);
    assertValuesGetHashed(val1, val2);
  }

  private void assertKeysGetHashed(@Nullable Object val) {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildRule rule = new EmptyFakeBuildRule(target);

    DefaultRuleKeyFactory factory =
        new TestDefaultRuleKeyFactory(new DummyFileHashCache(), pathResolver, ruleFinder);

    RuleKey key1 =
        factory.newBuilderForTesting(rule).setReflectively("key1", val).build(RuleKey::new);
    RuleKey key1again =
        factory.newBuilderForTesting(rule).setReflectively("key1", val).build(RuleKey::new);
    RuleKey key2 =
        factory.newBuilderForTesting(rule).setReflectively("key2", val).build(RuleKey::new);
    assertEquals("Rule keys should be same! " + val, key1, key1again);
    assertNotEquals("Rule keys should be different! " + val, key1, key2);
  }

  private void assertValuesGetHashed(@Nullable Object val1, @Nullable Object val2) {
    Preconditions.checkArgument(!Objects.equal(val1, val2), "Values should be different!");
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(new TestActionGraphBuilder());
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    BuildRule rule = new EmptyFakeBuildRule(target);

    DefaultRuleKeyFactory factory =
        new TestDefaultRuleKeyFactory(new DummyFileHashCache(), pathResolver, ruleFinder);

    RuleKey key1 =
        factory.newBuilderForTesting(rule).setReflectively("key", val1).build(RuleKey::new);
    RuleKey key1again =
        factory.newBuilderForTesting(rule).setReflectively("key", val1).build(RuleKey::new);
    RuleKey key2 =
        factory.newBuilderForTesting(rule).setReflectively("key", val2).build(RuleKey::new);
    assertEquals("Rule keys should be same! " + val1, key1, key1again);
    assertNotEquals("Rule keys should be different! " + val1 + " != " + val2, key1, key2);
  }

  private static class Adder implements AddsToRuleKey {
    @AddToRuleKey private String cheese = "brie";
    @AddToRuleKey private String wine = "cabernet";

    @Override
    public String toString() {
      return "cheddar";
    }
  }

  private static class Appender implements RuleKeyAppendable {
    @AddToRuleKey private String wine = "cabernet";

    @Override
    public void appendToRuleKey(RuleKeyObjectSink sink) {
      sink.setReflectively("cheese", "brie");
    }

    @Override
    public String toString() {
      return "cheddar";
    }
  }

  private enum DummyEnum {
    BLACK,
    WHITE,
  }

  private static class NoopRuleKeyCache<V> implements RuleKeyCache<V> {

    private final Map<Object, RuleKeyResult<V>> results = new MapMaker().weakKeys().makeMap();

    @Nullable
    @Override
    public V get(BuildRule rule) {
      RuleKeyResult<V> result = results.get(rule);
      if (result != null) {
        return result.result;
      }
      return null;
    }

    @Override
    public V get(BuildRule rule, Function<? super BuildRule, RuleKeyResult<V>> create) {
      RuleKeyResult<V> result = create.apply(rule);
      results.put(rule, result);
      return result.result;
    }

    @Override
    public V get(
        AddsToRuleKey appendable, Function<? super AddsToRuleKey, RuleKeyResult<V>> create) {
      RuleKeyResult<V> result = create.apply(appendable);
      results.put(appendable, result);
      return result.result;
    }

    public boolean isCached(BuildRule rule) {
      throw new UnsupportedOperationException();
    }

    public boolean isCached(AddsToRuleKey appendable) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void invalidateInputs(Iterable<RuleKeyInput> inputs) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void invalidateAllExceptFilesystems(ImmutableSet<ProjectFilesystem> filesystems) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void invalidateFilesystem(ProjectFilesystem filesystem) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void invalidateAll() {
      throw new UnsupportedOperationException();
    }
  }
}
