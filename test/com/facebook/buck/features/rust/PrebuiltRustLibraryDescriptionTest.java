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

package com.facebook.buck.features.rust;

import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.google.common.collect.ImmutableSortedSet;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Test;

public class PrebuiltRustLibraryDescriptionTest {

  @Test
  public void platformDeps() {
    PrebuiltRustLibraryBuilder depABuilder =
        PrebuiltRustLibraryBuilder.from("//:depA").setRlib(FakeSourcePath.of("a.rlib"));
    PrebuiltRustLibraryBuilder depBBuilder =
        PrebuiltRustLibraryBuilder.from("//:depB").setRlib(FakeSourcePath.of("b.rlib"));
    PrebuiltRustLibraryBuilder ruleBuilder =
        PrebuiltRustLibraryBuilder.from("//:rule")
            .setRlib(FakeSourcePath.of("c.rlib"))
            .setPlatformDeps(
                PatternMatchedCollection.<ImmutableSortedSet<BuildTarget>>builder()
                    .add(
                        Pattern.compile(
                            RustTestUtils.DEFAULT_PLATFORM.getFlavor().toString(), Pattern.LITERAL),
                        ImmutableSortedSet.of(depABuilder.getTarget()))
                    .add(
                        Pattern.compile("matches nothing", Pattern.LITERAL),
                        ImmutableSortedSet.of(depBBuilder.getTarget()))
                    .build());
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            depABuilder.build(), depBBuilder.build(), ruleBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    PrebuiltRustLibrary depA =
        (PrebuiltRustLibrary) graphBuilder.requireRule(depABuilder.getTarget());
    PrebuiltRustLibrary depB =
        (PrebuiltRustLibrary) graphBuilder.requireRule(depBBuilder.getTarget());
    PrebuiltRustLibrary rule =
        (PrebuiltRustLibrary) graphBuilder.requireRule(ruleBuilder.getTarget());
    assertThat(
        rule.getRustLinakbleDeps(RustTestUtils.DEFAULT_PLATFORM),
        Matchers.allOf(Matchers.hasItem(depA), not(Matchers.hasItem(depB))));
  }
}
