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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import javax.annotation.Nullable;

public class JarBackedJavacProvider implements JavacProvider, AddsToRuleKey {
  @AddToRuleKey private final SourcePath javacJarPath;
  @AddToRuleKey private final String compilerClassName;

  // This is just used to cache the Javac derived from the other fields.
  @Nullable private Javac javac;

  public JarBackedJavacProvider(SourcePath javacJarPath, String compilerClassName) {
    this.javacJarPath = javacJarPath;
    this.compilerClassName = compilerClassName;
  }

  @Override
  public Javac resolve(SourcePathRuleFinder ruleFinder) {
    if (javac == null) {
      ImmutableSortedSet.Builder<SourcePath> builder = ImmutableSortedSet.naturalOrder();
      builder.add(javacJarPath);

      // Add transitive deps if any exist so that everything needed is available
      Optional<BuildRule> possibleRule = ruleFinder.getRule(javacJarPath);
      if (possibleRule.isPresent() && possibleRule.get() instanceof JavaLibrary) {
        JavaLibrary compilerLibrary = (JavaLibrary) possibleRule.get();
        builder.addAll(compilerLibrary.getTransitiveClasspaths());
      }

      ImmutableSortedSet<SourcePath> fullJavacClasspath = builder.build();

      javac = new JarBackedJavac(compilerClassName, fullJavacClasspath);
    }

    return javac;
  }
}
