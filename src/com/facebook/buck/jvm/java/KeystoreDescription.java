/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorSet;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.shell.ExportFile;
import com.facebook.buck.shell.ExportFileDescription;
import com.facebook.buck.shell.ExportFileDirectoryAction;
import com.google.common.collect.ImmutableSet;

public class KeystoreDescription
    implements DescriptionWithTargetGraph<KeystoreDescriptionArg>, Flavored {

  static final Flavor PROPERTIES = InternalFlavor.of("properties");
  static final Flavor KEYSTORE = InternalFlavor.of("keystore");

  private static final ImmutableSet<Flavor> FLAVORS = ImmutableSet.of(PROPERTIES, KEYSTORE);
  private final JavaBuckConfig javaBuckConfig;

  public KeystoreDescription(JavaBuckConfig javaBuckConfig) {
    this.javaBuckConfig = javaBuckConfig;
  }

  @Override
  public Class<KeystoreDescriptionArg> getConstructorArgType() {
    return KeystoreDescriptionArg.class;
  }

  @Override
  public boolean hasFlavors(
      ImmutableSet<Flavor> flavors, TargetConfiguration toolchainTargetConfiguration) {
    if (!javaBuckConfig.useFlavorsForKeystore() && !flavors.isEmpty()) {
      throw new IllegalStateException("Flavors are not permitted for keystore, try named outputs!");
    }

    for (Flavor flavor : flavors) {
      if (!FLAVORS.contains(flavor)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      KeystoreDescriptionArg args) {

    FlavorSet flavors = buildTarget.getFlavors();
    if (!javaBuckConfig.useFlavorsForKeystore() && !flavors.isEmpty()) {
      throw new IllegalStateException("Flavors are not permitted for keystore, try named outputs!");
    }

    if (flavors.contains(PROPERTIES)) {
      return createExportFile(context, buildTarget, "keystore.properties", args.getProperties());
    } else if (flavors.contains(KEYSTORE)) {
      return createExportFile(context, buildTarget, "keystore.keystore", args.getStore());
    }

    return new Keystore(
        buildTarget, context.getProjectFilesystem(), params, args.getStore(), args.getProperties());
  }

  private ExportFile createExportFile(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      String name,
      SourcePath source) {
    return new ExportFile(
        buildTarget,
        context.getProjectFilesystem(),
        context.getActionGraphBuilder(),
        name,
        ExportFileDescription.Mode.REFERENCE,
        source,
        ExportFileDirectoryAction.FAIL);
  }

  @RuleArg
  interface AbstractKeystoreDescriptionArg extends BuildRuleArg, HasDeclaredDeps {
    SourcePath getStore();

    SourcePath getProperties();
  }
}
