/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.parser;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.AbstractBuildRuleBuilder;
import com.facebook.buck.rules.ResourcesAttributeBuilder;
import com.facebook.buck.rules.SrcsAttributeBuilder;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.Beta;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableSet;

@Beta
public abstract class AbstractBuildRuleFactory<T extends AbstractBuildRuleBuilder<?>>
    implements BuildRuleFactory<T> {

  protected abstract T newBuilder();

  /**
   * Subclasses should override this method to extract any information from the Python object that
   * is not extracted by default.
   */
  protected abstract void amendBuilder(T builder, BuildRuleFactoryParams params)
      throws NoSuchBuildTargetException;

  @Override
  public T newInstance(BuildRuleFactoryParams params)
      throws NoSuchBuildTargetException {
    T builder = newBuilder();
    BuildTarget target = params.target;

    // name
    builder.setBuildTarget(target);

    // deps
    ParseContext buildFileParseContext = ParseContext.forBaseName(target.getBaseName());
    for (String dep : params.getOptionalListAttribute("deps")) {
      BuildTarget buildTarget = params.buildTargetParser.parse(dep, buildFileParseContext);
      builder.addDep(buildTarget.getFullyQualifiedName());
    }

    // visibility
    for (BuildTargetPattern visiBuildTargetPattern : getVisibilityPatterns(params)) {
      builder.addVisibilityPattern(visiBuildTargetPattern);
    }

    // srcs
    if (builder instanceof SrcsAttributeBuilder) {
      for (String src : params.getOptionalListAttribute("srcs")) {
        String relativePath = params.resolveFilePathRelativeToBuildFileDirectory(src);
        ((SrcsAttributeBuilder)builder).addSrc(relativePath);
      }
    }

    // resources
    if (builder instanceof ResourcesAttributeBuilder) {
      for (String resource : params.getOptionalListAttribute("resources")) {
        String relativePath = params.resolveFilePathRelativeToBuildFileDirectory(resource);
        ((ResourcesAttributeBuilder)builder).addResource(relativePath);
      }
    }

    amendBuilder(builder, params);

    return builder;
  }

  @VisibleForTesting
  static ImmutableSet<BuildTargetPattern> getVisibilityPatterns(BuildRuleFactoryParams params)
      throws NoSuchBuildTargetException {
    ImmutableSet.Builder<BuildTargetPattern> builder = ImmutableSet.builder();
    for (String visibilityPattern : params.getOptionalListAttribute("visibility")) {
      builder.add(
          params.buildTargetPatternParser.parse(
              visibilityPattern, ParseContext.forVisibilityArgument()));
    }
    return builder.build();
  }

  /**
   * @return a function that takes a dep from this build file and returns the fully-qualified
   *     version. The function will throw a {@link HumanReadableException} whose cause will be a
   *     {@link NoSuchBuildTargetException} if the function is passed a build target that it cannot
   *     parse or resolve.
   */
  protected Function<String, String> createBuildTargetParseFunction(
      BuildRuleFactoryParams params) {
    final BuildTargetParser buildTargetParser = params.buildTargetParser;
    final ParseContext parseContext = ParseContext.forBaseName(params.target.getBaseName());

    return new Function<String, String>() {
      @Override
      public String apply(String buildTargetName) {
        try {
          return buildTargetParser.parse(buildTargetName, parseContext).getFullyQualifiedName();
        } catch (NoSuchBuildTargetException e) {
          throw new HumanReadableException(e);
        }
      }
    };
  }
}
