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
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import java.util.Optional;
import org.immutables.value.Value;

@Value.Immutable(copy = true)
@BuckStyleImmutable
abstract class AbstractJavacSpec {
  protected abstract Optional<SourcePath> getJavacPath();

  protected abstract Optional<SourcePath> getJavacJarPath();

  protected abstract Optional<String> getCompilerClassName();

  @Value.Lazy
  public JavacProvider getJavacProvider() {
    return ExternalJavacFactory.getProviderForSpec((JavacSpec) this);
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
}
