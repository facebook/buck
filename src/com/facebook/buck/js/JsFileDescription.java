/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.js;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.shell.WorkerTool;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.collect.ImmutableSet;

import java.util.Optional;

public class JsFileDescription
    implements
    Description<JsFileDescription.Arg>,
    Flavored {

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> JsFile createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    final WorkerTool worker = resolver.getRuleWithType(args.worker, WorkerTool.class);
    return params.getBuildTarget().getFlavors().contains(JsFlavors.PROD)
        ? getJsFileProd(resolver, params, args, worker)
        : getJsFileDev(params, args, worker);
  }

  private <A extends Arg> JsFile.JsFileDev getJsFileDev(
      BuildRuleParams params,
      A args,
      WorkerTool worker) {
    return new JsFile.JsFileDev(
        params,
        args.src,
        args.virtualPath,
        args.extraArgs,
        worker);
  }

  private <A extends Arg> JsFile.JsFileProd getJsFileProd(
      BuildRuleResolver resolver,
      BuildRuleParams params,
      A args,
      WorkerTool worker) throws NoSuchBuildTargetException {
    final JsFile devFile = (JsFile) resolver.requireRule(params.getBuildTarget().withFlavors());

    return new JsFile.JsFileProd(
        params.appendExtraDeps(devFile),
        devFile,
        args.extraArgs,
        worker);
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return JsFlavors.validateFlavors(flavors);
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public Optional<String> extraArgs;
    public SourcePath src;
    public Optional<String> virtualPath;
    public BuildTarget worker;
  }
}
