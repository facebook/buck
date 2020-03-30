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

package com.facebook.buck.core.rules.providers.impl;

import com.facebook.buck.core.rules.providers.Provider;
import com.facebook.buck.core.rules.providers.ProviderInfo;
import com.facebook.buck.util.MoreIterables;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.skylarkinterface.SkylarkValue;
import com.google.devtools.build.lib.syntax.ClassObject;
import com.google.devtools.build.lib.syntax.EvalUtils;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * {@link ProviderInfo} that is created by a {@link Provider} that is defined at runtime by a user.
 * e.g. {@code FooInfo = provider(fields=["foo"]); info = FooInfo(foo="bar")} in a build file
 */
public class UserDefinedProviderInfo
    implements ProviderInfo<UserDefinedProviderInfo>, SkylarkValue, ClassObject {

  private final Provider<UserDefinedProviderInfo> provider;
  private final ImmutableMap<String, Object> fieldValues;
  private boolean immutable = false;

  /**
   * Create an instance of {@link UserDefinedProviderInfo}
   *
   * @param provider The provider that created this {@link UserDefinedProviderInfo} object
   * @param fieldValues A mapping of field names to their values. Note that the names must be valid
   *     skylark identifiers as they are accessed via {@code info_instance.<field>}. The values must
   *     be valid when used in the Skylark interpreter. That is, either primitives, or instances of
   *     {@link SkylarkValue}
   */
  public UserDefinedProviderInfo(
      Provider<UserDefinedProviderInfo> provider, ImmutableMap<String, Object> fieldValues) {
    this.provider = provider;
    this.fieldValues = fieldValues;
  }

  @Override
  public Provider<UserDefinedProviderInfo> getProvider() {
    return provider;
  }

  @Override
  public ProviderInfo<?> getProviderInfo() {
    return this;
  }

  @Override
  public void repr(SkylarkPrinter printer) {
    printer.append(provider.toString());
    printer.append("(");
    MoreIterables.enumerate(fieldValues.entrySet())
        .forEach(
            pair -> {
              if (pair.getFirst() != 0) {
                printer.append(", ");
              }
              printer.format("%s = ", pair.getSecond().getKey());
              printer.repr(pair.getSecond().getValue());
            });
    printer.append(")");
  }

  @Nullable
  @Override
  public Object getValue(String name) {
    return fieldValues.get(name);
  }

  @Override
  public ImmutableCollection<String> getFieldNames() {
    return fieldValues.keySet();
  }

  @Nullable
  @Override
  public String getErrorMessageForUnknownField(String field) {
    return String.format("%s has no field named %s", provider.toString(), field);
  }

  @Override
  public boolean isImmutable() {
    // Once something's gone immutable, it cannot be made mutable again.
    if (immutable) {
      return true;
    }
    immutable =
        fieldValues.values().stream()
            .allMatch(o -> EvalUtils.isImmutable(Objects.requireNonNull(o)));
    return immutable;
  }
}
