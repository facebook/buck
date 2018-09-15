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

package com.facebook.buck.apple;

import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableSet;
import org.immutables.value.Value;

/**
 * Metadata query for collecting dependencies to include in a bundle.
 *
 * <p>Replacement for {@link com.facebook.buck.cxx.AbstractFrameworkDependencies} but unfortunately
 * for that metadata type framework embedding and generation are tightly coupled. The framework
 * flavor related code is scheduled to be removed in the future, so this should function as a more
 * general metadata class that is unrelated and could support more destinations than just the
 * framework folder.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractAppleBundleIncludableDependenies {

  /** will be copied into the */
  @Value.Parameter
  abstract ImmutableSet<SourcePath> getFrameworkPaths();
}
