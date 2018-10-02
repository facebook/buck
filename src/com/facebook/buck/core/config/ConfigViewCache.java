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

import com.facebook.buck.util.function.ThrowingFunction;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.lang.reflect.Constructor;
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

  public ConfigViewCache(T delegate, Class<T> clazz) {
    this.cache = CacheBuilder.newBuilder().build(new ConfigViewCacheLoader<>(delegate, clazz));
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
    private final Class<T> clazz;

    private ConfigViewCacheLoader(T delegate, Class<T> clazz) {
      this.delegate = delegate;
      this.clazz = clazz;
    }

    @Override
    public ConfigView<T> load(Class<? extends ConfigView<T>> key) {
      ThrowingFunction<T, ConfigView<T>, Exception> creator;
      try {
        Method builderMethod = key.getMethod("of", this.delegate.getClass());
        creator = config -> key.cast(builderMethod.invoke(null, config));
      } catch (NoSuchMethodException e) {
        try {
          Constructor<? extends ConfigView<T>> constructor = key.getConstructor(clazz);
          creator = constructor::newInstance;
        } catch (NoSuchMethodException e1) {
          throw new IllegalStateException(
              "missing factory method of(Config) or constructor for config view", e);
        }
      }

      try {
        return creator.apply(delegate);
      } catch (InvocationTargetException e) {
        throw new IllegalStateException("ConfigView creator should not throw.", e);
      } catch (IllegalAccessException e) {
        throw new IllegalStateException("ConfigView creator should be public.", e);
      } catch (ClassCastException e) {
        throw new IllegalStateException(
            "ConfigView creator should create correct ConfigView instance.", e);
      } catch (Exception e) {
        throw new IllegalStateException("Error creating ConfigView.", e);
      }
    }
  }
}
