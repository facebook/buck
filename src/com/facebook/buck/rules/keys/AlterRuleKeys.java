/*
 * Copyright 2017-present Facebook, Inc.
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

import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableCollection;

public final class AlterRuleKeys {
  private static final LoadingCache<Class<?>, ImmutableCollection<AlterRuleKey>> cache =
      CacheBuilder.newBuilder().build(new ReflectiveAlterKeyLoader());

  public static void amendKey(RuleKeyObjectSink sink, BuildRule rule) {
    amendKey(sink, (Object) rule);
  }

  public static void amendKey(RuleKeyObjectSink sink, AddsToRuleKey appendable) {
    if (appendable instanceof RuleKeyAppendable) {
      ((RuleKeyAppendable) appendable).appendToRuleKey(sink);
    }

    // Honor @AddToRuleKey on RuleKeyAppendable's in addition to their custom code. Having this be a
    // static method invoking things from outside (as opposed to a default method) ensures that the
    // long-standing habit of not needing to chain to super in RuleKeyAppendable doesn't cause
    // subtle breakages.
    amendKey(sink, (Object) appendable);
  }

  private static void amendKey(RuleKeyObjectSink sink, Object appendable) {
    for (AlterRuleKey alterRuleKey : cache.getUnchecked(appendable.getClass())) {
      alterRuleKey.amendKey(sink, appendable);
    }
  }
}
