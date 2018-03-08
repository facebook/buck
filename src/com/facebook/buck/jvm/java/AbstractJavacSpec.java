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

import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.types.Either;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable(copy = true)
@BuckStyleImmutable
abstract class AbstractJavacSpec implements AddsToRuleKey {
  public static final String COM_SUN_TOOLS_JAVAC_API_JAVAC_TOOL =
      "com.sun.tools.javac.api.JavacTool";

  protected abstract Optional<Either<BuiltInJavac, SourcePath>> getCompiler();

  protected abstract Optional<Either<Path, SourcePath>> getJavacPath();

  protected abstract Optional<SourcePath> getJavacJarPath();

  protected abstract Optional<String> getCompilerClassName();

  @Value.Lazy
  @AddToRuleKey
  public JavacProvider getJavacProvider() {
    if (getCompiler().isPresent() && getCompiler().get().isRight()) {
      return new ExternalOrJarBackedJavacProvider(
          getCompiler().get().getRight(),
          // compiler_class_name has no effect when compiler is specified
          COM_SUN_TOOLS_JAVAC_API_JAVAC_TOOL,
          getJavacLocation());
    }

    String compilerClassName = getCompilerClassName().orElse(COM_SUN_TOOLS_JAVAC_API_JAVAC_TOOL);
    Javac.Source javacSource = getJavacSource();
    Javac.Location javacLocation = getJavacLocation();
    switch (javacSource) {
      case EXTERNAL:
        return new ConstantJavacProvider(ExternalJavac.createJavac(getJavacPath().get()));
      case JAR:
        return new JarBackedJavacProvider(
            getJavacJarPath().get(), compilerClassName, javacLocation);
      case JDK:
        switch (javacLocation) {
          case IN_PROCESS:
            return new ConstantJavacProvider(new JdkProvidedInMemoryJavac());
        }
        break;
    }
    throw new AssertionError(
        "Unknown javac source/javac location pair: " + javacSource + "/" + javacLocation);
  }

  public Javac.Source getJavacSource() {
    if (getJavacPath().isPresent() && getJavacJarPath().isPresent()) {
      throw new HumanReadableException("Cannot set both javac and javacjar");
    }

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
}
