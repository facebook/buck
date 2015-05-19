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

package com.facebook.buck.android;

import static org.hamcrest.junit.MatcherAssert.assertThat;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeExportDependenciesRule;
import com.facebook.buck.rules.SourcePathResolver;

import org.hamcrest.Matchers;
import org.junit.Test;

public class AndroidLibraryDescriptionTest {

  @Test
  public void rulesExportedFromDepsBecomeFirstOrderDeps() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    FakeBuildRule transitiveExportedRule =
        resolver.addToIndex(new FakeBuildRule("//:transitive_exported_rule", pathResolver));
    FakeExportDependenciesRule exportedRule =
        resolver.addToIndex(
            new FakeExportDependenciesRule(
                "//:exported_rule",
                pathResolver,
                transitiveExportedRule));
    FakeExportDependenciesRule exportingRule =
        resolver.addToIndex(
            new FakeExportDependenciesRule("//:exporting_rule", pathResolver, exportedRule));

    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRule javaLibrary = AndroidLibraryBuilder.createBuilder(target)
        .addDep(exportingRule.getBuildTarget())
        .build(resolver);

    assertThat(
        javaLibrary.getDeps(),
        Matchers.allOf(
            Matchers.hasItem(exportedRule),
            Matchers.hasItem(transitiveExportedRule)));
  }

  @Test
  public void rulesExportedFromProvidedDepsBecomeFirstOrderDeps() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);

    FakeBuildRule transitiveExportedRule =
        resolver.addToIndex(new FakeBuildRule("//:transitive_exported_rule", pathResolver));
    FakeExportDependenciesRule exportedRule =
        resolver.addToIndex(
            new FakeExportDependenciesRule(
                "//:exported_rule",
                pathResolver,
                transitiveExportedRule));
    FakeExportDependenciesRule exportingRule =
        resolver.addToIndex(
            new FakeExportDependenciesRule("//:exporting_rule", pathResolver, exportedRule));

    BuildTarget target = BuildTargetFactory.newInstance("//:rule");
    BuildRule javaLibrary = AndroidLibraryBuilder.createBuilder(target)
        .addProvidedDep(exportingRule.getBuildTarget())
        .build(resolver);

    assertThat(
        javaLibrary.getDeps(),
        Matchers.allOf(
            Matchers.hasItem(exportedRule),
            Matchers.hasItem(transitiveExportedRule)));
  }

}
