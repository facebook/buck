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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.select.SelectorList;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;

/**
 * Used to derive information from the constructor args returned by {@link
 * com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph} instances. There are two
 * major uses this information is put to: populating the DTO object from the deserialized JSON maps,
 * which are outputted by the functions added to Buck's core build file parsing script. The second
 * function of this class is to generate those functions.
 */
public class ConstructorArgMarshaller {

  private final TypeCoercerFactory typeCoercerFactory;

  /**
   * Constructor. {@code pathFromProjectRootToBuildFile} is the path relative to the project root to
   * the build file that has called the build rule's function in buck.py. This is used for resolving
   * additional paths to ones relative to the project root, and to allow {@link BuildTarget}
   * instances to be fully qualified.
   */
  public ConstructorArgMarshaller(TypeCoercerFactory typeCoercerFactory) {
    this.typeCoercerFactory = typeCoercerFactory;
  }

  /**
   * Use the information contained in the {@code params} to fill in the public fields and settable
   * properties of {@code dto}. The following rules are used:
   *
   * <ul>
   *   <li>Boolean values are set to true or false.
   *   <li>{@link BuildTarget}s are resolved and will be fully qualified.
   *   <li>Numeric values are handled as if being cast from Long.
   *   <li>{@link SourcePath} instances will be set to the appropriate implementation.
   *   <li>{@link Path} declarations will be set to be relative to the project root.
   *   <li>Strings will be set "as is".
   *   <li>{@link List}s and {@link Set}s will be populated with the expected generic type, provided
   *       the wildcarding allows an upperbound to be determined to be one of the above.
   * </ul>
   *
   * Any property that is marked as being an {@link Optional} field will be set to a default value
   * if none is set. This is typically {@link Optional#empty()}, but in the case of collections is
   * an empty collection.
   *
   * @param dtoClass The type of the immutable constructor dto to be populated.
   * @param declaredDeps A builder to be populated with the declared dependencies.
   * @return The fully populated DTO.
   */
  @CheckReturnValue
  public <T> T populate(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      BuildTarget buildTarget,
      Class<T> dtoClass,
      ImmutableSet.Builder<BuildTarget> declaredDeps,
      Map<String, ?> instance)
      throws ParamInfoException {
    Pair<Object, Function<Object, T>> dtoAndBuild =
        CoercedTypeCache.instantiateSkeleton(dtoClass, buildTarget);
    ImmutableMap<String, ParamInfo> allParamInfo =
        CoercedTypeCache.INSTANCE.getAllParamInfo(typeCoercerFactory, dtoClass);
    for (ParamInfo info : allParamInfo.values()) {
      info.setFromParams(cellRoots, filesystem, buildTarget, dtoAndBuild.getFirst(), instance);
    }
    T dto = dtoAndBuild.getSecond().apply(dtoAndBuild.getFirst());
    collectDeclaredDeps(cellRoots, allParamInfo.get("deps"), declaredDeps, dto);
    return dto;
  }

  private void collectDeclaredDeps(
      CellPathResolver cellPathResolver,
      @Nullable ParamInfo deps,
      ImmutableSet.Builder<BuildTarget> declaredDeps,
      Object dto) {
    if (deps != null && deps.isDep()) {
      deps.traverse(
          cellPathResolver,
          object -> {
            if (!(object instanceof BuildTarget)) {
              return;
            }
            declaredDeps.add((BuildTarget) object);
          },
          dto);
    }
  }

  /**
   * Creates a constructor argument using configured attributes.
   *
   * @param attributes configured attributes that cannot contain selectable values (instances of
   *     {@link SelectorList})
   */
  public <T> T populateFromConfiguredAttributes(
      CellPathResolver cellPathResolver,
      BuildTarget buildTarget,
      Class<T> dtoClass,
      ImmutableSet.Builder<BuildTarget> declaredDeps,
      ImmutableMap<String, ?> attributes) {
    Pair<Object, Function<Object, T>> dtoAndBuild =
        CoercedTypeCache.instantiateSkeleton(dtoClass, buildTarget);
    ImmutableMap<String, ParamInfo> allParamInfo =
        CoercedTypeCache.INSTANCE.getAllParamInfo(typeCoercerFactory, dtoClass);
    for (ParamInfo info : allParamInfo.values()) {
      Object argumentValue = attributes.get(info.getName());
      if (argumentValue == null) {
        continue;
      }
      Preconditions.checkArgument(
          !(argumentValue instanceof SelectorList),
          "Attribute \"%s\" is not resolved",
          info.getName());
      info.setCoercedValue(dtoAndBuild.getFirst(), argumentValue);
    }
    T dto = dtoAndBuild.getSecond().apply(dtoAndBuild.getFirst());
    collectDeclaredDeps(cellPathResolver, allParamInfo.get("deps"), declaredDeps, dto);
    return dto;
  }

  /**
   * Creates a map with coerced attributes using raw attributes.
   *
   * @param rawAttributes raw attributes that can contain selectable values (instances of {@link
   *     SelectorList})
   */
  public ImmutableMap<String, Object> convertRawAttributes(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      BuildTarget buildTarget,
      Class<?> dtoClass,
      Map<String, Object> rawAttributes)
      throws CoerceFailedException {
    ImmutableMap<String, ParamInfo> allParamInfo =
        CoercedTypeCache.INSTANCE.getAllParamInfo(typeCoercerFactory, dtoClass);
    ImmutableMap.Builder<String, Object> populatedAttributesBuilder = ImmutableMap.builder();
    for (Map.Entry<String, Object> rawAttribute : rawAttributes.entrySet()) {
      String attributeName = rawAttribute.getKey();
      ParamInfo paramInfo = allParamInfo.get(attributeName);
      if (paramInfo == null) {
        continue;
      }
      Object rawValue = rawAttribute.getValue();
      Object value =
          createCoercedAttributeWithSelectableValue(
              cellRoots, filesystem, buildTarget, paramInfo, rawValue);
      populatedAttributesBuilder.put(attributeName, value);
    }
    return populatedAttributesBuilder.build();
  }

  private Object createCoercedAttributeWithSelectableValue(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      BuildTarget buildTarget,
      ParamInfo argumentInfo,
      Object rawValue)
      throws CoerceFailedException {
    TypeCoercer<?> coercer;
    // When an attribute value contains an instance of {@link
    // com.google.devtools.build.lib.syntax.SelectorList} it's
    // coerced by a coercer for {@link com.facebook.buck.core.select.SelectorList}.
    // The reason why we cannot use coercer from {@code argumentInfo} because {@link
    // com.google.devtools.build.lib.syntax.SelectorList} is not generic class, but an instance
    // contains all necessry information to coerce the value into an instance of {@link
    // com.facebook.buck.core.select.SelectorList} which is a generic class.
    if (rawValue instanceof com.google.devtools.build.lib.syntax.SelectorList) {
      coercer =
          typeCoercerFactory.typeCoercerForParameterizedType(
              "SelectorList",
              SelectorList.class,
              argumentInfo.getSetter().getGenericParameterTypes());
    } else {
      coercer = argumentInfo.getTypeCoercer();
    }
    return coercer.coerce(cellRoots, filesystem, buildTarget.getBasePath(), rawValue);
  }
}
