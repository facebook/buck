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

package com.facebook.buck.core.rules.provider;

import org.immutables.value.Value;

/**
 * This provider is used to pass a data regarding {@link com.facebook.buck.rules.BuildRule} up to
 * other dependant BuildRules during action graph construction without exposing {@link
 * com.facebook.buck.rules.BuildRule} and {@link com.facebook.buck.model.BuildTarget}. This is the
 * only interface for which {@link com.facebook.buck.rules.BuildRule} can pass information to its
 * dependants as they do not have direct access to its dependents.
 *
 * <p>The implementations of this interface need to be:
 *
 * <ul>
 *   <li>serializable
 *   <li>immutable
 * </ul>
 *
 * <p>Equals should be implemented such that if all {@link BuildRuleInfoProvider} on a {@link
 * com.facebook.buck.rules.BuildRule} is equal, we consider the {@link
 * com.facebook.buck.rules.BuildRule} to be equal.
 */
public interface BuildRuleInfoProvider {

  /**
   * Key for identifying a provider. Each BuildRuleInfoProvider type should have one distinct {@code
   * ProviderKey}, and all keys for the same provider type should be equal.
   *
   * <p>Implementations of {@code BuildRuleInfoProvider} should declare their {@code ProviderKey}
   * based on the following example:
   *
   * <pre>{@code
   * class SomeProvider implements BuildRuleInfoProvider {
   *   public static final ProviderKey = ProviderKey.of(SomeProvider.class);
   *
   *   ...
   * }
   * }</pre>
   */
  @Value.Immutable(builder = false, copy = false, prehash = true)
  public abstract static class ProviderKey {

    @Value.Parameter
    @SuppressWarnings("unused") // used to generate equals properly
    protected abstract Class<?> getProviderClass();

    @Override
    public String toString() {
      return getProviderClass().getCanonicalName() + "Key";
    }
  }

  default ProviderKey getKey() {
    return ImmutableProviderKey.of(getClass());
  }
}
