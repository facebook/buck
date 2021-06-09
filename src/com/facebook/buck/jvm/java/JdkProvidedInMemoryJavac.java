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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.jvm.java.javax.SynchronizedToolProvider;
import com.google.common.base.MoreObjects;
import javax.tools.JavaCompiler;

public class JdkProvidedInMemoryJavac extends Jsr199Javac {

  public static JdkProvidedInMemoryJavac INSTANCE = new JdkProvidedInMemoryJavac();

  private JdkProvidedInMemoryJavac() {}

  @Override
  protected ResolvedJsr199Javac create(SourcePathResolverAdapter resolver, AbsPath ruleCellRoot) {
    return createJsr199Javac();
  }

  /** Creates in memory javac */
  public static ResolvedJsr199Javac createJsr199Javac() {
    return new ResolvedJsr199Javac() {

      @Override
      protected JavaCompiler createCompiler(JavacExecutionContext context) {
        JavaCompiler compiler = SynchronizedToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
          throw new HumanReadableException(
              "No system compiler found. Did you install the JRE instead of the JDK?");
        }
        return compiler;
      }
    };
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    return o != null && getClass() == o.getClass();
  }

  @Override
  public int hashCode() {
    return super.hashCode();
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this).toString();
  }
}
