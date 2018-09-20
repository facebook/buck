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
package com.facebook.buck.versions;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.parser.BuildTargetPattern;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.rules.coercer.CoercedTypeCache;
import com.facebook.buck.rules.coercer.ParamInfo;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * A helper class which uses reflection to translate {@link BuildTarget}s in {@link TargetNode}s.
 * The API methods use an {@link Optional} for their return types, so that {@link Optional#empty()}
 * can be used to signify a translation was not needed. This may allow some translation functions to
 * avoid copying or creating unnecessary new objects.
 */
public abstract class TargetNodeTranslator {

  private final TypeCoercerFactory typeCoercerFactory;
  // Translators registered for various types.
  private final ImmutableList<TargetTranslator<?>> translators;

  public TargetNodeTranslator(
      TypeCoercerFactory typeCoercerFactory, ImmutableList<TargetTranslator<?>> translators) {
    this.typeCoercerFactory = typeCoercerFactory;
    this.translators = translators;
  }

  public abstract Optional<BuildTarget> translateBuildTarget(BuildTarget target);

  public abstract Optional<ImmutableMap<BuildTarget, Version>> getSelectedVersions(
      BuildTarget target);

  public <A> Optional<Optional<A>> translateOptional(
      CellPathResolver cellPathResolver,
      BuildTargetPatternParser<BuildTargetPattern> pattern,
      Optional<A> val) {
    if (!val.isPresent()) {
      return Optional.empty();
    }
    Optional<A> inner = translate(cellPathResolver, pattern, val.get());
    if (!inner.isPresent()) {
      return Optional.empty();
    }
    return Optional.of(inner);
  }

  public <A> Optional<ImmutableList<A>> translateList(
      CellPathResolver cellPathResolver,
      BuildTargetPatternParser<BuildTargetPattern> pattern,
      ImmutableList<A> val) {
    boolean modified = false;
    ImmutableList.Builder<A> builder = ImmutableList.builder();
    for (A a : val) {
      Optional<A> item = translate(cellPathResolver, pattern, a);
      modified = modified || item.isPresent();
      builder.add(item.orElse(a));
    }
    return modified ? Optional.of(builder.build()) : Optional.empty();
  }

  public <A> Optional<ImmutableSet<A>> translateSet(
      CellPathResolver cellPathResolver,
      BuildTargetPatternParser<BuildTargetPattern> pattern,
      ImmutableSet<A> val) {
    boolean modified = false;
    ImmutableSet.Builder<A> builder = ImmutableSet.builder();
    for (A a : val) {
      Optional<A> item = translate(cellPathResolver, pattern, a);
      modified = modified || item.isPresent();
      builder.add(item.orElse(a));
    }
    return modified ? Optional.of(builder.build()) : Optional.empty();
  }

  public <A extends Comparable<?>> Optional<ImmutableSortedSet<A>> translateSortedSet(
      CellPathResolver cellPathResolver,
      BuildTargetPatternParser<BuildTargetPattern> pattern,
      ImmutableSortedSet<A> val) {
    boolean modified = false;
    ImmutableSortedSet.Builder<A> builder = ImmutableSortedSet.naturalOrder();
    for (A a : val) {
      Optional<A> item = translate(cellPathResolver, pattern, a);
      modified = modified || item.isPresent();
      builder.add(item.orElse(a));
    }
    return modified ? Optional.of(builder.build()) : Optional.empty();
  }

  public <A extends Comparable<?>, B> Optional<ImmutableMap<A, B>> translateMap(
      CellPathResolver cellPathResolver,
      BuildTargetPatternParser<BuildTargetPattern> pattern,
      ImmutableMap<A, B> val) {
    boolean modified = false;
    ImmutableMap.Builder<A, B> builder = ImmutableMap.builder();
    for (Map.Entry<A, B> ent : val.entrySet()) {
      Optional<A> key = translate(cellPathResolver, pattern, ent.getKey());
      Optional<B> value = translate(cellPathResolver, pattern, ent.getValue());
      modified = modified || key.isPresent() || value.isPresent();
      builder.put(key.orElse(ent.getKey()), value.orElse(ent.getValue()));
    }
    return modified ? Optional.of(builder.build()) : Optional.empty();
  }

  public <A extends Comparable<?>, B> Optional<ImmutableSortedMap<A, B>> translateSortedMap(
      CellPathResolver cellPathResolver,
      BuildTargetPatternParser<BuildTargetPattern> pattern,
      ImmutableSortedMap<A, B> val) {
    boolean modified = false;
    ImmutableSortedMap.Builder<A, B> builder = ImmutableSortedMap.naturalOrder();
    for (Map.Entry<A, B> ent : val.entrySet()) {
      Optional<A> key = translate(cellPathResolver, pattern, ent.getKey());
      Optional<B> value = translate(cellPathResolver, pattern, ent.getValue());
      modified = modified || key.isPresent() || value.isPresent();
      builder.put(key.orElse(ent.getKey()), value.orElse(ent.getValue()));
    }
    return modified ? Optional.of(builder.build()) : Optional.empty();
  }

  public <A, B> Optional<Pair<A, B>> translatePair(
      CellPathResolver cellPathResolver,
      BuildTargetPatternParser<BuildTargetPattern> pattern,
      Pair<A, B> val) {
    Optional<A> first = translate(cellPathResolver, pattern, val.getFirst());
    Optional<B> second = translate(cellPathResolver, pattern, val.getSecond());
    if (!first.isPresent() && !second.isPresent()) {
      return Optional.empty();
    }
    return Optional.of(new Pair<>(first.orElse(val.getFirst()), second.orElse(val.getSecond())));
  }

  public Optional<DefaultBuildTargetSourcePath> translateBuildTargetSourcePath(
      CellPathResolver cellPathResolver,
      BuildTargetPatternParser<BuildTargetPattern> pattern,
      DefaultBuildTargetSourcePath val) {
    BuildTarget target = val.getTarget();
    Optional<BuildTarget> translatedTarget = translate(cellPathResolver, pattern, target);
    return translatedTarget.isPresent()
        ? Optional.of(DefaultBuildTargetSourcePath.of(translatedTarget.get()))
        : Optional.empty();
  }

  public Optional<SourceWithFlags> translateSourceWithFlags(
      CellPathResolver cellPathResolver,
      BuildTargetPatternParser<BuildTargetPattern> pattern,
      SourceWithFlags val) {
    Optional<SourcePath> translatedSourcePath =
        translate(cellPathResolver, pattern, val.getSourcePath());
    return translatedSourcePath.isPresent()
        ? Optional.of(SourceWithFlags.of(translatedSourcePath.get(), val.getFlags()))
        : Optional.empty();
  }

  @SuppressWarnings("unchecked")
  private <A, T> Optional<Optional<T>> tryTranslate(
      CellPathResolver cellPathResolver,
      BuildTargetPatternParser<BuildTargetPattern> pattern,
      TargetTranslator<A> translator,
      T object) {
    Class<A> clazz = translator.getTranslatableClass();
    if (!clazz.isAssignableFrom(object.getClass())) {
      return Optional.empty();
    }
    return Optional.of(
        (Optional<T>) translator.translateTargets(cellPathResolver, pattern, this, (A) object));
  }

  @SuppressWarnings("unchecked")
  public <A> Optional<A> translate(
      CellPathResolver cellPathResolver,
      BuildTargetPatternParser<BuildTargetPattern> pattern,
      A object) {

    // `null`s require no translating.
    if (object == null) {
      return Optional.empty();
    }

    // First try all registered translators.
    for (TargetTranslator<?> translator : translators) {
      if (translator.getTranslatableClass().isAssignableFrom(object.getClass())) {
        Optional<Optional<A>> translated =
            tryTranslate(cellPathResolver, pattern, translator, object);
        if (translated.isPresent()) {
          return translated.get();
        }
      }
    }

    if (object instanceof Optional) {
      return (Optional<A>) translateOptional(cellPathResolver, pattern, (Optional<?>) object);
    } else if (object instanceof ImmutableList) {
      return (Optional<A>) translateList(cellPathResolver, pattern, (ImmutableList<?>) object);
    } else if (object instanceof ImmutableSortedSet) {
      return (Optional<A>)
          translateSortedSet(
              cellPathResolver, pattern, (ImmutableSortedSet<? extends Comparable<?>>) object);
    } else if (object instanceof ImmutableSet) {
      return (Optional<A>) translateSet(cellPathResolver, pattern, (ImmutableSet<?>) object);
    } else if (object instanceof ImmutableSortedMap) {
      return (Optional<A>)
          translateSortedMap(
              cellPathResolver, pattern, (ImmutableSortedMap<? extends Comparable<?>, ?>) object);
    } else if (object instanceof ImmutableMap) {
      return (Optional<A>)
          translateMap(
              cellPathResolver, pattern, (ImmutableMap<? extends Comparable<?>, ?>) object);
    } else if (object instanceof Pair) {
      return (Optional<A>) translatePair(cellPathResolver, pattern, (Pair<?, ?>) object);
    } else if (object instanceof DefaultBuildTargetSourcePath) {
      return (Optional<A>)
          translateBuildTargetSourcePath(
              cellPathResolver, pattern, (DefaultBuildTargetSourcePath) object);
    } else if (object instanceof SourceWithFlags) {
      return (Optional<A>)
          translateSourceWithFlags(cellPathResolver, pattern, (SourceWithFlags) object);
    } else if (object instanceof BuildTarget) {
      return (Optional<A>) translateBuildTarget((BuildTarget) object);
    } else if (object instanceof TargetTranslatable) {
      TargetTranslatable<A> targetTranslatable = (TargetTranslatable<A>) object;
      return targetTranslatable.translateTargets(cellPathResolver, pattern, this);
    } else {
      return Optional.empty();
    }
  }

  public boolean translateConstructorArg(
      CellPathResolver cellPathResolver,
      BuildTargetPatternParser<BuildTargetPattern> pattern,
      Object constructorArg,
      Object newConstructorArgOrBuilder) {
    boolean modified = false;

    for (ParamInfo param :
        CoercedTypeCache.INSTANCE
            .getAllParamInfo(typeCoercerFactory, constructorArg.getClass())
            .values()) {
      Object value = param.get(constructorArg);
      Optional<Object> newValue = translate(cellPathResolver, pattern, value);
      modified |= newValue.isPresent();
      param.setCoercedValue(newConstructorArgOrBuilder, newValue.orElse(value));
    }
    return modified;
  }

  private <A> Optional<A> translateConstructorArg(
      CellPathResolver cellPathResolver,
      BuildTargetPatternParser<BuildTargetPattern> pattern,
      TargetNode<A> node) {
    A constructorArg = node.getConstructorArg();
    if (node.getDescription() instanceof TargetTranslatorOverridingDescription) {
      return ((TargetTranslatorOverridingDescription<A>) node.getDescription())
          .translateConstructorArg(
              node.getBuildTarget(), node.getCellNames(), this, constructorArg);
    } else {
      Pair<Object, Function<Object, A>> newArgAndBuild =
          CoercedTypeCache.instantiateSkeleton(
              node.getDescription().getConstructorArgType(), node.getBuildTarget());
      boolean modified =
          translateConstructorArg(
              cellPathResolver, pattern, constructorArg, newArgAndBuild.getFirst());
      if (!modified) {
        return Optional.empty();
      }
      return Optional.of(newArgAndBuild.getSecond().apply(newArgAndBuild.getFirst()));
    }
  }

  /**
   * @return a copy of the given {@link TargetNode} with all found {@link BuildTarget}s translated,
   *     or {@link Optional#empty()} if the node requires no translation.
   */
  public <A> Optional<TargetNode<A>> translateNode(TargetNode<A> node) {
    CellPathResolver cellPathResolver = node.getCellNames();
    BuildTargetPatternParser<BuildTargetPattern> pattern =
        BuildTargetPatternParser.forBaseName(node.getBuildTarget().getBaseName());

    Optional<BuildTarget> target = translateBuildTarget(node.getBuildTarget());
    Optional<A> constructorArg = translateConstructorArg(cellPathResolver, pattern, node);
    Optional<ImmutableSet<BuildTarget>> declaredDeps =
        translateSet(cellPathResolver, pattern, node.getDeclaredDeps());
    Optional<ImmutableSortedSet<BuildTarget>> extraDeps =
        translateSortedSet(cellPathResolver, pattern, node.getExtraDeps());
    Optional<ImmutableSortedSet<BuildTarget>> targetGraphOnlyDeps =
        translateSortedSet(cellPathResolver, pattern, node.getTargetGraphOnlyDeps());

    Optional<ImmutableMap<BuildTarget, Version>> newSelectedVersions =
        getSelectedVersions(node.getBuildTarget());
    Optional<ImmutableMap<BuildTarget, Version>> oldSelectedVersions = node.getSelectedVersions();
    Optional<Optional<ImmutableMap<BuildTarget, Version>>> selectedVersions =
        oldSelectedVersions.equals(newSelectedVersions)
            ? Optional.empty()
            : Optional.of(newSelectedVersions);

    // If nothing has changed, don't generate a new node.
    if (!target.isPresent()
        && !constructorArg.isPresent()
        && !declaredDeps.isPresent()
        && !extraDeps.isPresent()
        && !targetGraphOnlyDeps.isPresent()
        && !selectedVersions.isPresent()) {
      return Optional.empty();
    }

    return Optional.of(
        node.withBuildTarget(target.orElse(node.getBuildTarget()))
            .withConstructorArg(constructorArg.orElse(node.getConstructorArg()))
            .withDeclaredDeps(declaredDeps.orElse(node.getDeclaredDeps()))
            .withExtraDeps(extraDeps.orElse(node.getExtraDeps()))
            .withTargetGraphOnlyDeps(targetGraphOnlyDeps.orElse(node.getTargetGraphOnlyDeps()))
            .withSelectedVersions(selectedVersions.orElse(oldSelectedVersions)));
  }
}
