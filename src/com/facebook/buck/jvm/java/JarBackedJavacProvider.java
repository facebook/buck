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

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import javax.annotation.Nullable;

public class JarBackedJavacProvider implements JavacProvider {
  private final SourcePath javacJarPath;
  private final String compilerClassName;
  private final Javac.Location javacLocation;
  @Nullable private Javac javac;

  public JarBackedJavacProvider(
      SourcePath javacJarPath, String compilerClassName, Javac.Location javacLocation) {
    this.javacJarPath = javacJarPath;
    this.compilerClassName = compilerClassName;
    this.javacLocation = javacLocation;
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively("javacJar", javacJarPath)
        .setReflectively("compilerClassName", compilerClassName)
        .setReflectively("javacLocation", javacLocation);
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

      switch (javacLocation) {
        case IN_PROCESS:
          javac = new JarBackedJavac(compilerClassName, fullJavacClasspath);
          break;
        case OUT_OF_PROCESS:
          javac = new OutOfProcessJarBackedJavac(compilerClassName, fullJavacClasspath);
          break;
        default:
          throw new AssertionError("Unknown javac location: " + javacLocation);
      }
    }

    return javac;
  }
}
