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

package com.facebook.buck.core.artifact;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.starlark.rule.artifact.SkylarkOutputArtifactApi;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;

/**
 * A wrapper around {@link ArtifactImpl} that indicates that it should be used as an output to an
 * action.
 *
 * <p>This is generally used in {@link
 * com.facebook.buck.core.rules.actions.lib.args.CommandLineArgsFactory#from(ImmutableList)} and
 * things that consume it like {@link com.facebook.buck.core.rules.actions.lib.RunAction}. In
 * RunAction's case, we would like to be able to infer inputs and outputs, so users do not have to
 * specify information they have already provided (what artifacts are used in the action in some
 * fashion)
 */
@BuckStyleValue
public abstract class OutputArtifact
    implements SkylarkOutputArtifactApi, Comparable<OutputArtifact>, AddsToRuleKey {
  abstract ArtifactImpl getImpl();

  @Override
  public boolean isImmutable() {
    return true;
  }

  public Artifact getArtifact() {
    return getImpl();
  }

  @AddToRuleKey
  private String pathForRuleKey() {
    return getImpl().getOutputPath().toString();
  }

  @Override
  public void repr(SkylarkPrinter printer) {
    ArtifactImpl.repr(printer, getArtifact(), true);
  }

  @Override
  public String toString() {
    return ArtifactImpl.toString(getImpl(), true);
  }

  @Override
  public int compareTo(OutputArtifact o) {
    return getImpl().compareTo(o.getImpl());
  }
}
