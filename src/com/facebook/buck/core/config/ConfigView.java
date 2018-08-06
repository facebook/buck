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

import org.immutables.value.Value;

/**
 * A view of a particular config class.
 *
 * <p>A config class may implement extra state and accessors beyond the bare Config. ConfigViews
 * provides domain-specific accessors to Config values.
 *
 * <p>ConfigViews should be defined following this pattern.
 *
 * <pre>
 * {@literal @}Value.Immutable(builder=false, copy=false)
 * {@literal @}BuckStyleImmutable
 *  abstract class AbstractFooConfigView implements ConfigView<FooConfig> {
 *    // Additional accessors.
 *  }
 * </pre>
 *
 * Config views should also not declare any additional non-derived immutable fields if it's to be
 * used with {@link ConfigViewCache}. As the cache assumes one-to-one correspondence with the main
 * config instance, it uses the generated factory {@code FooConfigView.of(T delegate)} to
 * instantiate the view.
 *
 * @param <T> Config type.
 */
public interface ConfigView<T> {
  @Value.Parameter
  T getDelegate();
}
