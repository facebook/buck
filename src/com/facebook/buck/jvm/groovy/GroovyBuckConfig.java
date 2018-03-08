/*
 * Copyright 2015-present Facebook, Inc.
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
package com.facebook.buck.jvm.groovy;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.rules.HashedFileTool;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Suppliers;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Supplier;

public class GroovyBuckConfig {
  private final BuckConfig delegate;

  public GroovyBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  public BuckConfig getDelegate() {
    return delegate;
  }

  Supplier<Tool> getGroovyCompiler() {
    Optional<Path> path = delegate.getPath("groovy", "groovy_home");
    Path groovyHomePath;
    if (path.isPresent()) {
      groovyHomePath = path.get();
    } else {
      String defaultGroovyHome = delegate.getEnvironment().get("GROOVY_HOME");
      if (defaultGroovyHome == null) {
        throw new HumanReadableException(
            "Unable to locate groovy compiler:"
                + " GROOVY_HOME is not set, and groovy.groovy_home was not provided");
      } else {
        groovyHomePath = Paths.get(defaultGroovyHome);
      }
    }

    Path compiler =
        new ExecutableFinder()
            .getExecutable(groovyHomePath.resolve("bin/groovyc"), delegate.getEnvironment());

    return Suppliers.ofInstance(new HashedFileTool(delegate.getPathSourcePath(compiler)));
  }
}
