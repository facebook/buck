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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.util.cache.FileHashCache;

import java.util.function.Function;

/**
 * A class which generates {@link RuleKeyFactories} objects.
 */
public class RuleKeyFactoryManager {

  RuleKeyFactories instance;

  public RuleKeyFactoryManager(
      int keySeed,
      FileHashCache fileHashCache,
      BuildRuleResolver resolver,
      long inputRuleKeyFileSizeLimit,
      RuleKeyCache<RuleKey> defaultRuleKeyFactoryCache) {
    RuleKeyFieldLoader fieldLoader = new RuleKeyFieldLoader(keySeed);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    this.instance = RuleKeyFactories.of(
        new DefaultRuleKeyFactory(
            fieldLoader,
            fileHashCache,
            pathResolver,
            ruleFinder,
            defaultRuleKeyFactoryCache),
        new InputBasedRuleKeyFactory(
            fieldLoader,
            fileHashCache,
            pathResolver,
            ruleFinder,
            inputRuleKeyFileSizeLimit),
        new DefaultDependencyFileRuleKeyFactory(
            fieldLoader,
            fileHashCache,
            pathResolver,
            ruleFinder));

  }

  public Function<ProjectFilesystem, RuleKeyFactories> getProvider() {
    return fs -> instance;
  }

}
