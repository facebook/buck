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

import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
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
 * Description of a rule that builds a Java compiler plugin (either a {@link
 * javax.annotation.processing.Processor} or TODO(jkeljo): a {@link com.sun.source.util.Plugin}).
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
    propsBuilder.setCanReuseClassLoader(reuseClassLoader);
    propsBuilder.setDoesNotAffectAbi(args.isDoesNotAffectAbi());
    propsBuilder.setSupportsAbiGenerationFromSource(args.isSupportsAbiGenerationFromSource());
    JavacPluginProperties properties = propsBuilder.build();

    return new JavaAnnotationProcessor(
        buildTarget, context.getProjectFilesystem(), params, properties);
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractJavaAnnotationProcessorDescriptionArg
      extends CommonDescriptionArg, HasDeclaredDeps {

    @Value.Default
    default Optional<String> getProcessorClass() {
      return Optional.empty();
    }

    ImmutableSet<String> getProcessorClasses();

    @Value.Default
    default boolean isIsolateClassLoader() {
      return false;
    }

    /**
     * A value of false indicates that the annotation processor generates classes that are intended
     * for use outside of the code being processed. Annotation processors that affect the ABI of the
     * rule in which they run must be run during ABI generation from source.
     *
     * <p>Defaults to false because that's the "safe" value. When migrating to ABI generation from
     * source, having as few ABI-affecting processors as possible will yield the fastest ABI
     * generation.
     */
    @Value.Default
    default boolean isDoesNotAffectAbi() {
      return false;
    }

    /**
     * If true, allows ABI-affecting annotation processors to run during ABI generation from source.
     * To run during ABI generation from source, an annotation processor must meet all of the
     * following criteria:
     * <li>
     *
     *     <ul>
     *       Uses only the public APIs from JSR-269 (annotation processing). Access to the Compiler
     *       Tree API may also be possible via a Buck support library.
     * </ul>
     *
     * <ul>
     *   Does not require details about types beyond those being compiled as a general rule. There
     *   are ways to ensure type information is available on a case by case basis, at some
     *   performance cost.
     * </ul>
     *
     * Defaults to false because that's the "safe" value. When migrating to ABI generation from
     * source, having as many ABI-affecting processors as possible running during ABI generation
     * will result in the flattest build graph.
     */
    @Value.Default
    default boolean isSupportsAbiGenerationFromSource() {
      return false;
    }
  }
}
