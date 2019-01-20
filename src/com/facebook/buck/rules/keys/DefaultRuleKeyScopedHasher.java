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

import com.facebook.buck.rules.keys.hasher.CountingRuleKeyHasher;
import com.facebook.buck.rules.keys.hasher.RuleKeyHasher;
import com.facebook.buck.util.Scope;

/**
 * A wrapper of {@link RuleKeyHasher} that provides scoped hashing facilities.
 *
 * <p>Important: Container, wrapper and key signatures only get hashed if their scope was non-empty.
 * I.e. if at least one thing gets hashed under their scope. This is to support rule key builders
 * that ignore some fields.
 */
public class DefaultRuleKeyScopedHasher<HASH> implements RuleKeyScopedHasher {
  private final CountingRuleKeyHasher<HASH> hasher;

  DefaultRuleKeyScopedHasher(CountingRuleKeyHasher<HASH> hasher) {
    this.hasher = hasher;
  }

  public CountingRuleKeyHasher<HASH> getHasher() {
    return hasher;
  }

  /** Hashes the key iff non-empty (i.e. if anything gets hashed during its scope). */
  @Override
  public Scope keyScope(String key) {
    long hasherCount = hasher.getCount();
    return () -> {
      if (hasher.getCount() > hasherCount) {
        hasher.putKey(key);
      }
    };
  }

  /** Hashes the wrapper iff non-empty (i.e. if any element gets hashed during its scope). */
  @Override
  public Scope wrapperScope(RuleKeyHasher.Wrapper wrapper) {
    long hasherCount = hasher.getCount();
    return () -> {
      if (hasher.getCount() > hasherCount) {
        hasher.putWrapper(wrapper);
      }
    };
  }

  /**
   * Hashes the container iff non-empty (i.e. if any element gets hashed during its scope).
   *
   * <p>Note that an element scope needs to be created for each element!
   */
  @Override
  public ContainerScope containerScope(RuleKeyHasher.Container container) {
    return new DefaultContainerScope(hasher, container);
  }

  public static class DefaultContainerScope implements ContainerScope {
    private final CountingRuleKeyHasher<?> hasher;
    private final RuleKeyHasher.Container container;
    private int elementCount = 0;

    private DefaultContainerScope(
        CountingRuleKeyHasher<?> hasher, RuleKeyHasher.Container container) {
      this.hasher = hasher;
      this.container = container;
    }

    /** Increases element count if anything gets hashed during the element scope. */
    @Override
    public Scope elementScope() {
      long hasherCount = hasher.getCount();
      return () -> {
        if (hasher.getCount() > hasherCount) {
          elementCount++;
        }
      };
    }

    /** Hashes the container iff non-empty (i.e. if any element gets hashed during this scope). */
    @Override
    public void close() {
      if (elementCount > 0) {
        hasher.putContainer(container, elementCount);
      }
    }
  }
}
