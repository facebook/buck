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
package com.facebook.buck.core.config;

import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * A cache for views of some Config.
 *
 * <p>This class is useful if the ConfigViews memoize certain values that may be expensive to
 * compute.
 *
 * @param <T> Config type
 */
public final class ConfigViewCache<T> {

  private final LoadingCache<Class<? extends ConfigView<T>>, ? extends ConfigView<T>> cache;

  public ConfigViewCache(T delegate) {
    this.cache = CacheBuilder.newBuilder().build(new ConfigViewCacheLoader<>(delegate));
  }

  public <V extends ConfigView<T>> V getView(Class<V> viewClass) {
    try {
      return viewClass.cast(cache.getUnchecked(viewClass));
    } catch (UncheckedExecutionException e) {
      Throwables.throwIfUnchecked(e.getCause());
      throw e;
    }
  }

  private static class ConfigViewCacheLoader<T>
      extends CacheLoader<Class<? extends ConfigView<T>>, ConfigView<T>> {
    private final T delegate;

    private ConfigViewCacheLoader(T delegate) {
      this.delegate = delegate;
    }

    @Override
    public ConfigView<T> load(Class<? extends ConfigView<T>> key) {
      Method builderMethod;
      try {
        builderMethod = key.getMethod("of", this.delegate.getClass());
      } catch (NoSuchMethodException e) {
        throw new IllegalStateException("missing factory method of(Config) for config view", e);
      }

      try {
        return key.cast(builderMethod.invoke(null, this.delegate));
      } catch (InvocationTargetException e) {
        throw new IllegalStateException("of() should not throw.", e);
      } catch (IllegalAccessException e) {
        throw new IllegalStateException("of() should be public.", e);
      } catch (ClassCastException e) {
        throw new IllegalStateException(
            "factory method should create correct ConfigView instance.", e);
      }
    }
  }
}
