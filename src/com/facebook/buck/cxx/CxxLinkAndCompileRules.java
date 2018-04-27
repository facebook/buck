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

package com.facebook.buck.cxx;

import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.rules.BuildRule;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import java.util.SortedSet;

public class CxxLinkAndCompileRules {

  public final SortedSet<BuildRule> deps;
  private final CxxLink cxxLink;
  private final Optional<CxxStrip> cxxStrip;
  final ImmutableSortedSet<CxxPreprocessAndCompile> compileRules;
  public final Tool executable;

  CxxLinkAndCompileRules(
      CxxLink cxxLink,
      Optional<CxxStrip> cxxStrip,
      ImmutableSortedSet<CxxPreprocessAndCompile> compileRules,
      Tool executable,
      SortedSet<BuildRule> deps) {
    this.cxxLink = cxxLink;
    this.cxxStrip = cxxStrip;
    this.compileRules = compileRules;
    this.executable = executable;
    this.deps = deps;
  }

  public CxxLink getCxxLink() {
    return cxxLink;
  }

  public BuildRule getBinaryRule() {
    if (cxxStrip.isPresent()) {
      return cxxStrip.get();
    } else {
      return cxxLink;
    }
  }
}
