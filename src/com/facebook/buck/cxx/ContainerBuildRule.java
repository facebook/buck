/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSortedSet;

/**
 * A dummy {@link BuildRule} that stores items in the action graph.  This is mainly useful as a
 * "summary" of several build rules, which are generated together, and allows checking if they
 * have all been created via a single build rule lookup in the action graph.  This is useful for
 * how the C/C++ support lazily generates actions for the build rule.
 */
public class ContainerBuildRule<T> extends NoopBuildRule {

  private static final BuildRuleType TYPE = BuildRuleType.of("container");

  private final T item;

  private ContainerBuildRule(BuildRuleParams params, SourcePathResolver resolver, T item) {
    super(params, resolver);
    this.item = item;
  }

  public static <T> ContainerBuildRule<T> of(
      BuildRuleParams params,
      SourcePathResolver resolver,
      BuildTarget target,
      T item) {
    return new ContainerBuildRule<>(
        params.copyWithChanges(
            TYPE,
            target,
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
            Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
        resolver,
        item);
  }

  public T get() {
    return item;
  }

}
