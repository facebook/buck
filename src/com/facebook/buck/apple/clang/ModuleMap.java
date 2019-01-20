/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.apple.clang;

import com.google.common.base.Objects;
import org.stringtemplate.v4.ST;

/**
 * Generate a modulemap, with optional references to a swift generated swift header.
 *
 * <p>Note: currently only supports modulemaps for modules with an umbrella header, as otherwise the
 * inferred submodule functionality does not work.
 */
public class ModuleMap {

  public enum SwiftMode {
    NO_SWIFT,
    EXCLUDE_SWIFT_HEADER,
    INCLUDE_SWIFT_HEADER
  }

  private final SwiftMode swiftMode;
  private final String moduleName;

  private String generatedModule;
  private static final String template =
      "module <module_name> {\n"
          + "    umbrella header \"<module_name>.h\"\n"
          + "\n"
          + "    export *\n"
          + "    module * { export * }\n"
          + "}\n"
          + "\n"
          + "<if(include_swift_header)>"
          + "module <module_name>.Swift {\n"
          + "    header \"<module_name>-Swift.h\"\n"
          + "    requires objc\n"
          + "}\n"
          + "<endif>"
          + "\n"
          + "<if(exclude_swift_header)>"
          + "module <module_name>.__Swift {\n"
          + "    exclude header \"<module_name>-Swift.h\"\n"
          + "}\n"
          + "<endif>"
          + "\n";

  public ModuleMap(String moduleName, SwiftMode swiftMode) {
    this.moduleName = moduleName;
    this.swiftMode = swiftMode;
  }

  public String render() {
    if (this.generatedModule == null) {
      ST st =
          new ST(template)
              .add("module_name", moduleName)
              .add("include_swift_header", false)
              .add("exclude_swift_header", false);
      switch (swiftMode) {
        case INCLUDE_SWIFT_HEADER:
          st.add("include_swift_header", true);
          break;
        case EXCLUDE_SWIFT_HEADER:
          st.add("exclude_swift_header", true);
          break;
        default:
        case NO_SWIFT:
      }
      this.generatedModule = st.render();
    }
    return this.generatedModule;
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof ModuleMap)) {
      return false;
    }
    ModuleMap that = (ModuleMap) obj;
    return Objects.equal(this.swiftMode, that.swiftMode)
        && Objects.equal(this.moduleName, that.moduleName);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(swiftMode, moduleName);
  }
}
