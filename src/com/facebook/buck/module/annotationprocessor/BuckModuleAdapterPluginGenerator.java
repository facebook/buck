/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.module.annotationprocessor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic.Kind;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.stringtemplate.v4.AutoIndentWriter;
import org.stringtemplate.v4.ST;

/** Generates adapter plugin source code from Buck module descriptor. */
class BuckModuleAdapterPluginGenerator {
  public static final String ADAPTER_PLUGIN_TEMPLATE = "BuckModuleAdapterPlugin.st";
  public static final String MANIFEST_TEMPLATE = "MANIFEST.st";

  private final ProcessingEnvironment processingEnv;
  private final BuckModuleDescriptor buckModuleDescriptor;
  private final String adapterPluginClassName;
  private final String fullAdapterPluginClassName;

  public BuckModuleAdapterPluginGenerator(
      ProcessingEnvironment processingEnv, BuckModuleDescriptor buckModuleDescriptor) {
    this.processingEnv = processingEnv;
    this.buckModuleDescriptor = buckModuleDescriptor;
    adapterPluginClassName = buckModuleDescriptor.className + "AdapterPlugin";
    fullAdapterPluginClassName = buckModuleDescriptor.packageName + "." + adapterPluginClassName;
  }

  /** Generates code and writes it to files. */
  public void write() throws IOException {
    writePluginJavaFile();
    writePluginManifest();
  }

  private void writePluginJavaFile() throws IOException {
    ST template =
        getStringTemplate(ADAPTER_PLUGIN_TEMPLATE)
            .add("packageName", buckModuleDescriptor.packageName)
            .add("className", adapterPluginClassName);

    Writer writer =
        createSourceCodeWriter(
            fullAdapterPluginClassName, buckModuleDescriptor.buckModuleAnnotation);

    writeStringTemplateToWriter(writer, template);
  }

  private Writer createSourceCodeWriter(String className, TypeElement annotation)
      throws IOException {
    JavaFileObject fileObject = processingEnv.getFiler().createSourceFile(className, annotation);
    return fileObject.openWriter();
  }

  private void writePluginManifest() throws IOException {
    Writer writer = createResourceWriter();

    ST template =
        getStringTemplate(MANIFEST_TEMPLATE)
            .add("fullClassName", fullAdapterPluginClassName)
            .add("pluginDependencies", String.join(", ", buckModuleDescriptor.dependencies))
            .add("pluginId", buckModuleDescriptor.name);

    writeStringTemplateToWriter(writer, template);
  }

  private Writer createResourceWriter() throws IOException {
    FileObject fileObject =
        processingEnv
            .getFiler()
            .createResource(StandardLocation.CLASS_OUTPUT, "", "META-INF/MANIFEST.MF");
    return fileObject.openWriter();
  }

  private ST getStringTemplate(String templateFileName) throws IOException {
    InputStream in = getClass().getResourceAsStream(templateFileName);

    ByteArrayOutputStream result = new ByteArrayOutputStream();
    byte[] buffer = new byte[1024];
    int length;
    while ((length = in.read(buffer)) != -1) {
      result.write(buffer, 0, length);
    }
    String template = result.toString(StandardCharsets.UTF_8.name());

    return new ST(template, '%', '%');
  }

  private void writeStringTemplateToWriter(Writer writer, ST stringTemplate) throws IOException {
    AutoIndentWriter noIndentWriter = new AutoIndentWriter(writer);
    try {
      stringTemplate.write(noIndentWriter);
    } finally {
      try {
        writer.close();
      } catch (IOException e) {
        processingEnv
            .getMessager()
            .printMessage(Kind.WARNING, "Exception during close: " + ThrowablesUtils.toString(e));
      }
    }
  }
}
