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

package com.facebook.buck.core.rules.providers.lib;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.rules.providers.ProviderInfo;
import com.facebook.buck.core.rules.providers.annotations.ImmutableInfo;
import com.facebook.buck.core.rules.providers.impl.BuiltInProvider;
import com.facebook.buck.core.rules.providers.impl.BuiltInProviderInfo;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import java.util.Set;

/**
 * The standard default information that all rules should be propagating via {@link ProviderInfo}.
 */
@ImmutableInfo(
    args = {"named_outputs", "default_outputs"},
    defaultSkylarkValues = {"{}", "[]"})
public abstract class DefaultInfo extends BuiltInProviderInfo<DefaultInfo> {

  public static final BuiltInProvider<DefaultInfo> PROVIDER =
      BuiltInProvider.of(ImmutableDefaultInfo.class);

  /**
   * These are the named outputs of a rule. Named outputs are a mapping of String identifiers to a
   * set of outputs of the rule that the name maps to.
   *
   * @return a map of a String, which is the named identifier to a set of outputs.
   */
  // TODO(bobyf): replace with our own map types?
  public abstract SkylarkDict<String, Set<Artifact>> namedOutputs();

  /** @return the set of default outputs built by the rule if no output selection is specified. */
  // TODO: replace with determinstic ordered set
  public abstract Set<Artifact> defaultOutputs();

  // TODO: add run files and such
}
