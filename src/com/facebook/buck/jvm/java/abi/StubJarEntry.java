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

package com.facebook.buck.jvm.java.abi;

import com.facebook.buck.cd.model.java.AbiGenerationMode;
import com.facebook.buck.jvm.java.abi.kotlin.InlineFunctionScope;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import javax.annotation.Nullable;

public abstract class StubJarEntry {
  @Nullable
  static StubJarEntry of(
      LibraryReader input,
      Path path,
      AbiGenerationMode compatibilityMode,
      @Nullable InlineFunctionScope inlineFunctionScope)
      throws IOException {
    if (isStubbableResource(input, path)) {
      return StubJarResourceEntry.of(input, path);
    } else if (input.isClass(path)) {
      return StubJarClassEntry.of(input, path, compatibilityMode, inlineFunctionScope);
    }

    return null;
  }

  public abstract void write(StubJarWriter writer);

  public abstract List<String> getInlineFunctions();

  public abstract boolean extendsInlineFunctionScope();

  private static boolean isStubbableResource(LibraryReader input, Path path) {
    return input.isResource(path);
  }
}
