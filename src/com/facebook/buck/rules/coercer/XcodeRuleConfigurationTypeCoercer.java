/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

/**
 * A type coercer for a list of build configurations in Apple targets. Expects either a list of
 * objects or a single object, suitable to be coerced by
 * {@link XcodeRuleConfigurationLayerTypeCoercer}. Each resulting layer is going to be applied
 * sequentially.
 */
public class XcodeRuleConfigurationTypeCoercer implements TypeCoercer<XcodeRuleConfiguration> {

  private final TypeCoercer<XcodeRuleConfigurationLayer> layerTypeCoercer;

  public XcodeRuleConfigurationTypeCoercer(
      TypeCoercer<XcodeRuleConfigurationLayer> layerTypeCoercer) {
    this.layerTypeCoercer = layerTypeCoercer;
  }

  @Override
  public Class<XcodeRuleConfiguration> getOutputClass() {
    return XcodeRuleConfiguration.class;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return layerTypeCoercer.hasElementClass(types);
  }

  @Override
  public Optional<XcodeRuleConfiguration> getOptionalValue() {
    return Optional.of(new XcodeRuleConfiguration(ImmutableList.<XcodeRuleConfigurationLayer>of()));
  }

  @Override
  public void traverse(XcodeRuleConfiguration object, Traversal traversal) {
    traversal.traverse(object);
    for (XcodeRuleConfigurationLayer layer : object.getLayers()) {
      layerTypeCoercer.traverse(layer, traversal);
    }
  }

  @Override
  public XcodeRuleConfiguration coerce(
      BuildTargetParser buildTargetParser,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    if (object instanceof Collection) {
      Collection<?> collection = (Collection<?>) object;
      ImmutableList.Builder<XcodeRuleConfigurationLayer> layers = ImmutableList.builder();
      for (Object element : collection) {
        layers.add(
            layerTypeCoercer.coerce(
                buildTargetParser,
                filesystem,
                pathRelativeToProjectRoot,
                element));
      }
      return new XcodeRuleConfiguration(layers.build());
    } else if (object instanceof Map) {
      return new XcodeRuleConfiguration(
          ImmutableList.of(
              layerTypeCoercer.coerce(
                  buildTargetParser,
                  filesystem,
                  pathRelativeToProjectRoot,
                  object)));
    } else {
      throw CoerceFailedException.simple(
          object,
          getOutputClass(),
          "input object should be a collection or a map");
    }
  }
}
