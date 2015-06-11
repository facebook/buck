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

import com.facebook.buck.rules.AppendableRuleKeyCache;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.util.FileHashCache;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

/**
 * A factory for generating input-based {@link RuleKey}s for {@link BuildRule}s which enumerate
 * their dependencies implicitly through their inputs, which are described by {@link SourcePath}s
 * added to their {@link RuleKey}.
 *
 * Input-based rule keys are generally more accurate than normal rule keys, as they won't
 * necessarily change if the rule key of a dependency changed.  Instead, they only change if a
 * the actual inputs to the rule change.
 */
public class InputBasedRuleKeyBuilderFactory extends DefaultRuleKeyBuilderFactory {

  public InputBasedRuleKeyBuilderFactory(
      FileHashCache hashCache,
      SourcePathResolver pathResolver) {
    super(hashCache, pathResolver);
  }

  @Override
  protected RuleKey.Builder newBuilder(
      final BuildRule rule,
      final SourcePathResolver pathResolver,
      FileHashCache hashCache,
      AppendableRuleKeyCache appendableRuleKeyCache) {
    return new RuleKey.Builder(pathResolver, hashCache, appendableRuleKeyCache) {

      // Input-based rule keys are evaluated after all dependencies for a rule are available on
      // disk, and so we can always resolve the `Path` packaged in a `SourcePath`.  We hash this,
      // rather than the rule key from it's `BuildRule`.
      @Override
      protected RuleKey.Builder setSourcePath(SourcePath sourcePath) {

        // If this source path comes from the output of a build rule, verify that the providing
        // build rule is a dependency of the build rule that owns this rule key.
        Optional<BuildRule> provider = pathResolver.getRule(sourcePath);
        Preconditions.checkState(!provider.isPresent() || rule.getDeps().contains(provider.get()));

        // Resolve the `Path` object this `SourcePath` points to and hash that.
        return setSingleValue(pathResolver.getPath(sourcePath));
      }

      // Rules supporting input-based rule keys should be described entirely by their `SourcePath`
      // inputs.  If we see a `BuildRule` when generating the rule key, this is likely a break in
      // that contract, so check for that.
      @Override
      protected RuleKey.Builder setBuildRule(BuildRule rule) {
        throw new IllegalStateException("Input-based rule key builders cannot process build rules");
      }

    };
  }

}
