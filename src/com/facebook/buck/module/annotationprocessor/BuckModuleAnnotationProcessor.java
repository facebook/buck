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

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic.Kind;

/**
 * Generates an adapter plugin for the plugin framework to allow loading modules from jars outside
 * of the main Buck jar.
 *
 * <p>For example, given the following configuration:
 *
 * <pre>
 *  {@literal @}BuckModule(
 *     name = "com.facebook.buck.some.module"
 *   )
 *   public class SomeModule {}
 * </pre>
 *
 * The annotation processor will generate the following adapter in the same package:
 *
 * <pre>
 *   public class SomeModuleAdapterPlugin extends Plugin {
 *     public SomeModuleAdapterPlugin(PluginWrapper wrapper) {
 *       super(wrapper);
 *     }
 *   }
 * </pre>
 */
@SupportedAnnotationTypes({BuckModuleAnnotationProcessorConstants.BUCK_MODULE_ANNOTATION})
@SupportedSourceVersion(SourceVersion.RELEASE_6)
public class BuckModuleAnnotationProcessor extends AbstractProcessor {

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    if (roundEnv.processingOver()) {
      return false;
    }

    List<BuckModuleDescriptor> buckModuleDescriptors =
        collectBuckModuleDescriptors(annotations, roundEnv);

    if (buckModuleDescriptors.isEmpty()) {
      return false;
    }

    assertOneBuckModuleDescriptor(buckModuleDescriptors);

    return generateBuckModuleAdapterPlugin(buckModuleDescriptors.get(0));
  }

  private List<BuckModuleDescriptor> collectBuckModuleDescriptors(
      Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    BuckModuleVisitor visitor = new BuckModuleVisitor(processingEnv);
    for (TypeElement annotation : annotations) {
      for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
        element.accept(visitor, annotation);
      }
    }

    if (visitor.hasData()) {
      return visitor.getBuckModuleDescriptors();
    }
    return Collections.emptyList();
  }

  private void assertOneBuckModuleDescriptor(List<BuckModuleDescriptor> buckModuleDescriptors) {
    if (buckModuleDescriptors.size() > 1) {
      throw new IllegalArgumentException("Got multiple Buck modules: " + buckModuleDescriptors);
    }
  }

  private boolean generateBuckModuleAdapterPlugin(BuckModuleDescriptor buckModuleDescriptor) {
    BuckModuleAdapterPluginGenerator buckModuleAdapterPluginGenerator =
        new BuckModuleAdapterPluginGenerator(processingEnv, buckModuleDescriptor);

    try {
      buckModuleAdapterPluginGenerator.write();
    } catch (IOException e) {
      processingEnv.getMessager().printMessage(Kind.ERROR, "Could not generate Buck module: " + e);
      return false;
    }

    return true;
  }
}
