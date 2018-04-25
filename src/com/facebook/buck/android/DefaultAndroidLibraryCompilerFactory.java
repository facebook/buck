/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.jvm.java.ConfiguredCompilerFactory;
import com.facebook.buck.jvm.java.JavaBuckConfig;
import com.facebook.buck.jvm.java.JavaConfiguredCompilerFactory;
import com.facebook.buck.jvm.kotlin.KotlinBuckConfig;
import com.facebook.buck.jvm.kotlin.KotlinConfiguredCompilerFactory;
import com.facebook.buck.jvm.scala.ScalaBuckConfig;
import com.facebook.buck.jvm.scala.ScalaConfiguredCompilerFactory;

public class DefaultAndroidLibraryCompilerFactory implements AndroidLibraryCompilerFactory {
  private final JavaBuckConfig javaConfig;
  private final ScalaBuckConfig scalaConfig;
  private final KotlinBuckConfig kotlinBuckConfig;

  public DefaultAndroidLibraryCompilerFactory(
      JavaBuckConfig javaConfig, ScalaBuckConfig scalaConfig, KotlinBuckConfig kotlinBuckConfig) {
    this.javaConfig = javaConfig;
    this.scalaConfig = scalaConfig;
    this.kotlinBuckConfig = kotlinBuckConfig;
  }

  @Override
  public ConfiguredCompilerFactory getCompiler(AndroidLibraryDescription.JvmLanguage language) {
    switch (language) {
      case JAVA:
        return new JavaConfiguredCompilerFactory(javaConfig, AndroidClasspathProvider::new);
      case SCALA:
        return new ScalaConfiguredCompilerFactory(
            scalaConfig, javaConfig, AndroidClasspathProvider::new);
      case KOTLIN:
        return new KotlinConfiguredCompilerFactory(
            kotlinBuckConfig, javaConfig, AndroidClasspathProvider::new);
    }
    throw new HumanReadableException("Unsupported `language` parameter value: %s", language);
  }
}
