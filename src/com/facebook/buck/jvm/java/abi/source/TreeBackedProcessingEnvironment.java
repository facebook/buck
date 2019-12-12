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

package com.facebook.buck.jvm.java.abi.source;

import com.sun.source.util.Trees;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

class TreeBackedProcessingEnvironment implements ProcessingEnvironment {
  private final FrontendOnlyJavacTask task;
  private final ProcessingEnvironment javacProcessingEnvironment;
  private final TreeBackedMessager messager;
  private final TreeBackedFiler filer;
  private final Map<String, String> options = new HashMap<>();

  public TreeBackedProcessingEnvironment(
      FrontendOnlyJavacTask task, ProcessingEnvironment javacProcessingEnvironment) {
    this.task = task;
    this.javacProcessingEnvironment = javacProcessingEnvironment;
    messager = new TreeBackedMessager(task, javacProcessingEnvironment.getMessager());
    filer = new TreeBackedFiler(task, javacProcessingEnvironment.getFiler());

    options.putAll(javacProcessingEnvironment.getOptions());
    options.put("com.facebook.buck.java.generating_abi", "true");
  }

  @Override
  public Map<String, String> getOptions() {
    return options;
  }

  @Override
  public Messager getMessager() {
    return messager;
  }

  @Override
  public Filer getFiler() {
    return filer;
  }

  @Override
  public Elements getElementUtils() {
    return task.getElements();
  }

  @Override
  public Types getTypeUtils() {
    return task.getTypes();
  }

  public Trees getTreeUtils() {
    return task.getTrees();
  }

  @Override
  public SourceVersion getSourceVersion() {
    return javacProcessingEnvironment.getSourceVersion();
  }

  @Override
  public Locale getLocale() {
    return javacProcessingEnvironment.getLocale();
  }
}
