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

package com.facebook.buck.core.build.engine.cache.manager;

import com.facebook.buck.core.build.engine.buildinfo.BuildInfo;
import com.facebook.buck.core.build.engine.buildinfo.BuildInfoRecorder;
import com.facebook.buck.core.build.engine.buildinfo.OnDiskBuildInfo;
import com.facebook.buck.core.build.engine.type.DepFiles;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.attr.SupportsDependencyFileRuleKey;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.RuleKeyCalculationEvent;
import com.facebook.buck.rules.keys.DependencyFileEntry;
import com.facebook.buck.rules.keys.RuleKeyAndInputs;
import com.facebook.buck.rules.keys.RuleKeyFactories;
import com.facebook.buck.rules.keys.SizeLimiter;
import com.facebook.buck.util.Discardable;
import com.facebook.buck.util.Scope;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.Optional;

public class DependencyFileRuleKeyManager {
  private final DepFiles depFiles;
  private final BuildRule rule;
  private final Discardable<BuildInfoRecorder> buildInfoRecorder;
  private final OnDiskBuildInfo onDiskBuildInfo;
  private final RuleKeyFactories ruleKeyFactories;
  private final BuckEventBus eventBus;

  public DependencyFileRuleKeyManager(
      DepFiles depFiles,
      BuildRule rule,
      Discardable<BuildInfoRecorder> buildInfoRecorder,
      OnDiskBuildInfo onDiskBuildInfo,
      RuleKeyFactories ruleKeyFactories,
      BuckEventBus eventBus) {
    this.depFiles = depFiles;
    this.rule = rule;
    this.buildInfoRecorder = buildInfoRecorder;
    this.onDiskBuildInfo = onDiskBuildInfo;
    this.ruleKeyFactories = ruleKeyFactories;
    this.eventBus = eventBus;
  }

  public boolean useDependencyFileRuleKey() {
    return depFiles != DepFiles.DISABLED
        && rule instanceof SupportsDependencyFileRuleKey
        && ((SupportsDependencyFileRuleKey) rule).useDependencyFileRuleKeys();
  }

  private BuildInfoRecorder getBuildInfoRecorder() {
    return buildInfoRecorder.get();
  }

  public boolean checkMatchingDepfile() throws IOException {
    // Try to get the current dep-file rule key.
    Optional<RuleKeyAndInputs> depFileRuleKeyAndInputs =
        calculateDepFileRuleKey(
            onDiskBuildInfo.getValues(BuildInfo.MetadataKey.DEP_FILE),
            /* allowMissingInputs */ true);
    if (!depFileRuleKeyAndInputs.isPresent()) {
      return false;
    }
    RuleKey depFileRuleKey = depFileRuleKeyAndInputs.get().getRuleKey();
    getBuildInfoRecorder()
        .addBuildMetadata(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY, depFileRuleKey.toString());

    // Check the input-based rule key says we're already built.
    Optional<RuleKey> lastDepFileRuleKey =
        onDiskBuildInfo.getRuleKey(BuildInfo.MetadataKey.DEP_FILE_RULE_KEY);
    return lastDepFileRuleKey.isPresent() && depFileRuleKey.equals(lastDepFileRuleKey.get());
  }

  public Optional<RuleKeyAndInputs> calculateDepFileRuleKey(
      Optional<ImmutableList<String>> depFile, boolean allowMissingInputs) throws IOException {

    Preconditions.checkState(useDependencyFileRuleKey());

    // Extract the dep file from the last build.  If we don't find one, abort.
    if (!depFile.isPresent()) {
      return Optional.empty();
    }

    // Build the dep-file rule key.  If any inputs are no longer on disk, this means something
    // changed and a dep-file based rule key can't be calculated.
    ImmutableList<DependencyFileEntry> inputs =
        depFile.get().stream()
            .map(ObjectMappers.fromJsonFunction(DependencyFileEntry.class))
            .collect(ImmutableList.toImmutableList());

    try (Scope ignored =
        RuleKeyCalculationEvent.scope(
            eventBus, RuleKeyCalculationEvent.Type.DEP_FILE, rule.getBuildTarget())) {
      return Optional.of(
          ruleKeyFactories
              .getDepFileRuleKeyFactory()
              .build(((SupportsDependencyFileRuleKey) rule), inputs));
    } catch (SizeLimiter.SizeLimitException ex) {
      return Optional.empty();
    } catch (Exception e) {
      // TODO(plamenko): fix exception propagation in RuleKeyBuilder
      if (allowMissingInputs && Throwables.getRootCause(e) instanceof NoSuchFileException) {
        return Optional.empty();
      }
      throw e;
    }
  }
}
