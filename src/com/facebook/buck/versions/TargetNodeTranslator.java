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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.TargetNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;

/**
 * A helper class which uses reflection to translate {@link BuildTarget}s in {@link TargetNode}s.
 * The API methods use an {@link Optional} for their return types, so that {@link Optional#empty()}
 * can be used to signify a translation was not needed.  This may allow some translation functions
 * to avoid copying or creating unnecessary new objects.
 */
public abstract class TargetNodeTranslator {

  public abstract Optional<BuildTarget> translateBuildTarget(BuildTarget target);

  public abstract Optional<ImmutableMap<BuildTarget, Version>> getSelectedVersions(
      BuildTarget target);

  public <A> Optional<Optional<A>> translateOptional(Optional<A> val) {
    if (!val.isPresent()) {
      return Optional.empty();
    }
    Optional<A> inner = translate(val.get());
    if (!inner.isPresent()) {
      return Optional.empty();
    }
    return Optional.of(inner);
  }

  public <A> Optional<ImmutableList<A>> translateList(ImmutableList<A> val) {
    boolean modified = false;
    ImmutableList.Builder<A> builder = ImmutableList.builder();
    for (A a : val) {
      Optional<A> item = translate(a);
      modified = modified || item.isPresent();
      builder.add(item.orElse(a));
    }
    return modified ? Optional.of(builder.build()) : Optional.empty();
  }

  public <A> Optional<ImmutableSet<A>> translateSet(ImmutableSet<A> val) {
    boolean modified = false;
    ImmutableSet.Builder<A> builder = ImmutableSet.builder();
    for (A a : val) {
      Optional<A> item = translate(a);
      modified = modified || item.isPresent();
      builder.add(item.orElse(a));
    }
    return modified ? Optional.of(builder.build()) : Optional.empty();
  }

  public <A extends Comparable<?>> Optional<ImmutableSortedSet<A>> translateSortedSet(
      ImmutableSortedSet<A> val) {
    boolean modified = false;
    ImmutableSortedSet.Builder<A> builder = ImmutableSortedSet.naturalOrder();
    for (A a : val) {
      Optional<A> item = translate(a);
      modified = modified || item.isPresent();
      builder.add(item.orElse(a));
    }
    return modified ? Optional.of(builder.build()) : Optional.empty();
  }

  public <A extends Comparable<?>, B> Optional<ImmutableMap<A, B>> translateMap(
      ImmutableMap<A, B> val) {
    boolean modified = false;
    ImmutableMap.Builder<A, B> builder = ImmutableMap.builder();
    for (Map.Entry<A, B> ent : val.entrySet()) {
      Optional<A> key = translate(ent.getKey());
      Optional<B> value = translate(ent.getValue());
      modified = modified || key.isPresent() || value.isPresent();
      builder.put(key.orElse(ent.getKey()), value.orElse(ent.getValue()));
    }
    return modified ? Optional.of(builder.build()) : Optional.empty();
  }

  public <A extends Comparable<?>, B> Optional<ImmutableSortedMap<A, B>> translateSortedMap(
      ImmutableSortedMap<A, B> val) {
    boolean modified = false;
    ImmutableSortedMap.Builder<A, B> builder = ImmutableSortedMap.naturalOrder();
    for (Map.Entry<A, B> ent : val.entrySet()) {
      Optional<A> key = translate(ent.getKey());
      Optional<B> value = translate(ent.getValue());
      modified = modified || key.isPresent() || value.isPresent();
      builder.put(key.orElse(ent.getKey()), value.orElse(ent.getValue()));
    }
    return modified ? Optional.of(builder.build()) : Optional.empty();
  }

  public <A, B> Optional<Pair<A, B>> translatePair(Pair<A, B> val) {
    Optional<A> first = translate(val.getFirst());
    Optional<B> second = translate(val.getSecond());
    if (!first.isPresent() && !second.isPresent()) {
      return Optional.empty();
    }
    return Optional.of(new Pair<>(first.orElse(val.getFirst()), second.orElse(val.getSecond())));
  }

  public Optional<BuildTargetSourcePath> translateBuildTargetSourcePath(BuildTargetSourcePath val) {
    BuildTarget target = val.getTarget();
    Optional<BuildTarget> translatedTarget = translate(target);
    return translatedTarget.isPresent() ?
        Optional.of(new BuildTargetSourcePath(translatedTarget.get())) :
        Optional.empty();
  }

  public Optional<SourceWithFlags> translateSourceWithFlags(SourceWithFlags val) {
    Optional<SourcePath> translatedSourcePath = translate(val.getSourcePath());
    return translatedSourcePath.isPresent() ?
        Optional.of(SourceWithFlags.of(translatedSourcePath.get(), val.getFlags())) :
        Optional.empty();
  }

  @SuppressWarnings("unchecked")
  public <A> Optional<A> translate(
      A object) {
    if (object instanceof Optional) {
      return (Optional<A>) translateOptional((Optional<?>) object);
    } else if (object instanceof ImmutableList) {
      return (Optional<A>) translateList((ImmutableList<?>) object);
    } else if (object instanceof ImmutableSortedSet) {
      return (Optional<A>) translateSortedSet((ImmutableSortedSet<? extends Comparable<?>>) object);
    } else if (object instanceof ImmutableSet) {
      return (Optional<A>) translateSet((ImmutableSet<?>) object);
    } else if (object instanceof ImmutableSortedMap) {
      return (Optional<A>) translateSortedMap(
          (ImmutableSortedMap<? extends Comparable<?>, ?>) object);
    } else if (object instanceof ImmutableMap) {
      return (Optional<A>) translateMap((ImmutableMap<? extends Comparable<?>, ?>) object);
    } else if (object instanceof Pair) {
      return (Optional<A>) translatePair((Pair<?, ?>) object);
    } else if (object instanceof BuildTargetSourcePath) {
      return (Optional<A>) translateBuildTargetSourcePath((BuildTargetSourcePath) object);
    } else if (object instanceof SourceWithFlags) {
      return (Optional<A>) translateSourceWithFlags((SourceWithFlags) object);
    } else if (object instanceof BuildTarget) {
      return (Optional<A>) translateBuildTarget((BuildTarget) object);
    } else if (object instanceof TargetTranslatable) {
      TargetTranslatable<A> targetTranslatable = (TargetTranslatable<A>) object;
      Optional<A> res = targetTranslatable.translateTargets(this);
      return res;
    } else {
      return Optional.empty();
    }
  }

  private <A> Optional<A> translateConstructorArg(TargetNode<A, ?> node) {
    boolean modified = false;

    // Generate the new constructor arg from the original
    A constructorArg = node.getConstructorArg();
    A newConstructorArg = node.getDescription().createUnpopulatedConstructorArg();
    for (Field field : constructorArg.getClass().getFields()) {
      try {
        Object val = field.get(constructorArg);
        Optional<Object> mVal = translate(val);
        modified = modified || mVal.isPresent();
        field.set(newConstructorArg, mVal.orElse(val));
      } catch (IllegalAccessException e) {
        throw new IllegalStateException(e);
      }
    }

    return modified ? Optional.of(newConstructorArg) : Optional.empty();
  }

  /**
   * @return a copy of the given {@link TargetNode} with all found {@link BuildTarget}s translated,
   *         or {@link Optional#empty()} if the node requires no translation.
   */
  public <A> Optional<TargetNode<A, ?>> translateNode(TargetNode<A, ?> node) {
    Optional<BuildTarget> target = translateBuildTarget(node.getBuildTarget());
    Optional<A> constructorArg = translateConstructorArg(node);
    Optional<ImmutableSet<BuildTarget>> declaredDeps = translateSet(node.getDeclaredDeps());
    Optional<ImmutableSet<BuildTarget>> extraDeps = translateSet(node.getExtraDeps());

    Optional<ImmutableMap<BuildTarget, Version>> selectedVersions =
        getSelectedVersions(node.getBuildTarget());
    Optional<ImmutableMap<BuildTarget, Version>> oldSelectedVersions = node.getSelectedVersions();
    if (oldSelectedVersions.equals(selectedVersions)) {
      selectedVersions = Optional.empty();
    }

    // If nothing has changed, don't generate a new node.
    if (!target.isPresent() &&
        !constructorArg.isPresent() &&
        !declaredDeps.isPresent() &&
        !extraDeps.isPresent() &&
        !selectedVersions.isPresent()) {
      return Optional.empty();
    }

    return Optional.of(
        node.withTargetConstructorArgDepsAndSelectedVerisons(
            target.orElse(node.getBuildTarget()),
            constructorArg.orElse(node.getConstructorArg()),
            declaredDeps.orElse(node.getDeclaredDeps()),
            extraDeps.orElse(node.getExtraDeps()),
            selectedVersions));
  }

}
