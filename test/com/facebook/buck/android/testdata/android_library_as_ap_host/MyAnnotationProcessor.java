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

package com.facebook.example;

import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

@SupportedAnnotationTypes("com.facebook.example.MyAnnotation")
@SupportedSourceVersion(SourceVersion.RELEASE_7)
public class MyAnnotationProcessor extends AbstractProcessor {
  private Filer mFiler;
  private Elements mElementUtils;
  private Messager mMessager;

  private int runCount = 0;

  @Override
  public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    mFiler = processingEnv.getFiler();
    mElementUtils = processingEnv.getElementUtils();
    mMessager = processingEnv.getMessager();
  }

  @Override
  public boolean process(
      final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {
    if (runCount > 0) {
      return false;
    }
    try {
      List<String> required_configs = Arrays.asList("res1.json");
      List<String> maybe_configs = Arrays.asList("res2.json");

      OutputStream outputStream =
          mFiler
              .createResource(
                  StandardLocation.CLASS_OUTPUT,
                  "com.facebook.example.config",
                  "collected_configs.json",
                  mElementUtils.getTypeElement("com.facebook.example.MyAnnotationProcessor"))
              .openOutputStream();

      StringBuilder sb = new StringBuilder();
      sb.append("{\n");
      byte[] buffer = new byte[1024];
      for (String config : required_configs) {
        try {
          FileObject inputFile =
              mFiler.getResource(StandardLocation.CLASS_PATH, "", "META-INF/" + config);
          int len = inputFile.openInputStream().read(buffer);
          String contents = new String(buffer, 0, len);
          sb.append("    \"").append(config).append("\": ");
          sb.append("\"").append(contents).append("\"");
          sb.append(",\n");
        } catch (Exception e) {
          mMessager.printMessage(
              Diagnostic.Kind.ERROR,
              "Could not open " + config + " because of " + e.getLocalizedMessage());
        }
      }
      for (String config : maybe_configs) {
        try {
          FileObject inputFile =
              mFiler.getResource(StandardLocation.CLASS_PATH, "", "META-INF/" + config);
          int len = inputFile.openInputStream().read(buffer);
          String contents = new String(buffer, 0, len);
          sb.append("    \"").append(config).append("\": ");
          sb.append("\"").append(contents).append("\"");
          sb.append(",\n");
        } catch (Exception e) {
          mMessager.printMessage(Diagnostic.Kind.NOTE, config + " not found");
        }
      }
      sb.append("\"version\": 1");
      sb.append("\n}");
      outputStream.write(sb.toString().getBytes());
      outputStream.close();

    } catch (Exception e) {
      mMessager.printMessage(Diagnostic.Kind.ERROR, "" + e);
      throw new RuntimeException(e);
    }
    runCount += 1;
    return true;
  }
}
