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

import com.facebook.buck.model.Either;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableSet;

import org.immutables.value.Value;

import java.nio.file.Path;
import java.util.Optional;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractJavacSpec implements RuleKeyAppendable {
  public static final String COM_SUN_TOOLS_JAVAC_API_JAVAC_TOOL =
      "com.sun.tools.javac.api.JavacTool";

  protected abstract Optional<Either<Path, SourcePath>> getJavacPath();
  protected abstract Optional<SourcePath> getJavacJarPath();
  protected abstract Optional<String> getCompilerClassName();

  @Value.Lazy
  public JavacProvider getJavacProvider() {
    final Javac.Source javacSource = getJavacSource();
    final Javac.Location javacLocation = getJavacLocation();
    switch (javacSource) {
      case EXTERNAL:
        return new ConstantJavacProvider(
            ExternalJavac.createJavac(getJavacPath().get()));
      case JAR:
        switch (javacLocation) {
          case IN_PROCESS:
            return new ConstantJavacProvider(new JarBackedJavac(
                getCompilerClassName().orElse(COM_SUN_TOOLS_JAVAC_API_JAVAC_TOOL),
                ImmutableSet.of(getJavacJarPath().get())));
          case OUT_OF_PROCESS:
            return new ConstantJavacProvider(new OutOfProcessJarBackedJavac(
                getCompilerClassName().orElse(COM_SUN_TOOLS_JAVAC_API_JAVAC_TOOL),
                ImmutableSet.of(getJavacJarPath().get())));
        }
        break;
      case JDK:
        switch (javacLocation) {
          case IN_PROCESS:
            return new ConstantJavacProvider(new JdkProvidedInMemoryJavac());
          case OUT_OF_PROCESS:
            return new ConstantJavacProvider(new OutOfProcessJdkProvidedInMemoryJavac());
        }
        break;
    }
    throw new AssertionError(
        "Unknown javac source/javac location pair: " + javacSource + "/" + javacLocation);
  }

  public Javac.Source getJavacSource() {
    if (getJavacPath().isPresent()) {
      return Javac.Source.EXTERNAL;
    } else if (getJavacJarPath().isPresent()) {
      return Javac.Source.JAR;
    } else {
      return Javac.Source.JDK;
    }
  }

  @Value.Default
  public Javac.Location getJavacLocation() {
    return Javac.Location.IN_PROCESS;
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    getJavacProvider().appendToRuleKey(sink);
  }
}
