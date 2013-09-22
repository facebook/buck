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

import com.facebook.buck.model.BuildTarget;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Used to derive information from the constructor args returned by {@link Description} instances.
 * There are two major uses this information is put to: populating the DTO object from the
 * {@link BuildRuleFactoryParams}, which is populated by the functions added to Buck's core build
 * file parsing script. The second function of this class is to generate those functions.
 * <p>
 * The constructor arg is examined for public fields. Reflection is used to determine the type of
 * the field, and it's possible to indicate that a field isn't mandatory by using {@link Optional}
 * declarations. If the name of the property to be exposed to a build file does not match the field
 * name, then a {@link Hint} can be used to set it.
 * <p>
 * As an example, the arguments for a build target defined as:
 * <pre>
 *    example_library(
 *      name = 'example',
 *      srcs = [ '//foo:bar', 'Eggs.java', ],
 *      optional_thing = True,
 *    )
 * </pre>
 * Could be defined as a constructor arg that looks like:
 * <pre>
 *     public class ExampleLibraryArg {
 *       public String name;
 *       public ImmutableSortedSet&gt;SourcePath> srcs;
 *       @Hint(name = "optional_thing")
 *       public Optional&gt;Boolean> isNeeded;
 *     }
 * </pre>
 */
public class ConstructorArgMarshaller {

  private final Path basePath;

  /**
   * Constructor. {@code pathFromProjectRootToBuildFile} is the path relative to the project root to
   * the build file that has called the build rule's function in buck.py. This is used for resolving
   * additional paths to ones relative to the project root, and to allow {@link BuildTarget}
   * instances to be fully qualified.
   *
   * @param pathFromProjectRootToBuildFile The path from the root of the project to the directory of
   *     the build file that this function is being created from.
   */
  public ConstructorArgMarshaller(Path pathFromProjectRootToBuildFile) {
    Preconditions.checkNotNull(pathFromProjectRootToBuildFile);

    // Without this check an IndexOutOfBounds exception is thrown by normalize.
    if (pathFromProjectRootToBuildFile.toString().isEmpty()) {
      this.basePath = pathFromProjectRootToBuildFile;
    } else {
      this.basePath = pathFromProjectRootToBuildFile.normalize();
    }
  }

  /**
   * Use the information contained in the {@code params} to fill in the public fields and settable
   * properties of {@code dto}. The following rules are used:
   * <ul>
   *   <li>Boolean values are set to true or false.</li>
   *   <li>{@link BuildRule}s will be resovled.</li>
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
   * if none is set. This is typically {@link Optional#absent()}, but in the case of collections is
   * an empty collection.
   *
   * @param ruleResolver The resolver to use when looking up {@link BuildRule}s.
   * @param params The parameters to be used to populate the {@code dto} instance.
   * @param dto The constructor dto to be populated.
   */
  public void populate(BuildRuleResolver ruleResolver, BuildRuleFactoryParams params, Object dto) {
    Set<ParamInfo> allInfo = getAllParamInfo(dto);

    for (ParamInfo info : allInfo) {
      info.setFromParams(ruleResolver, dto, params);
    }
  }

  ImmutableSet<ParamInfo> getAllParamInfo(Object dto) {
    Class<?> argClass = dto.getClass();

    ImmutableSet.Builder<ParamInfo> allInfo = ImmutableSet.builder();

    for (Field field : argClass.getFields()) {
      if (Modifier.isFinal(field.getModifiers())) {
        continue;
      }
      allInfo.add(new ParamInfo(basePath, field));
    }

    return allInfo.build();
  }
}
