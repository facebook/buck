/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.rules.modern.impl;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.createStrictMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.CustomClassSerialization;
import com.facebook.buck.rules.modern.CustomFieldSerialization;
import com.facebook.buck.rules.modern.Deserializer;
import com.facebook.buck.rules.modern.Deserializer.DataProvider;
import com.facebook.buck.rules.modern.Serializer;
import com.facebook.buck.rules.modern.Serializer.Delegate;
import com.facebook.buck.rules.modern.SourcePathResolverSerialization;
import com.facebook.buck.rules.modern.ValueCreator;
import com.facebook.buck.rules.modern.ValueVisitor;
import com.facebook.buck.rules.modern.annotations.CustomClassBehavior;
import com.facebook.buck.rules.modern.annotations.CustomFieldBehavior;
import com.facebook.buck.rules.modern.annotations.DefaultFieldSerialization;
import com.facebook.buck.util.types.Either;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;

public class BuildableSerializerTest extends AbstractValueVisitorTest {
  private SourcePathRuleFinder ruleFinder;
  private CellPathResolver cellResolver;
  private SourcePathResolver resolver;

  @Before
  public void setUp() throws IOException, InterruptedException {
    resolver = createStrictMock(SourcePathResolver.class);
    ruleFinder = createStrictMock(SourcePathRuleFinder.class);
    cellResolver = createStrictMock(CellPathResolver.class);

    expect(cellResolver.getCellPaths())
        .andReturn(ImmutableMap.of("other", otherFilesystem.getRootPath()))
        .anyTimes();
    expect(cellResolver.getCellPath(Optional.empty()))
        .andReturn(Optional.of(rootFilesystem.getRootPath()))
        .anyTimes();
    expect(cellResolver.getCellPathOrThrow(Optional.empty()))
        .andReturn(rootFilesystem.getRootPath())
        .anyTimes();
  }

  static DataProvider getDataProvider(
      Map<HashCode, byte[]> dataMap, Map<HashCode, List<HashCode>> childMap, HashCode hash) {
    return new DataProvider() {
      @Override
      public InputStream getData() {
        return new ByteArrayInputStream(Preconditions.checkNotNull(dataMap.get(hash)));
      }

      @Override
      public DataProvider getChild(HashCode hash) {
        return getDataProvider(dataMap, childMap, hash);
      }
    };
  }

  <T extends Buildable> T test(T instance) throws IOException {
    return test(instance, expected -> expected);
  }

  <T extends Buildable> T test(T instance, Function<String, String> expectedMapper)
      throws IOException {
    replay(cellResolver, ruleFinder);

    Map<HashCode, byte[]> dataMap = new HashMap<>();
    Map<HashCode, List<HashCode>> childMap = new HashMap<>();

    Delegate serializerDelegate =
        (value, data, children) -> {
          int id = dataMap.size();
          HashCode hash = HashCode.fromInt(id);
          dataMap.put(hash, data);
          childMap.put(hash, children);
          return hash;
        };

    Either<HashCode, byte[]> serialized =
        new Serializer(ruleFinder, cellResolver, serializerDelegate)
            .serialize(instance, DefaultClassInfoFactory.forInstance(instance));

    AddsToRuleKey reconstructed =
        new Deserializer(
                s -> s.isPresent() ? otherFilesystem : rootFilesystem,
                Class::forName,
                () -> resolver)
            .deserialize(
                new DataProvider() {
                  @Override
                  public InputStream getData() {
                    return new ByteArrayInputStream(
                        serialized.transform(left -> dataMap.get(left), right -> right));
                  }

                  @Override
                  public DataProvider getChild(HashCode hash) {
                    return getDataProvider(dataMap, childMap, hash);
                  }
                },
                AddsToRuleKey.class);
    Preconditions.checkState(instance.getClass().equals(reconstructed.getClass()));
    verify(cellResolver, ruleFinder);
    assertEquals(expectedMapper.apply(stringify(instance)), stringify(reconstructed));
    return (T) reconstructed;
  }

  private String stringify(AddsToRuleKey instance) {
    StringifyingValueVisitor visitor = new StringifyingValueVisitor();
    DefaultClassInfoFactory.forInstance(instance).visit(instance, visitor);
    return String.format(
        "%s {\n  %s\n}",
        instance.getClass().getName(), Joiner.on("\n  ").join(visitor.getValue().split("\n")));
  }

  @Override
  @Test
  public void outputPath() throws IOException {
    test(new WithOutputPath());
  }

  @Test
  @Override
  public void sourcePath() throws IOException {
    test(new WithSourcePath());
  }

  @Override
  @Test
  public void set() throws IOException {
    test(new WithSet());
  }

  @Test
  @Override
  public void list() throws IOException {
    test(new WithList());
  }

  @Test
  @Override
  public void optional() throws IOException {
    test(new WithOptional());
  }

  @Test
  @Override
  public void simple() throws IOException {
    test(new Simple());
  }

  @Test
  @Override
  public void superClass() throws IOException {
    test(new TwiceDerived());
  }

  @Test
  @Override
  public void empty() throws IOException {
    test(new Empty());
  }

  @Test
  @Override
  public void addsToRuleKey() throws IOException {
    test(new WithAddsToRuleKey());
  }

  @Test
  @Override
  public void complex() throws IOException {
    BuildRule mockRule = createStrictMock(BuildRule.class);
    BuildTarget target =
        BuildTargetFactory.newInstance(rootFilesystem.getRootPath(), "//some/build:target");
    expect(ruleFinder.getRule((SourcePath) anyObject())).andReturn(Optional.of(mockRule));
    mockRule.getSourcePathToOutput();
    expectLastCall().andReturn(ExplicitBuildTargetSourcePath.of(target, Paths.get("and.path")));
    replay(mockRule);
    test(
        new Complex(),
        expected ->
            expected.replace(
                "SourcePath(//some/build:target)",
                "SourcePath(Pair(//some/build:target, and.path))"));
    verify(mockRule);
  }

  @Test
  @Override
  public void buildTarget() throws IOException {
    test(new WithBuildTarget());
  }

  @Override
  @Test
  public void pattern() throws Exception {
    test(new WithPattern());
  }

  @Override
  @Test
  public void anEnum() throws Exception {
    test(new WithEnum());
  }

  @Override
  @Test
  public void nonHashableSourcePathContainer() throws Exception {
    test(new WithNonHashableSourcePathContainer());
  }

  @Override
  @Test
  public void sortedMap() throws Exception {
    test(new WithSortedMap());
  }

  @Override
  @Test
  public void supplier() throws Exception {
    test(new WithSupplier());
  }

  @Override
  @Test
  public void nullable() throws Exception {
    test(new WithNullable());
  }

  @Override
  @Test
  public void either() throws Exception {
    test(new WithEither());
  }

  @Override
  @Test
  public void excluded() throws Exception {
    expectedException.expect(Exception.class);
    expectedException.expectMessage(Matchers.containsString("Cannot create excluded fields."));
    test(new WithExcluded());
  }

  @Override
  @Test
  public void immutables() throws Exception {
    test(new WithImmutables());
  }

  @Test
  public void customFieldBehavior() throws Exception {
    test(new WithCustomFieldBehavior());
  }

  @Override
  @Test
  public void stringified() throws Exception {
    expectedException.expect(Exception.class);
    expectedException.expectMessage(Matchers.containsString("Cannot create excluded fields."));
    test(new WithStringified());
  }

  @Override
  @Test
  public void wildcards() throws Exception {
    test(new WithWildcards());
  }

  private static class WithCustomFieldBehavior implements FakeBuildable {
    // By default, fields without @AddToRuleKey can't be serialized. DefaultFieldSerialization
    // serializes them as though they were added to the key.
    @CustomFieldBehavior(DefaultFieldSerialization.class)
    private final String excluded = "excluded";

    @AddToRuleKey
    @CustomFieldBehavior(SpecialFieldSerialization.class)
    private final ImmutableList<String> paths = ImmutableList.of("Hello", " ", "world", "!");
  }

  private static class SpecialFieldSerialization
      implements CustomFieldSerialization<ImmutableList<String>> {
    @Override
    public <E extends Exception> void serialize(
        ImmutableList<String> value, ValueVisitor<E> serializer) throws E {
      serializer.visitString("key");
    }

    @Override
    public <E extends Exception> ImmutableList<String> deserialize(ValueCreator<E> deserializer)
        throws E {
      assertEquals("key", deserializer.createString());
      return ImmutableList.of("Hello", " ", "world", "!");
    }
  }

  @Test
  public void customClassBehavior() throws Exception {
    test(new WithCustomClassBehavior());
  }

  @CustomClassBehavior(SpecialClassSerialization.class)
  private static class WithCustomClassBehavior implements FakeBuildable {
    private final String value = "value";
    @AddToRuleKey private final int number = 3;
  }

  private static class SpecialClassSerialization
      implements CustomClassSerialization<WithCustomClassBehavior> {
    @Override
    public void serialize(WithCustomClassBehavior instance, ValueVisitor<IOException> serializer)
        throws IOException {
      assertEquals("value", instance.value);
      assertEquals(3, instance.number);
      serializer.visitString("special");
    }

    @Override
    public WithCustomClassBehavior deserialize(ValueCreator<IOException> deserializer)
        throws IOException {
      assertEquals("special", deserializer.createString());
      return new WithCustomClassBehavior();
    }
  }

  @Test
  public void sourcePathResolver() throws Exception {
    WithSourcePathResolver reconstructed = test(new WithSourcePathResolver());
    assertEquals(resolver, reconstructed.resolver);
  }

  private static class WithSourcePathResolver implements FakeBuildable {
    @CustomFieldBehavior(SourcePathResolverSerialization.class)
    private final SourcePathResolver resolver = null;
  }
}
