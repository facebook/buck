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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.util.types.Either;
import com.google.common.base.Preconditions;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Provides either an {@link ExternalJavac} or {@link JarBackedJavac}, depending on its parameters.
 * This is here to support {@link JvmLibraryArg#getCompiler}.
 */
public class ExternalOrJarBackedJavacProvider implements JavacProvider, AddsToRuleKey {
  @AddToRuleKey private final SourcePath compiler;
  @AddToRuleKey @Nullable private final String compilerClassName;
  @AddToRuleKey private Javac.Location javacLocation;

  // This is just used to cache the Javac derived from the other fields.
  @Nullable private Javac javac;

  public ExternalOrJarBackedJavacProvider(
      SourcePath compiler, @Nullable String compilerClassName, Javac.Location javacLocation) {
    this.compiler = compiler;
    this.compilerClassName = compilerClassName;
    this.javacLocation = javacLocation;
  }

  @Override
  public Javac resolve(SourcePathRuleFinder ruleFinder) {
    if (javac == null) {
      Optional<BuildRule> possibleRule = ruleFinder.getRule(compiler);
      if (possibleRule.isPresent() && possibleRule.get() instanceof JavaLibrary) {
        SourcePath javacJarPath = possibleRule.get().getSourcePathToOutput();
        if (javacJarPath == null) {
          throw new HumanReadableException(
              String.format(
                  "%s isn't a valid value for javac_jar because it does not produce output",
                  compiler));
        }

        javac =
            new JarBackedJavacProvider(
                    javacJarPath, Preconditions.checkNotNull(compilerClassName), javacLocation)
                .resolve(ruleFinder);
      } else {
        javac = new ExternalJavac(Either.ofRight(compiler));
      }
    }

    return javac;
  }
}
