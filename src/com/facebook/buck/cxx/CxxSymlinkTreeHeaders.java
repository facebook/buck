/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.cxx;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.CustomFieldBehavior;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.modern.annotations.CustomClassBehavior;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.immutables.BuckStylePrehashedValue;
import com.facebook.buck.cxx.CxxPreprocessables.IncludeType;
import com.facebook.buck.cxx.toolchain.HeaderSymlinkTree;
import com.facebook.buck.rules.keys.RuleKeyAppendable;
import com.facebook.buck.rules.modern.CustomClassSerialization;
import com.facebook.buck.rules.modern.CustomFieldInputs;
import com.facebook.buck.rules.modern.ValueCreator;
import com.facebook.buck.rules.modern.ValueTypeInfo;
import com.facebook.buck.rules.modern.ValueVisitor;
import com.facebook.buck.rules.modern.impl.ValueTypeInfoFactory;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.util.types.Either;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.reflect.TypeToken;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/** Encapsulates headers modeled using a {@link HeaderSymlinkTree}. */
@BuckStylePrehashedValue
@CustomClassBehavior(CxxSymlinkTreeHeaders.SerializationBehavior.class)
public abstract class CxxSymlinkTreeHeaders extends CxxHeaders implements RuleKeyAppendable {

  @SuppressWarnings("immutables")
  private final AtomicReference<Optional<ImmutableList<BuildRule>>> computedDeps =
      new AtomicReference<>(Optional.empty());

  @Override
  @AddToRuleKey
  public abstract CxxPreprocessables.IncludeType getIncludeType();

  @Override
  @CustomFieldBehavior(RootInputsBehavior.class)
  public abstract SourcePath getRoot();

  /**
   * @return the path to add to the preprocessor search path to find the includes. This defaults to
   *     the root, but can be overridden to use an alternate path.
   */
  @CustomFieldBehavior(IncludeRootInputsBehavior.class)
  public abstract Either<PathSourcePath, SourcePath> getIncludeRoot();

  @Override
  public Optional<Path> getResolvedIncludeRoot(SourcePathResolverAdapter resolver) {
    return Optional.of(
        getIncludeRoot()
            .transform(
                left -> resolver.getAbsolutePath(left), right -> resolver.getAbsolutePath(right)));
  }

  @Override
  @CustomFieldBehavior(HeaderMapInputsBehavior.class)
  public abstract Optional<SourcePath> getHeaderMap();

  @Value.Auxiliary
  @CustomFieldBehavior(NameToPathMapInputsBehavior.class)
  public abstract ImmutableSortedMap<Path, SourcePath> getNameToPathMap();

  @Override
  public void addToHeaderPathNormalizer(HeaderPathNormalizer.Builder builder) {
    builder.addSymlinkTree(getRoot(), getNameToPathMap());
  }

  @AddToRuleKey
  public abstract String getSymlinkTreeClass();

  /** @return all deps required by this header pack. */
  @Override
  // This has custom getDeps() logic because the way that the name to path map is added to the
  // rulekey is really slow to compute.
  public Stream<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
    Stream.Builder<BuildRule> builder = Stream.builder();
    ruleFinder.getRule(getRoot()).ifPresent(builder);
    if (getIncludeRoot().isRight()) {
      ruleFinder.getRule(getIncludeRoot().getRight()).ifPresent(builder);
    }
    getHeaderMap().flatMap(ruleFinder::getRule).ifPresent(builder);

    // return a stream of the cached dependencies, or compute and store it
    return Stream.concat(
            computedDeps.get()
                .orElseGet(
                    () -> {
                      // We can cache the list here because getNameToPathMap() is an ImmutableMap,
                      // and if a value in the map is not in ruleFinder, an exception will be
                      // thrown. Since ruleFinder rules only increase, the output of this will never
                      // change if we do not get an exception.
                      ImmutableList.Builder<BuildRule> cachedBuilder = ImmutableList.builder();
                      getNameToPathMap()
                          .values()
                          .forEach(
                              value -> ruleFinder.getRule(value).ifPresent(cachedBuilder::add));
                      ImmutableList<BuildRule> rules = cachedBuilder.build();
                      computedDeps.set(Optional.of(rules));
                      return rules;
                    })
                .stream(),
            builder.build())
        .distinct();
  }

  @Override
  public void appendToRuleKey(RuleKeyAppendableSink sink) {
    getNameToPathMap().forEach(sink::addValue);
  }

  /** @return a {@link CxxHeaders} constructed from the given {@link HeaderSymlinkTree}. */
  public static CxxSymlinkTreeHeaders from(
      HeaderSymlinkTree symlinkTree, CxxPreprocessables.IncludeType includeType) {

    Either<PathSourcePath, SourcePath> includeRoot;
    Optional<SourcePath> headerMap;
    if (includeType == CxxPreprocessables.IncludeType.LOCAL) {
      includeRoot = Either.ofLeft(symlinkTree.getIncludeSourcePath());
      headerMap = symlinkTree.getHeaderMapSourcePath();
    } else {
      includeRoot = Either.ofRight(symlinkTree.getRootSourcePath());
      headerMap = Optional.empty();
    }
    return of(
        includeType,
        symlinkTree.getRootSourcePath(),
        includeRoot,
        headerMap,
        symlinkTree.getLinks(),
        symlinkTree.getClass().getName());
  }

  public static CxxSymlinkTreeHeaders of(
      CxxPreprocessables.IncludeType includeType,
      SourcePath root,
      Either<PathSourcePath, SourcePath> includeRoot,
      Optional<? extends SourcePath> headerMap,
      ImmutableSortedMap<Path, SourcePath> nameToPathMap,
      String symlinkTreeClass) {
    return ImmutableCxxSymlinkTreeHeaders.of(
        includeType, root, includeRoot, headerMap, nameToPathMap, symlinkTreeClass);
  }

  /** Custom serialization. */
  static class SerializationBehavior implements CustomClassSerialization<CxxSymlinkTreeHeaders> {
    static final ValueTypeInfo<IncludeType> INCLUDE_TYPE_TYPE_INFO =
        ValueTypeInfoFactory.forTypeToken(new TypeToken<IncludeType>() {});
    static final ValueTypeInfo<Optional<SourcePath>> HEADER_MAP_TYPE_INFO =
        ValueTypeInfoFactory.forTypeToken(new TypeToken<Optional<SourcePath>>() {});
    static final ValueTypeInfo<Either<PathSourcePath, SourcePath>> INCLUDE_ROOT_TYPE_INFO =
        ValueTypeInfoFactory.forTypeToken(new TypeToken<Either<PathSourcePath, SourcePath>>() {});

    @Override
    public <E extends Exception> void serialize(
        CxxSymlinkTreeHeaders instance, ValueVisitor<E> serializer) throws E {
      INCLUDE_TYPE_TYPE_INFO.visit(instance.getIncludeType(), serializer);
      HEADER_MAP_TYPE_INFO.visit(instance.getHeaderMap(), serializer);
      serializer.visitSourcePath(instance.getRoot());
      INCLUDE_ROOT_TYPE_INFO.visit(instance.getIncludeRoot(), serializer);
      ImmutableSortedMap<Path, SourcePath> nameToPathMap = instance.getNameToPathMap();
      serializer.visitInteger(nameToPathMap.size());
      serializer.visitString(instance.getSymlinkTreeClass());
      RichStream.from(nameToPathMap.entrySet())
          .forEachThrowing(
              entry -> {
                Preconditions.checkState(!entry.getKey().isAbsolute());
                serializer.visitString(entry.getKey().toString());
                serializer.visitSourcePath(entry.getValue());
              });
    }

    @Override
    public <E extends Exception> CxxSymlinkTreeHeaders deserialize(ValueCreator<E> deserializer)
        throws E {
      IncludeType includeType = INCLUDE_TYPE_TYPE_INFO.createNotNull(deserializer);
      Optional<SourcePath> headerMapType = HEADER_MAP_TYPE_INFO.createNotNull(deserializer);
      SourcePath root = deserializer.createSourcePath();
      Either<PathSourcePath, SourcePath> includeRootType =
          INCLUDE_ROOT_TYPE_INFO.createNotNull(deserializer);
      int nameToPathMapSize = deserializer.createInteger();
      String symlinkTreeClass = deserializer.createString();

      ImmutableSortedMap.Builder<Path, SourcePath> nameToPathMapBuilder =
          ImmutableSortedMap.naturalOrder();
      for (int i = 0; i < nameToPathMapSize; i++) {
        nameToPathMapBuilder.put(
            Paths.get(deserializer.createString()), deserializer.createSourcePath());
      }

      return ImmutableCxxSymlinkTreeHeaders.of(
          includeType,
          root,
          includeRootType,
          headerMapType,
          nameToPathMapBuilder.build(),
          symlinkTreeClass);
    }
  }

  private static class NameToPathMapInputsBehavior
      implements CustomFieldInputs<ImmutableSortedMap<Path, SourcePath>> {
    @Override
    public void getInputs(
        ImmutableSortedMap<Path, SourcePath> value, Consumer<SourcePath> consumer) {
      value.values().forEach(consumer);
    }
  }

  private static class IncludeRootInputsBehavior
      implements CustomFieldInputs<Either<PathSourcePath, SourcePath>> {
    @Override
    public void getInputs(Either<PathSourcePath, SourcePath> value, Consumer<SourcePath> consumer) {
      if (value.isRight()) {
        consumer.accept(value.getRight());
      }
    }
  }

  private static class RootInputsBehavior implements CustomFieldInputs<SourcePath> {
    @Override
    public void getInputs(SourcePath value, Consumer<SourcePath> consumer) {
      consumer.accept(value);
    }
  }

  private static class HeaderMapInputsBehavior implements CustomFieldInputs<SourcePath> {
    @Override
    public void getInputs(@Nullable SourcePath value, Consumer<SourcePath> consumer) {
      if (value != null) {
        consumer.accept(value);
      }
    }
  }
}
