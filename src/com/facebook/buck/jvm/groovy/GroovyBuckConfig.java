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

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.ExecutableFinder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class GroovyBuckConfig {
  private static final String SECTION = "groovy";
  private static final String GROOVY_HOME_CONFIG = "groovy_home";

  private final BuckConfig delegate;

  public GroovyBuckConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  public BuckConfig getDelegate() {
    return delegate;
  }

  Tool getGroovyc(TargetConfiguration targetConfiguration) {
    Optional<SourcePath> sourcePath =
        delegate.getSourcePath(SECTION, GROOVY_HOME_CONFIG, targetConfiguration);
    if (sourcePath.isPresent()) {
      return new Groovyc(sourcePath.get(), false);
    } else {
      String defaultGroovyHome = delegate.getEnvironment().get("GROOVY_HOME");
      if (defaultGroovyHome == null) {
        throw new HumanReadableException(
            "Unable to locate groovy compiler:"
                + " GROOVY_HOME is not set, and groovy.groovy_home was not provided");
      }

      Path groovyHomePath = Paths.get(defaultGroovyHome);
      Path compiler =
          new ExecutableFinder()
              .getExecutable(
                  groovyHomePath.resolve(Groovyc.BIN_GROOVYC), delegate.getEnvironment());
      return new Groovyc(delegate.getPathSourcePath(compiler), true);
    }
  }

  public Optional<BuildTarget> getGroovycTarget(TargetConfiguration targetConfiguration) {
    return delegate.getMaybeBuildTarget(SECTION, GROOVY_HOME_CONFIG, targetConfiguration);
  }
}
