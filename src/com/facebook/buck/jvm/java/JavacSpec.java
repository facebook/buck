/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import java.util.Optional;
import org.immutables.value.Value;

@BuckStyleValue
public abstract class JavacSpec {
  public abstract Optional<SourcePath> getJavacPath();

  @Value.Lazy
  public JavacProvider getJavacProvider() {
    return ExternalJavacProvider.getProviderForSpec(this);
  }

  /** Returns {@link ResolvedJavac.Source} */
  public ResolvedJavac.Source getJavacSource() {
    if (getJavacPath().isPresent()) {
      return ResolvedJavac.Source.EXTERNAL;
    } else {
      return ResolvedJavac.Source.JDK;
    }
  }

  public static JavacSpec of() {
    return of(Optional.empty());
  }

  public static JavacSpec of(Optional<SourcePath> javacPath) {
    return ImmutableJavacSpec.ofImpl(javacPath);
  }
}
