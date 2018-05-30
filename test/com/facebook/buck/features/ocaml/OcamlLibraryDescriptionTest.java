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

package com.facebook.buck.features.ocaml;

import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.model.targetgraph.TargetGraphFactory;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.google.common.collect.ImmutableSortedSet;
import java.util.regex.Pattern;
import org.hamcrest.Matchers;
import org.junit.Test;

public class OcamlLibraryDescriptionTest {

  @Test
  public void platformDeps() {
    OcamlLibraryBuilder depABuilder =
        new OcamlLibraryBuilder(BuildTargetFactory.newInstance("//:depA"));
    OcamlLibraryBuilder depBBuilder =
        new OcamlLibraryBuilder(BuildTargetFactory.newInstance("//:depB"));
    OcamlLibraryBuilder ruleBuilder =
        new OcamlLibraryBuilder(BuildTargetFactory.newInstance("//:rule"))
            .setPlatformDeps(
                PatternMatchedCollection.<ImmutableSortedSet<BuildTarget>>builder()
                    .add(
                        Pattern.compile(
                            OcamlTestUtils.DEFAULT_PLATFORM.getFlavor().toString(),
                            Pattern.LITERAL),
                        ImmutableSortedSet.of(depABuilder.getTarget()))
                    .add(
                        Pattern.compile("matches nothing", Pattern.LITERAL),
                        ImmutableSortedSet.of(depBBuilder.getTarget()))
                    .build());
    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            depABuilder.build(), depBBuilder.build(), ruleBuilder.build());
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder(targetGraph);
    OcamlLibrary depA = (OcamlLibrary) graphBuilder.requireRule(depABuilder.getTarget());
    OcamlLibrary depB = (OcamlLibrary) graphBuilder.requireRule(depBBuilder.getTarget());
    OcamlLibrary rule = (OcamlLibrary) graphBuilder.requireRule(ruleBuilder.getTarget());
    assertThat(
        rule.getOcamlLibraryDeps(OcamlTestUtils.DEFAULT_PLATFORM),
        Matchers.allOf(Matchers.hasItem(depA), not(Matchers.hasItem(depB))));
  }
}
