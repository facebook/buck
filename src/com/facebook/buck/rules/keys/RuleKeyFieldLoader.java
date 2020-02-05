/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.rules.keys;

import com.facebook.buck.core.build.action.BuildEngineAction;
import com.facebook.buck.core.module.BuckModuleHashStrategy;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.actions.Action;
import com.facebook.buck.io.filesystem.BuckPaths;
import com.facebook.buck.rules.keys.config.RuleKeyConfiguration;
import com.google.common.base.Preconditions;
import java.nio.file.Path;

public class RuleKeyFieldLoader {

  private final RuleKeyConfiguration ruleKeyConfiguration;

  public RuleKeyFieldLoader(RuleKeyConfiguration ruleKeyConfiguration) {
    this.ruleKeyConfiguration = ruleKeyConfiguration;
  }

  private void setFields(AbstractRuleKeyBuilder<?> builder, BuildRule buildRule) {
    // "." is not a valid first character for a field name, nor a valid character for rule attribute
    // name and so the following fields will never collide with other stuff.
    builder.setReflectively(".build_rule_type", buildRule.getType());

    BuckModuleHashStrategy hashStrategy = ruleKeyConfiguration.getBuckModuleHashStrategy();
    Class<?> buildRuleClass = buildRule.getClass();
    if (hashStrategy.needToAddModuleHashToRuleKey(buildRuleClass)) {
      builder.setReflectively(".buck_module_hash", hashStrategy.getModuleHash(buildRuleClass));
    }

    // We currently cache items using their full buck-out path, so make sure this is reflected in
    // the rule key.
    BuckPaths buckPaths = buildRule.getProjectFilesystem().getBuckPaths();
    Path buckOutPath = buckPaths.getConfiguredBuckOut();
    builder.setReflectively(".out", buckOutPath.toString());
    builder.setReflectively(".hashed_buck_out_paths", buckPaths.shouldIncludeTargetConfigHash());

    AlterRuleKeys.amendKey(builder, buildRule);
  }

  private void setFields(AbstractRuleKeyBuilder<?> builder, Action action) {
    builder.setReflectively(".id", action.getID());
    AlterRuleKeys.amendKey(builder, action);
  }

  void setFields(
      AbstractRuleKeyBuilder<?> builder, BuildEngineAction action, RuleKeyType ruleKeyType) {
    // "." is not a valid first character for a field name, nor a valid character for rule attribute
    // name and so the following fields will never collide with other stuff.
    builder.setReflectively(".cache_key_seed", ruleKeyConfiguration.getSeed());
    builder.setReflectively(".target_name", action.getBuildTarget().getFullyQualifiedName());
    builder.setReflectively(
        ".target_conf", action.getBuildTarget().getTargetConfiguration().toString());
    builder.setReflectively(".buck_core_key", ruleKeyConfiguration.getCoreKey());
    builder.setReflectively(".rule_key_type", ruleKeyType);

    builder.setReflectively(
        ".input_rule_key_file_size_limit",
        ruleKeyConfiguration.getBuildInputRuleKeyFileSizeLimit());

    // We used to require build rules to piggyback on the `RuleKeyAppendable` type to add in
    // additional details, but have since switched to using a method in the build rule class, so
    // error out if we see the `RuleKeyAppendable` being used improperly.
    Preconditions.checkArgument(!(builder instanceof RuleKeyAppendable));

    if (action instanceof BuildRule) {
      setFields(builder, (BuildRule) action);
    } else if (action instanceof Action) {
      setFields(builder, (Action) action);
    } else {
      throw new IllegalStateException();
    }
  }
}
