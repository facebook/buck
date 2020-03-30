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

package com.facebook.buck.versions;

import com.facebook.buck.core.cell.Cells;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.arg.ConstructorArg;
import com.facebook.buck.core.model.BaseName;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.rules.coercer.DataTransferObjectDescriptor;
import com.facebook.buck.rules.coercer.ParamInfo;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.types.Pair;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Map;
import java.util.Optional;

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
  private final Cells cells;

  public TargetNodeTranslator(
      TypeCoercerFactory typeCoercerFactory,
      ImmutableList<TargetTranslator<?>> translators,
      Cells cells) {

    this.typeCoercerFactory = typeCoercerFactory;
    this.translators = translators;
    this.cells = cells;
  }

  public abstract Optional<BuildTarget> translateBuildTarget(BuildTarget target);

  public abstract Optional<ImmutableMap<BuildTarget, Version>> getSelectedVersions(
      BuildTarget target);

  private <A> Optional<Optional<A>> translateOptional(
      CellNameResolver cellNameResolver, BaseName targetBaseName, Optional<A> val) {
    if (!val.isPresent()) {
      return Optional.empty();
    }
    Optional<A> inner = translate(cellNameResolver, targetBaseName, val.get());
    if (!inner.isPresent()) {
      return Optional.empty();
    }
    return Optional.of(inner);
  }

  private <A> Optional<ImmutableList<A>> translateList(
      CellNameResolver cellNameResolver, BaseName targetBaseName, ImmutableList<A> val) {
    boolean modified = false;
    ImmutableList.Builder<A> builder = ImmutableList.builder();
    for (A a : val) {
      Optional<A> item = translate(cellNameResolver, targetBaseName, a);
      modified = modified || item.isPresent();
      builder.add(item.orElse(a));
    }
    return modified ? Optional.of(builder.build()) : Optional.empty();
  }

  private <A> Optional<ImmutableSet<A>> translateSet(
      CellNameResolver cellNameResolver, BaseName targetBaseName, ImmutableSet<A> val) {
    boolean modified = false;
    ImmutableSet.Builder<A> builder = ImmutableSet.builder();
    for (A a : val) {
      Optional<A> item = translate(cellNameResolver, targetBaseName, a);
      modified = modified || item.isPresent();
      builder.add(item.orElse(a));
    }
    return modified ? Optional.of(builder.build()) : Optional.empty();
  }

  private <A extends Comparable<?>> Optional<ImmutableSortedSet<A>> translateSortedSet(
      CellNameResolver cellNameResolver, BaseName targetBaseName, ImmutableSortedSet<A> val) {
    boolean modified = false;
    ImmutableSortedSet.Builder<A> builder = ImmutableSortedSet.naturalOrder();
    for (A a : val) {
      Optional<A> item = translate(cellNameResolver, targetBaseName, a);
      modified = modified || item.isPresent();
      builder.add(item.orElse(a));
    }
    return modified ? Optional.of(builder.build()) : Optional.empty();
  }

  private <A extends Comparable<?>, B> Optional<ImmutableMap<A, B>> translateMap(
      CellNameResolver cellNameResolver, BaseName targetBaseName, ImmutableMap<A, B> val) {
    boolean modified = false;
    ImmutableMap.Builder<A, B> builder = ImmutableMap.builder();
    for (Map.Entry<A, B> ent : val.entrySet()) {
      Optional<A> key = translate(cellNameResolver, targetBaseName, ent.getKey());
      Optional<B> value = translate(cellNameResolver, targetBaseName, ent.getValue());
      modified = modified || key.isPresent() || value.isPresent();
      builder.put(key.orElse(ent.getKey()), value.orElse(ent.getValue()));
    }
    return modified ? Optional.of(builder.build()) : Optional.empty();
  }

  private <A extends Comparable<?>, B> Optional<ImmutableSortedMap<A, B>> translateSortedMap(
      CellNameResolver cellNameResolver, BaseName targetBaseName, ImmutableSortedMap<A, B> val) {
    boolean modified = false;
    ImmutableSortedMap.Builder<A, B> builder = ImmutableSortedMap.naturalOrder();
    for (Map.Entry<A, B> ent : val.entrySet()) {
      Optional<A> key = translate(cellNameResolver, targetBaseName, ent.getKey());
      Optional<B> value = translate(cellNameResolver, targetBaseName, ent.getValue());
      modified = modified || key.isPresent() || value.isPresent();
      builder.put(key.orElse(ent.getKey()), value.orElse(ent.getValue()));
    }
    return modified ? Optional.of(builder.build()) : Optional.empty();
  }

  @VisibleForTesting
  <A, B> Optional<Pair<A, B>> translatePair(
      CellNameResolver cellNameResolver, BaseName targetBaseName, Pair<A, B> val) {
    Optional<A> first = translate(cellNameResolver, targetBaseName, val.getFirst());
    Optional<B> second = translate(cellNameResolver, targetBaseName, val.getSecond());
    if (!first.isPresent() && !second.isPresent()) {
      return Optional.empty();
    }
    return Optional.of(new Pair<>(first.orElse(val.getFirst()), second.orElse(val.getSecond())));
  }

  @VisibleForTesting
  Optional<DefaultBuildTargetSourcePath> translateBuildTargetSourcePath(
      CellNameResolver cellNameResolver,
      BaseName targetBaseName,
      DefaultBuildTargetSourcePath val) {
    BuildTarget target = val.getTarget();
    Optional<BuildTarget> translatedTarget = translate(cellNameResolver, targetBaseName, target);
    return translatedTarget.map(DefaultBuildTargetSourcePath::of);
  }

  @VisibleForTesting
  Optional<SourceWithFlags> translateSourceWithFlags(
      CellNameResolver cellNameResolver, BaseName targetBaseName, SourceWithFlags val) {
    Optional<SourcePath> translatedSourcePath =
        translate(cellNameResolver, targetBaseName, val.getSourcePath());
    return translatedSourcePath.map(sourcePath -> SourceWithFlags.of(sourcePath, val.getFlags()));
  }

  @SuppressWarnings("unchecked")
  private <A, T> Optional<Optional<T>> tryTranslate(
      CellNameResolver cellPathResolver,
      BaseName targetBaseName,
      TargetTranslator<A> translator,
      T object) {
    Class<A> clazz = translator.getTranslatableClass();
    if (!clazz.isAssignableFrom(object.getClass())) {
      return Optional.empty();
    }
    return Optional.of(
        (Optional<T>)
            translator.translateTargets(cellPathResolver, targetBaseName, this, (A) object));
  }

  @SuppressWarnings("unchecked")
  public <A> Optional<A> translate(
      CellNameResolver cellNameResolver, BaseName targetBaseName, A object) {

    // `null`s require no translating.
    if (object == null) {
      return Optional.empty();
    }

    // First try all registered translators.
    for (TargetTranslator<?> translator : translators) {
      if (translator.getTranslatableClass().isAssignableFrom(object.getClass())) {
        Optional<Optional<A>> translated =
            tryTranslate(cellNameResolver, targetBaseName, translator, object);
        if (translated.isPresent()) {
          return translated.get();
        }
      }
    }

    if (object instanceof Optional) {
      return (Optional<A>)
          translateOptional(cellNameResolver, targetBaseName, (Optional<?>) object);
    } else if (object instanceof ImmutableList) {
      return (Optional<A>)
          translateList(cellNameResolver, targetBaseName, (ImmutableList<?>) object);
    } else if (object instanceof ImmutableSortedSet) {
      return (Optional<A>)
          translateSortedSet(
              cellNameResolver,
              targetBaseName,
              (ImmutableSortedSet<? extends Comparable<?>>) object);
    } else if (object instanceof ImmutableSet) {
      return (Optional<A>) translateSet(cellNameResolver, targetBaseName, (ImmutableSet<?>) object);
    } else if (object instanceof ImmutableSortedMap) {
      return (Optional<A>)
          translateSortedMap(
              cellNameResolver,
              targetBaseName,
              (ImmutableSortedMap<? extends Comparable<?>, ?>) object);
    } else if (object instanceof ImmutableMap) {
      return (Optional<A>)
          translateMap(
              cellNameResolver, targetBaseName, (ImmutableMap<? extends Comparable<?>, ?>) object);
    } else if (object instanceof Pair) {
      return (Optional<A>) translatePair(cellNameResolver, targetBaseName, (Pair<?, ?>) object);
    } else if (object instanceof DefaultBuildTargetSourcePath) {
      return (Optional<A>)
          translateBuildTargetSourcePath(
              cellNameResolver, targetBaseName, (DefaultBuildTargetSourcePath) object);
    } else if (object instanceof SourceWithFlags) {
      return (Optional<A>)
          translateSourceWithFlags(cellNameResolver, targetBaseName, (SourceWithFlags) object);
    } else if (object instanceof BuildTarget) {
      return (Optional<A>) translateBuildTarget((BuildTarget) object);
    } else if (object instanceof TargetTranslatable) {
      TargetTranslatable<A> targetTranslatable = (TargetTranslatable<A>) object;
      return targetTranslatable.translateTargets(cellNameResolver, targetBaseName, this);
    } else {
      return Optional.empty();
    }
  }

  private boolean translateConstructorArg(
      CellNameResolver cellNameResolver,
      BaseName targetBaseName,
      ConstructorArg constructorArg,
      Object newConstructorArgOrBuilder) {
    boolean modified = false;

    for (ParamInfo<?> param :
        typeCoercerFactory
            .getConstructorArgDescriptor(constructorArg.getClass())
            .getParamInfos()
            .values()) {
      Object value = param.get(constructorArg);
      Optional<Object> newValue = translate(cellNameResolver, targetBaseName, value);
      modified |= newValue.isPresent();
      param.setCoercedValue(newConstructorArgOrBuilder, newValue.orElse(value));
    }
    return modified;
  }

  private <A extends ConstructorArg> Optional<A> translateConstructorArg(
      BaseName targetBaseName, TargetNode<A> node) {
    CellNameResolver cellNameResolver =
        cells.getCell(node.getBuildTarget().getCell()).getCellNameResolver();

    A constructorArg = node.getConstructorArg();
    DataTransferObjectDescriptor<A> newArgAndBuild =
        typeCoercerFactory.getConstructorArgDescriptor(
            node.getDescription().getConstructorArgType());
    Object builder = newArgAndBuild.getBuilderFactory().get();
    boolean modified =
        translateConstructorArg(cellNameResolver, targetBaseName, constructorArg, builder);
    if (!modified) {
      return Optional.empty();
    }
    return Optional.of(newArgAndBuild.build(builder, node.getBuildTarget()));
  }

  /**
   * @return a copy of the given {@link TargetNode} with all found {@link BuildTarget}s translated,
   *     or {@link Optional#empty()} if the node requires no translation.
   */
  public <A extends ConstructorArg> Optional<TargetNode<A>> translateNode(TargetNode<A> node) {
    CellNameResolver cellNameResolver =
        cells.getCell(node.getBuildTarget().getCell()).getCellNameResolver();

    BaseName targetBaseName = node.getBuildTarget().getBaseName();

    Optional<BuildTarget> target = translateBuildTarget(node.getBuildTarget());
    Optional<A> constructorArg = translateConstructorArg(targetBaseName, node);
    Optional<ImmutableSet<BuildTarget>> declaredDeps =
        translateSet(cellNameResolver, targetBaseName, node.getDeclaredDeps());
    Optional<ImmutableSortedSet<BuildTarget>> extraDeps =
        translateSortedSet(cellNameResolver, targetBaseName, node.getExtraDeps());
    Optional<ImmutableSortedSet<BuildTarget>> targetGraphOnlyDeps =
        translateSortedSet(cellNameResolver, targetBaseName, node.getTargetGraphOnlyDeps());

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
