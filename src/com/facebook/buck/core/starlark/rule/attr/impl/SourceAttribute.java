/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.starlark.rule.attr.impl;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.artifact.ImmutableSourceArtifactImpl;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetViewFactory;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.lib.DefaultInfo;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.starlark.rule.attr.Attribute;
import com.facebook.buck.core.starlark.rule.attr.PostCoercionTransform;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.rules.coercer.BuildTargetTypeCoercer;
import com.facebook.buck.rules.coercer.PathTypeCoercer;
import com.facebook.buck.rules.coercer.SourcePathTypeCoercer;
import com.facebook.buck.rules.coercer.TypeCoercer;
import com.facebook.buck.rules.coercer.UnconfiguredBuildTargetTypeCoercer;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Represents a single source file, whether on disk or another build target
 *
 * <p>Using this attribute will automatically add source files that are build targets as
 * dependencies.
 */
@BuckStyleValue
public abstract class SourceAttribute extends Attribute<SourcePath> {

  private static final TypeCoercer<SourcePath> coercer =
      new SourcePathTypeCoercer(
          new BuildTargetTypeCoercer(
              new UnconfiguredBuildTargetTypeCoercer(
                  new ParsingUnconfiguredBuildTargetViewFactory())),
          new PathTypeCoercer());

  @Override
  public abstract Object getPreCoercionDefaultValue();

  @Override
  public abstract String getDoc();

  @Override
  public abstract boolean getMandatory();

  @Override
  public void repr(SkylarkPrinter printer) {
    printer.append("<attr.source>");
  }

  @Override
  public TypeCoercer<SourcePath> getTypeCoercer() {
    return coercer;
  }

  @Override
  public PostCoercionTransform<ImmutableMap<BuildTarget, ProviderInfoCollection>, Artifact>
      getPostCoercionTransform() {
    return this::postCoercionTransform;
  }

  private Artifact postCoercionTransform(
      Object src, ImmutableMap<BuildTarget, ProviderInfoCollection> deps) {

    if (src instanceof BuildTargetSourcePath) {
      BuildTarget target = ((BuildTargetSourcePath) src).getTarget();
      ProviderInfoCollection providerInfos = deps.get(target);
      if (providerInfos == null) {
        throw new IllegalStateException(String.format("Deps %s did not contain %s", deps, src));
      }
      Set<Artifact> outputs =
          providerInfos
              .get(DefaultInfo.PROVIDER)
              .map(DefaultInfo::defaultOutputs)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          String.format("%s did not provide DefaultInfo", src)));
      try {
        return Iterables.getOnlyElement(outputs);
      } catch (NoSuchElementException | IllegalArgumentException e) {
        throw new IllegalStateException(
            String.format(
                "%s must have exactly one output, but had %s outputs", src, outputs.size()));
      }

    } else if (src instanceof PathSourcePath) {
      return ImmutableSourceArtifactImpl.of((PathSourcePath) src);
    } else {
      throw new IllegalStateException(
          String.format("%s must either be a source file, or a BuildTarget", src));
    }
  }
}
