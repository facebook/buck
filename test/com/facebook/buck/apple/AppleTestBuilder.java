/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.apple;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosUtils;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;

public final class AppleTestBuilder
    extends AbstractNodeBuilder<
        AppleTestDescriptionArg.Builder, AppleTestDescriptionArg, AppleTestDescription, AppleTest> {

  protected AppleTestBuilder(BuildTarget target) {
    super(createDescription(), target);
  }

  public static AppleTestBuilder createBuilder(BuildTarget target) {
    return new AppleTestBuilder(target);
  }

  public AppleTestBuilder setContacts(ImmutableSortedSet<String> contacts) {
    getArgForPopulating().setContacts(contacts);
    return this;
  }

  public AppleTestBuilder setLabels(ImmutableSortedSet<String> labels) {
    getArgForPopulating().setLabels(labels);
    return this;
  }

  public AppleTestBuilder setInfoPlist(SourcePath infoPlist) {
    getArgForPopulating().setInfoPlist(infoPlist);
    return this;
  }

  public AppleTestBuilder isUiTest(boolean value) {
    getArgForPopulating().setIsUiTest(value);
    return this;
  }

  public AppleTestBuilder setTestHostApp(Optional<BuildTarget> testHostApp) {
    getArgForPopulating().setTestHostApp(testHostApp);
    return this;
  }

  public AppleTestBuilder setUiTestTargetApp(Optional<BuildTarget> uiTestTargetApp) {
    getArgForPopulating().setUiTestTargetApp(uiTestTargetApp);
    return this;
  }

  private static AppleTestDescription createDescription() {
    return FakeAppleRuleDescriptions.TEST_DESCRIPTION;
  }

  public AppleTestBuilder setConfigs(
      ImmutableSortedMap<String, ImmutableMap<String, String>> configs) {
    getArgForPopulating().setConfigs(configs);
    return this;
  }

  public AppleTestBuilder setCompilerFlags(ImmutableList<String> compilerFlags) {
    getArgForPopulating().setCompilerFlags(StringWithMacrosUtils.fromStrings(compilerFlags));
    return this;
  }

  public AppleTestBuilder setLinkerFlags(ImmutableList<StringWithMacros> linkerFlags) {
    getArgForPopulating().setLinkerFlags(linkerFlags);
    return this;
  }

  public AppleTestBuilder setExportedLinkerFlags(
      ImmutableList<StringWithMacros> exportedLinkerFlags) {
    getArgForPopulating().setExportedLinkerFlags(exportedLinkerFlags);
    return this;
  }

  public AppleTestBuilder setSrcs(ImmutableSortedSet<SourceWithFlags> srcs) {
    getArgForPopulating().setSrcs(srcs);
    return this;
  }

  public AppleTestBuilder setHeaders(SourceList headers) {
    getArgForPopulating().setHeaders(headers);
    return this;
  }

  public AppleTestBuilder setHeaders(ImmutableSortedSet<SourcePath> headers) {
    return setHeaders(SourceList.ofUnnamedSources(headers));
  }

  public AppleTestBuilder setHeaders(ImmutableSortedMap<String, SourcePath> headers) {
    return setHeaders(SourceList.ofNamedSources(headers));
  }

  public AppleTestBuilder setFrameworks(ImmutableSortedSet<FrameworkPath> frameworks) {
    getArgForPopulating().setFrameworks(frameworks);
    return this;
  }

  public AppleTestBuilder setLibraries(ImmutableSortedSet<FrameworkPath> libraries) {
    getArgForPopulating().setLibraries(libraries);
    return this;
  }

  public AppleTestBuilder setDeps(ImmutableSortedSet<BuildTarget> deps) {
    getArgForPopulating().setDeps(deps);
    return this;
  }

  public AppleTestBuilder setExportedDeps(ImmutableSortedSet<BuildTarget> exportedDeps) {
    getArgForPopulating().setExportedDeps(exportedDeps);
    return this;
  }

  public AppleTestBuilder setTests(ImmutableSortedSet<BuildTarget> tests) {
    getArgForPopulating().setTests(tests);
    return this;
  }
}
