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

import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rulekey.RuleKeyAppendable;
import com.facebook.buck.core.rulekey.RuleKeyObjectSink;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.log.Logger;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableCollection;
import java.util.concurrent.ConcurrentHashMap;

public final class AlterRuleKeys {
  private static final Logger LOG = Logger.get(AlterRuleKeys.class);
  private static final LoadingCache<Class<?>, ImmutableCollection<AlterRuleKey>> cache =
      CacheBuilder.newBuilder().build(new ReflectiveAlterKeyLoader());
  private static final ConcurrentHashMap<Class<?>, String> pseudoNameCache =
      new ConcurrentHashMap<>();

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
    Class<?> clazz = appendable.getClass();
    String className = clazz.getName();
    if (clazz.isAnonymousClass() || clazz.isSynthetic()) {
      className = pseudoNameCache.computeIfAbsent(clazz, AlterRuleKeys::getPseudoClassName);
    }
    sink.setReflectively(".class", className);
    for (AlterRuleKey alterRuleKey : cache.getUnchecked(clazz)) {
      alterRuleKey.amendKey(sink, appendable);
    }
  }

  private static String getPseudoClassName(Class<?> clazz) {
    Class<?> declaring = clazz;
    while ((declaring.isAnonymousClass() || declaring.isSynthetic())
        && declaring.getEnclosingClass() != null) {
      declaring = declaring.getEnclosingClass();
    }
    String prefix = declaring.getName();
    if (declaring.isAnonymousClass() || declaring.isSynthetic()) {
      prefix = prefix.replaceAll("\\$.*", "");
    }
    String pseudoName = prefix + "$?????";
    LOG.warn(
        "Trying to add anonymous class %s to rulekeys. Using %s instead",
        clazz.getName(), pseudoName);
    return pseudoName;
  }
}
