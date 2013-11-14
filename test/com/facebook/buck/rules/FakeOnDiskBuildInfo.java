/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.collect.Maps;

import java.util.Map;

import javax.annotation.Nullable;

public class FakeOnDiskBuildInfo extends OnDiskBuildInfo {

  @Nullable private RuleKey ruleKey;
  @Nullable private RuleKey ruleKeyWithoutDeps;
  private Map<String, String> metadata = Maps.newHashMap();

  public FakeOnDiskBuildInfo(BuildTarget target, ProjectFilesystem projectFilesystem) {
    super(target, projectFilesystem);
  }

  /** @return this */
  public FakeOnDiskBuildInfo setRuleKey(@Nullable RuleKey ruleKey) {
    this.ruleKey = ruleKey;
    return this;
  }

  @Override
  public Optional<RuleKey> getRuleKey() {
    return Optional.fromNullable(ruleKey);
  }

  /** @return this */
  public FakeOnDiskBuildInfo setRuleKeyWithoutDeps(@Nullable RuleKey ruleKeyWithoutDeps) {
    this.ruleKeyWithoutDeps = ruleKeyWithoutDeps;
    return this;
  }

  @Override
  public Optional<RuleKey> getRuleKeyWithoutDeps() {
    return Optional.fromNullable(ruleKeyWithoutDeps);
  }

  /** @return this */
  public FakeOnDiskBuildInfo putMetadata(String key, String value) {
    this.metadata.put(key, value);
    return this;
  }

  @Override
  public Optional<String> getValue(String key) {
    return Optional.fromNullable(metadata.get(key));
  }
}
