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
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;

import java.nio.file.Path;
import java.util.Map;

/**
 * A type coercer for a single build configuration in Apple targets. Expects either a path to an
 * existing xcconfig file, the target of a rule that outputs one, or a dictionary of xcode build
 * setting names to values.
 */
public class XcodeRuleConfigurationLayerTypeCoercer
    implements TypeCoercer<XcodeRuleConfigurationLayer> {

  private final TypeCoercer<SourcePath> fileTypeCoercer;
  private final TypeCoercer<ImmutableMap<String, String>> inlineSettingsTypeCoercer;

  public XcodeRuleConfigurationLayerTypeCoercer(
      TypeCoercer<SourcePath> fileTypeCoercer,
      TypeCoercer<ImmutableMap<String, String>> inlineSettingsTypeCoercer) {
    this.fileTypeCoercer = fileTypeCoercer;
    this.inlineSettingsTypeCoercer = inlineSettingsTypeCoercer;
  }

  @Override
  public Class<XcodeRuleConfigurationLayer> getOutputClass() {
    return XcodeRuleConfigurationLayer.class;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    return fileTypeCoercer.hasElementClass(types) ||
        inlineSettingsTypeCoercer.hasElementClass(types);
  }

  @Override
  public Optional<XcodeRuleConfigurationLayer> getOptionalValue() {
    return Optional.of(new XcodeRuleConfigurationLayer(ImmutableMap.<String, String>of()));
  }

  @Override
  public void traverse(XcodeRuleConfigurationLayer object, Traversal traversal) {
    traversal.traverse(object);
    switch (object.getLayerType()) {
      case FILE:
        fileTypeCoercer.traverse(object.getSourcePath().get(), traversal);
        break;
      case INLINE_SETTINGS:
        inlineSettingsTypeCoercer.traverse(object.getInlineSettings().get(), traversal);
        break;
    }
  }

  @Override
  public XcodeRuleConfigurationLayer coerce(
      BuildTargetParser buildTargetParser,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    if (object instanceof String) {
      SourcePath sourcePath = fileTypeCoercer.coerce(
          buildTargetParser,
          filesystem,
          pathRelativeToProjectRoot,
          object);
      return new XcodeRuleConfigurationLayer(sourcePath);
    } else if (object instanceof Map) {
      ImmutableMap<String, String> inlineSettings = inlineSettingsTypeCoercer.coerce(
          buildTargetParser,
          filesystem,
          pathRelativeToProjectRoot,
          object);
      return new XcodeRuleConfigurationLayer(inlineSettings);
    } else {
      throw CoerceFailedException.simple(
          object,
          getOutputClass(),
          "input object should be a string or a map");
    }
  }
}
