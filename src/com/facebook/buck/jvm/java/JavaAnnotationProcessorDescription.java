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

package com.facebook.buck.jvm.java;

import static com.facebook.buck.jvm.java.AbstractJavacPluginProperties.Type.ANNOTATION_PROCESSOR;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.collect.ImmutableSet;
import java.util.Optional;
import org.immutables.value.Value;

/**
 * Description of a rule that builds a {@link javax.annotation.processing.Processor} javac plugin.
 */
public class JavaAnnotationProcessorDescription
    implements DescriptionWithTargetGraph<JavaAnnotationProcessorDescriptionArg>,
        VersionPropagator<JavaAnnotationProcessorDescriptionArg> {
  @Override
  public Class<JavaAnnotationProcessorDescriptionArg> getConstructorArgType() {
    return JavaAnnotationProcessorDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      JavaAnnotationProcessorDescriptionArg args) {
    if (!args.getProcessorClass().isPresent() && args.getProcessorClasses().isEmpty()) {
      throw new HumanReadableException(
          String.format("%s: must specify a processor class, none specified;", buildTarget));
    }

    JavacPluginProperties.Builder propsBuilder = JavacPluginProperties.builder();

    if (args.getProcessorClass().isPresent()) {
      propsBuilder.addProcessorNames(args.getProcessorClass().get());
    } else {
      for (String pClass : args.getProcessorClasses()) {
        propsBuilder.addProcessorNames(pClass);
      }
    }

    for (BuildRule dep : params.getBuildDeps()) {
      if (!(dep instanceof JavaLibrary)) {
        throw new HumanReadableException(
            String.format(
                "%s: dependencies must produce JVM libraries; %s is a %s",
                buildTarget, dep.getBuildTarget(), dep.getType()));
      }
      propsBuilder.addDep(dep);
    }

    boolean reuseClassLoader = !args.isIsolateClassLoader();
    propsBuilder.setType(ANNOTATION_PROCESSOR);
    propsBuilder.setCanReuseClassLoader(reuseClassLoader);
    propsBuilder.setDoesNotAffectAbi(args.isDoesNotAffectAbi());
    propsBuilder.setSupportsAbiGenerationFromSource(args.isSupportsAbiGenerationFromSource());
    JavacPluginProperties properties = propsBuilder.build();

    return new JavaAnnotationProcessor(
        buildTarget, context.getProjectFilesystem(), params, properties);
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractJavaAnnotationProcessorDescriptionArg extends JavacPluginArgs {

    Optional<String> getProcessorClass();

    ImmutableSet<String> getProcessorClasses();
  }
}
