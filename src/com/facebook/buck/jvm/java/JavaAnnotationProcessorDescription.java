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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.versions.VersionPropagator;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.collect.ImmutableSortedSet;

/**
 * Description of a rule that builds a Java compiler plugin (either a
 * {@link javax.annotation.processing.Processor} or
 * TODO(jkeljo): a {@link com.sun.source.util.Plugin}).
 */
public class JavaAnnotationProcessorDescription implements
    Description<JavaAnnotationProcessorDescription.Arg>,
    VersionPropagator<JavaAnnotationProcessorDescription.Arg> {
  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    JavacPluginProperties.Builder propsBuilder = JavacPluginProperties.builder();
    propsBuilder.addProcessorNames(args.processorClass);
    for (BuildRule dep : params.getDeps()) {
      if (!(dep instanceof JavaLibrary)) {
        throw new HumanReadableException(
            String.format(
                "%s: dependencies must produce JVM libraries; %s is a %s",
                params.getBuildTarget(),
                dep.getBuildTarget(),
                dep.getType()));
      }
      propsBuilder.addDep(dep);
    }

    boolean reuseClassLoader = !args.isolateClassLoader;
    propsBuilder.setCanReuseClassLoader(reuseClassLoader);
    JavacPluginProperties properties = propsBuilder.build();

    SourcePathResolver pathResolver = new SourcePathResolver(new SourcePathRuleFinder(resolver));
    return new JavaAnnotationProcessor(
        params.appendExtraDeps(properties.getClasspathDeps()),
        pathResolver,
        properties);
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();
    public String processorClass;
    public boolean isolateClassLoader = false;
  }
}
