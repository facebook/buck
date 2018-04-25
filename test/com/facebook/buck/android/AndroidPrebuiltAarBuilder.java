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

package com.facebook.buck.android;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.jvm.java.JavaCompilationConstants;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.FakeSourcePath;
import java.nio.file.Path;
import java.util.Optional;

public class AndroidPrebuiltAarBuilder
    extends AbstractNodeBuilder<
        AndroidPrebuiltAarDescriptionArg.Builder, AndroidPrebuiltAarDescriptionArg,
        AndroidPrebuiltAarDescription, AndroidPrebuiltAar> {

  private AndroidPrebuiltAarBuilder(BuildTarget target) {
    super(new AndroidPrebuiltAarDescription(JavaCompilationConstants.DEFAULT_JAVA_CONFIG), target);
  }

  public static AndroidPrebuiltAarBuilder createBuilder(BuildTarget target) {
    return new AndroidPrebuiltAarBuilder(target);
  }

  public AndroidPrebuiltAarBuilder setBinaryAar(SourcePath binaryAar) {
    getArgForPopulating().setAar(binaryAar);
    return this;
  }

  public AndroidPrebuiltAarBuilder setSourcesJar(Path sourcesJar) {
    getArgForPopulating().setSourceJar(Optional.of(FakeSourcePath.of(sourcesJar.toString())));
    return this;
  }

  public AndroidPrebuiltAarBuilder setJavadocUrl(String javadocUrl) {
    getArgForPopulating().setJavadocUrl(Optional.of(javadocUrl));
    return this;
  }
}
