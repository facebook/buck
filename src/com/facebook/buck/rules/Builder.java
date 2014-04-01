/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;

public class Builder {

  private static final Builder instance = new Builder();

  private Builder() {}

  public static Builder getInstance() {
    return instance;
  }

  public ListenableFuture<List<BuildRuleSuccess>> buildRules(
      final BuildEngine buildEngine,
      Iterable<BuildRule> rules,
      final BuildContext context) {
    return Futures.allAsList(Iterables.transform(
        rules,
        new Function<BuildRule, ListenableFuture<BuildRuleSuccess>>() {
          @Override
          public ListenableFuture<BuildRuleSuccess> apply(BuildRule rule) {
            return buildEngine.build(context, rule);
          }
        }));
  }
}
