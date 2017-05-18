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

package com.facebook.buck.jvm.kotlin;

import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.jvm.java.HasJavaAbi;
import com.facebook.buck.jvm.java.JavaLibrary;
import com.facebook.buck.jvm.java.JavaLibraryDescription;
import com.facebook.buck.jvm.java.JavaSourceJar;
import com.facebook.buck.jvm.java.MavenUberJar;
import com.facebook.buck.maven.AetherUtil;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;
import org.immutables.value.Value;

public class KotlinLibraryDescription
    implements Description<KotlinLibraryDescriptionArg>, Flavored {

  private final KotlinBuckConfig kotlinBuckConfig;

  public static final ImmutableSet<Flavor> SUPPORTED_FLAVORS =
      ImmutableSet.of(JavaLibrary.SRC_JAR, JavaLibrary.MAVEN_JAR);

  public KotlinLibraryDescription(KotlinBuckConfig kotlinBuckConfig) {
    this.kotlinBuckConfig = kotlinBuckConfig;
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return SUPPORTED_FLAVORS.containsAll(flavors);
  }

  @Override
  public Class<KotlinLibraryDescriptionArg> getConstructorArgType() {
    return KotlinLibraryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      KotlinLibraryDescriptionArg args)
      throws NoSuchBuildTargetException {

    BuildTarget target = params.getBuildTarget();

    ImmutableSortedSet<Flavor> flavors = target.getFlavors();

    BuildRuleParams paramsWithMavenFlavor = null;
    if (flavors.contains(JavaLibrary.MAVEN_JAR)) {
      paramsWithMavenFlavor = params;

      // Maven rules will depend upon their vanilla versions, so the latter have to be constructed
      // without the maven flavor to prevent output-path conflict
      params = params.withoutFlavor(JavaLibrary.MAVEN_JAR);
    }

    if (flavors.contains(JavaLibrary.SRC_JAR)) {
      Optional<String> mavenCoords =
          args.getMavenCoords()
              .map(input -> AetherUtil.addClassifier(input, AetherUtil.CLASSIFIER_SOURCES));

      if (!flavors.contains(JavaLibrary.MAVEN_JAR)) {
        return new JavaSourceJar(params, args.getSrcs(), mavenCoords);
      } else {
        return MavenUberJar.SourceJar.create(
            Preconditions.checkNotNull(paramsWithMavenFlavor),
            args.getSrcs(),
            mavenCoords,
            args.getMavenPomTemplate());
      }
    }

    DefaultKotlinLibraryBuilder defaultKotlinLibraryBuilder =
        new DefaultKotlinLibraryBuilder(targetGraph, params, resolver, cellRoots, kotlinBuckConfig)
            .setArgs(args);

    // We know that the flavour we're being asked to create is valid, since the check is done when
    // creating the action graph from the target graph.
    if (HasJavaAbi.isAbiTarget(target)) {
      return defaultKotlinLibraryBuilder.buildAbi();
    }

    DefaultJavaLibrary defaultKotlinLibrary = defaultKotlinLibraryBuilder.build();

    if (!flavors.contains(JavaLibrary.MAVEN_JAR)) {
      return defaultKotlinLibrary;
    } else {
      return MavenUberJar.create(
          defaultKotlinLibrary,
          Preconditions.checkNotNull(paramsWithMavenFlavor),
          args.getMavenCoords(),
          args.getMavenPomTemplate());
    }
  }

  public interface CoreArg extends JavaLibraryDescription.CoreArg {
    ImmutableList<String> getExtraKotlincArguments();
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractKotlinLibraryDescriptionArg extends CoreArg {}
}
