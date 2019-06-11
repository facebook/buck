/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.rules.providers.impl;

import com.facebook.buck.core.rules.providers.Provider;
import com.facebook.buck.core.rules.providers.ProviderInfo;
import com.facebook.buck.core.rules.providers.annotations.ImmutableInfo;
import com.facebook.buck.core.starlark.compatible.BuckStarlarkStructObject;

/**
 * Represents a {@link ProviderInfo} that is defined in Java. The corresponding {@link Provider} is
 * automatically generated from the this class.
 *
 * <p>The specific provider implementation should use the {@link ImmutableInfo} annotation, and be
 * an abstract class that contains only methods that act as accessors of the struct. The method name
 * will be equivalent to the struct field name. The methods should only return Skylark compatible
 * types.
 *
 * <p>An immutable implementation will be generated for the info.
 *
 * <p>The {@link BuiltInProviderInfo} should have a public static field containing the {@link
 * BuiltInProvider} for the class. That provider can be created with {@code
 * BuiltInProvider.of(ImmutableSomeClass.class}.
 *
 * <p>TODO(bobyf): support map/list/set types better
 *
 * @param <T> the specific type of the {@link BuiltInProviderInfo}
 */
public abstract class BuiltInProviderInfo<T extends BuiltInProviderInfo<T>>
    extends BuckStarlarkStructObject implements ProviderInfo<T> {

  private static final String PROVIDER_FIELD = "PROVIDER";

  private final Class<T> infoClass;
  private final BuiltInProvider<T> provider;

  @SuppressWarnings("unchecked")
  protected BuiltInProviderInfo() {
    this.infoClass = BuiltInProviderClassUtilities.findDeclaringClass(getClass());

    // TODO: We'll want to probably eventually do annotation processors to make these compile time
    // errors.
    try {
      this.provider = (BuiltInProvider<T>) infoClass.getField(PROVIDER_FIELD).get(this);
    } catch (IllegalAccessException | NoSuchFieldException e) {
      throw new IllegalArgumentException(
          String.format(
              "%s should declare a field `public static BuiltInProvider<%s> PROVIDER = BuiltInProvider.of(Immutable%s.class)`",
              infoClass, infoClass.getSimpleName(), infoClass.getSimpleName()));
    }
  }

  @Override
  public BuiltInProvider<T> getProvider() {
    return provider;
  }

  @Override
  protected Class<?> getDeclaredClass() {
    return infoClass;
  }
}
