/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.jvm.core.HasJavaAbi;
import com.facebook.buck.rules.keys.ArchiveDependencySupplier;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedSet;
import java.util.stream.Stream;

public class ZipArchiveDependencySupplier implements ArchiveDependencySupplier {
  @AddToRuleKey private final ImmutableSortedSet<SourcePath> zipFiles;

  public ZipArchiveDependencySupplier(ImmutableSortedSet<SourcePath> zipFiles) {
    this.zipFiles = zipFiles;
  }

  @Override
  public ImmutableSortedSet<SourcePath> get() {
    return zipFiles;
  }

  @Override
  public Stream<SourcePath> getArchiveMembers(
      SourcePathResolver resolver, SourcePathRuleFinder ruleFinder) {
    return zipFiles
        .stream()
        .flatMap(
            zipSourcePath -> {
              BuildRule rule = ruleFinder.getRule((BuildTargetSourcePath) zipSourcePath);
              HasJavaAbi hasJavaAbi = (HasJavaAbi) rule;
              Preconditions.checkState(rule.getSourcePathToOutput().equals(zipSourcePath));
              return hasJavaAbi.getAbiInfo().getJarContents().stream();
            });
  }
}
