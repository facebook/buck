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

package com.facebook.buck.apple.clang;

import com.google.common.base.Objects;
import javax.annotation.Nullable;
import org.stringtemplate.v4.ST;

/**
 * A module map using an umbrella directory, rather than an umbrella header. This removes the need
 * to maintain or generate an umbrella header: all exported headers in the library will be included
 * in the module automatically.
 */
public class UmbrellaDirectoryModuleMap implements ModuleMap {
  private final String moduleName;

  @Nullable private String generatedModule;
  private static final String template =
      "module <module_name> {\n"
          + "    umbrella \".\"\n"
          + "\n"
          + "    export *\n"
          + "    module * { export * }\n"
          + "}\n"
          + "\n";

  public UmbrellaDirectoryModuleMap(String moduleName) {
    this.moduleName = moduleName;
  }

  @Override
  public String render() {
    if (this.generatedModule == null) {
      ST st = new ST(template).add("module_name", moduleName);
      this.generatedModule = st.render();
    }
    return this.generatedModule;
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof UmbrellaDirectoryModuleMap)) {
      return false;
    }
    UmbrellaDirectoryModuleMap that = (UmbrellaDirectoryModuleMap) obj;
    return Objects.equal(this.moduleName, that.moduleName);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(moduleName);
  }
}
