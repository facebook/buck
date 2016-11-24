/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableSet;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

/**
 * Used to derive information from the constructor args returned by {@link Description} instances.
 * There are two major uses this information is put to: populating the DTO object from the
 * deserialized JSON maps, which are outputted by the functions added to Buck's core build file
 * parsing script. The second function of this class is to generate those functions.
 */
public class ConstructorArgMarshaller {

  private final TypeCoercerFactory typeCoercerFactory;
  private final Cache<Class<?>, ImmutableSet<ParamInfo>> coercedTypes;

  /**
   * Constructor. {@code pathFromProjectRootToBuildFile} is the path relative to the project root to
   * the build file that has called the build rule's function in buck.py. This is used for resolving
   * additional paths to ones relative to the project root, and to allow {@link BuildTarget}
   * instances to be fully qualified.
   */
  public ConstructorArgMarshaller(TypeCoercerFactory typeCoercerFactory) {
    this.typeCoercerFactory = typeCoercerFactory;
    this.coercedTypes = CacheBuilder.newBuilder().build();
  }

  /**
   * Use the information contained in the {@code params} to fill in the public fields and settable
   * properties of {@code dto}. The following rules are used:
   * <ul>
   *   <li>Boolean values are set to true or false.</li>
   *   <li>{@link BuildTarget}s are resolved and will be fully qualified.</li>
   *   <li>Numeric values are handled as if being cast from Long.</li>
   *   <li>{@link SourcePath} instances will be set to the appropriate implementation.</li>
   *   <li>{@link Path} declarations will be set to be relative to the project root.</li>
   *   <li>Strings will be set "as is".</li>
   *   <li>{@link List}s and {@link Set}s will be populated with the expected generic type,
   *   provided the wildcarding allows an upperbound to be determined to be one of the above.</li>
   * </ul>
   *
   * Any property that is marked as being an {@link Optional} field will be set to a default value
   * if none is set. This is typically {@link Optional#empty()}, but in the case of collections is
   * an empty collection.
   * @param params The parameters to be used to populate the {@code dto} instance.
   * @param dto The constructor dto to be populated.
   * @param declaredDeps A builder to be populated with the declared dependencies.
   * @param visibilityPatterns A builder to be populated with the visibility patterns.
   */
  public void populate(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      BuildTarget buildTarget,
      Object dto,
      ImmutableSet.Builder<BuildTarget> declaredDeps,
      ImmutableSet.Builder<VisibilityPattern> visibilityPatterns,
      Map<String, ?> instance) throws ParamInfoException {
    for (ParamInfo info : getAllParamInfo(dto)) {
      info.setFromParams(cellRoots, filesystem, buildTarget, dto, instance);
      if (info.getName().equals("deps")) {
        populateDeclaredDeps(info, declaredDeps, dto);
      }
    }
    populateVisibilityPatterns(cellRoots, visibilityPatterns, instance, buildTarget);
  }

  public void amendTargetNodeReferences(
      Function<BuildTarget, TargetNode<?, ?>> targetNodeResolver,
      Object dto) throws ParamInfoException {
    for (ParamInfo info : getAllParamInfo(dto)) {
      if (info.hasElementTypes(TargetNode.class)) {
        info.amendTargetNodeReferences(targetNodeResolver, dto);
      }
    }
  }

  /**
   * Populate only the fields that have default values.
   *
   * Used for testing.
   */
  @VisibleForTesting
  void populateDefaults(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      BuildTarget buildTarget,
      Object dto) throws ParamInfoException {
    for (ParamInfo info : getAllParamInfo(dto)) {
      if (info.isOptional()) {
        info.set(cellRoots, filesystem, buildTarget.getBasePath(), dto, null);
      }
    }
  }

  private void populateDeclaredDeps(
      ParamInfo paramInfo,
      final ImmutableSet.Builder<BuildTarget> declaredDeps,
      Object dto) {

    if (paramInfo.isDep()) {
      paramInfo.traverse(
          object -> {
            if (!(object instanceof BuildTarget)) {
              return;
            }
            declaredDeps.add((BuildTarget) object);
          },
          dto);

    }
  }

  @SuppressWarnings("unchecked")
  private void populateVisibilityPatterns(
      CellPathResolver cellNames,
      ImmutableSet.Builder<VisibilityPattern> visibilityPatterns,
      Map<String, ?> instance,
      BuildTarget target) {
    Object value = instance.get("visibility");
    if (value != null) {
      if (!(value instanceof List)) {
        throw new RuntimeException(
            String.format("Expected an array for visibility but was %s", value));
      }

      VisibilityPatternParser parser = new VisibilityPatternParser();
      for (String visibility : (List<String>) value) {
        try {
          visibilityPatterns.add(parser.parse(cellNames, visibility));
        } catch (IllegalArgumentException e) {
          throw new HumanReadableException(
              e,
              "Bad visibility expression: %s listed %s in its visibility argument, but only %s, " +
                  "%s, or fully qualified target patterns are allowed (i.e. those starting with " +
                  "// or a cell).",
              target.getFullyQualifiedName(),
              visibility,
              VisibilityPatternParser.VISIBILITY_PUBLIC,
              VisibilityPatternParser.VISIBILITY_GROUP);
        }
      }
    }
  }

  ImmutableSet<ParamInfo> getAllParamInfo(Object dto) {
    final Class<?> argClass = dto.getClass();
    try {
      return coercedTypes.get(argClass, () -> {
        ImmutableSet.Builder<ParamInfo> allInfo = ImmutableSet.builder();

        for (Field field : argClass.getFields()) {
          if (Modifier.isFinal(field.getModifiers())) {
            continue;
          }
          allInfo.add(new ParamInfo(typeCoercerFactory, argClass, field));
        }

        return allInfo.build();
      });
    } catch (ExecutionException e) {
      // This gets thrown if we saw an error when loading the value. Nothing sane to do here, and
      // we (previously, before using a cache), simply allowed a RuntimeException to bubble up.
      // Maintain that behaviour.
      throw new RuntimeException(e);
    }
  }
}
