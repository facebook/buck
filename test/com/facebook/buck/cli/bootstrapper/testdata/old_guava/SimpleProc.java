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

import static javax.lang.model.SourceVersion.RELEASE_7;

import java.io.Closeable;
import java.io.StringReader;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.element.TypeElement;

import com.google.common.io.Closeables;

/**
 * Simple annotation processor that does nothing.
 */
@SupportedSourceVersion(RELEASE_7)
@SupportedAnnotationTypes("*")
public class SimpleProc extends AbstractProcessor {
  @Override
  public boolean process(
      Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    Closeable closeable = new StringReader("example");
    Closeables.closeQuietly(closeable);
    return false;
  }
}
