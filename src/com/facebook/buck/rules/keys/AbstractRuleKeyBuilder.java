/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.NonHashableSourcePathContainer;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.keys.hasher.RuleKeyHasher;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.util.types.Either;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.annotation.Nullable;

public abstract class AbstractRuleKeyBuilder<RULE_KEY> implements RuleKeyObjectSink {
  private static final Logger LOG = Logger.get(AbstractRuleKeyBuilder.class);
  final RuleKeyScopedHasher scopedHasher;

  protected AbstractRuleKeyBuilder(RuleKeyScopedHasher scopedHasher) {
    this.scopedHasher = scopedHasher;
  }

  @Override
  public final AbstractRuleKeyBuilder<RULE_KEY> setReflectively(String key, @Nullable Object val) {
    try (Scope ignored = scopedHasher.keyScope(key)) {
      try {
        return setReflectively(val);
      } catch (IOException e) {
        throw new BuckUncheckedExecutionException(
            e, String.format("When adding %s with value %s", key, val));
      }
    }
  }

  /** Recursively serializes the value. Serialization of the key is handled outside. */
  protected AbstractRuleKeyBuilder<RULE_KEY> setReflectively(@Nullable Object val)
      throws IOException {
    if (val instanceof AddsToRuleKey) {
      return setAddsToRuleKey((AddsToRuleKey) val);
    }

    if (val instanceof BuildRule) {
      return setBuildRule((BuildRule) val);
    }

    if (val instanceof Supplier) {
      try (Scope ignored = scopedHasher.wrapperScope(RuleKeyHasher.Wrapper.SUPPLIER)) {
        Object newVal = ((Supplier<?>) val).get();
        return setReflectively(newVal);
      }
    }

    if (val instanceof Optional) {
      Object o = ((Optional<?>) val).orElse(null);
      try (Scope ignored = scopedHasher.wrapperScope(RuleKeyHasher.Wrapper.OPTIONAL)) {
        return setReflectively(o);
      }
    }

    if (val instanceof Either) {
      Either<?, ?> either = (Either<?, ?>) val;
      if (either.isLeft()) {
        try (Scope ignored = scopedHasher.wrapperScope(RuleKeyHasher.Wrapper.EITHER_LEFT)) {
          return setReflectively(either.getLeft());
        }
      } else {
        try (Scope ignored = scopedHasher.wrapperScope(RuleKeyHasher.Wrapper.EITHER_RIGHT)) {
          return setReflectively(either.getRight());
        }
      }
    }

    // Check to see if we're dealing with a collection of some description.
    // Note {@link java.nio.file.Path} implements "Iterable", so we explicitly exclude it here.
    if (val instanceof Iterable && !(val instanceof Path)) {
      try (RuleKeyScopedHasher.ContainerScope containerScope =
          scopedHasher.containerScope(RuleKeyHasher.Container.LIST)) {
        for (Object element : (Iterable<?>) val) {
          try (Scope ignored = containerScope.elementScope()) {
            setReflectively(element);
          }
        }
        return this;
      }
    }

    if (val instanceof Iterator) {
      Iterator<?> iterator = (Iterator<?>) val;
      try (RuleKeyScopedHasher.ContainerScope containerScope =
          scopedHasher.containerScope(RuleKeyHasher.Container.LIST)) {
        while (iterator.hasNext()) {
          try (Scope ignored = containerScope.elementScope()) {
            setReflectively(iterator.next());
          }
        }
      }
      return this;
    }

    if (val instanceof Map) {
      if (!(val instanceof SortedMap || val instanceof ImmutableMap)) {
        LOG.warn(
            "Adding an unsorted map to the rule key. "
                + "Expect unstable ordering and caches misses: %s",
            val);
      }
      try (RuleKeyScopedHasher.ContainerScope containerScope =
          scopedHasher.containerScope(RuleKeyHasher.Container.MAP)) {
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) val).entrySet()) {
          try (Scope ignored = containerScope.elementScope()) {
            setReflectively(entry.getKey());
          }
          try (Scope ignored = containerScope.elementScope()) {
            setReflectively(entry.getValue());
          }
        }
      }
      return this;
    }

    if (val instanceof Path) {
      throw new HumanReadableException(
          "It's not possible to reliably disambiguate Paths. They are disallowed from rule keys");
    }

    if (val instanceof SourcePath) {
      return setSourcePath((SourcePath) val);
    }

    if (val instanceof NonHashableSourcePathContainer) {
      SourcePath sourcePath = ((NonHashableSourcePathContainer) val).getSourcePath();
      return setNonHashingSourcePath(sourcePath);
    }

    if (val instanceof SourceWithFlags) {
      SourceWithFlags source = (SourceWithFlags) val;
      try (RuleKeyScopedHasher.ContainerScope containerScope =
          scopedHasher.containerScope(RuleKeyHasher.Container.TUPLE)) {
        try (Scope ignored = containerScope.elementScope()) {
          setSourcePath(source.getSourcePath());
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
        try (Scope ignored = containerScope.elementScope()) {
          setReflectively(source.getFlags());
        }
      }
      return this;
    }

    return setSingleValue(val);
  }

  protected abstract AbstractRuleKeyBuilder<RULE_KEY> setSingleValue(@Nullable Object val);

  protected abstract AbstractRuleKeyBuilder<RULE_KEY> setBuildRule(BuildRule rule);

  protected abstract AbstractRuleKeyBuilder<RULE_KEY> setAddsToRuleKey(AddsToRuleKey appendable);

  protected abstract AbstractRuleKeyBuilder<RULE_KEY> setSourcePath(SourcePath sourcePath)
      throws IOException;

  protected abstract AbstractRuleKeyBuilder<RULE_KEY> setNonHashingSourcePath(
      SourcePath sourcePath);

  public abstract RULE_KEY build();

  /** A convenience method that builds the rule key hash and transforms it with a mapper. */
  public final <RESULT> RESULT build(Function<RULE_KEY, RESULT> mapper) {
    return mapper.apply(build());
  }
}
