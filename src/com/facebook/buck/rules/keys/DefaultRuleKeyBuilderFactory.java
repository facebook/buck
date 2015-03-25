/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.model.BuckVersion;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.FileHashCache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableCollection;

import java.util.concurrent.ExecutionException;


public class DefaultRuleKeyBuilderFactory implements RuleKeyBuilderFactory {
  private final FileHashCache hashCache;
  private LoadingCache<Class<? extends BuildRule>, ImmutableCollection<AlterRuleKey>> knownFields;

  public DefaultRuleKeyBuilderFactory(FileHashCache hashCache) {
    this.hashCache = hashCache;

    knownFields = CacheBuilder.newBuilder().build(new ReflectiveAlterKeyLoader());
  }

  @Override
  public RuleKey.Builder newInstance(BuildRule buildRule, SourcePathResolver resolver) {
    RuleKey.Builder builder = RuleKey.builder(buildRule, resolver, hashCache);
    builder.setReflectively("buckVersionUid", BuckVersion.getVersion());

    try {
      for (AlterRuleKey alterRuleKey : knownFields.get(buildRule.getClass())) {
        alterRuleKey.amendKey(builder, buildRule);
      }
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }

    return builder;
  }

}
