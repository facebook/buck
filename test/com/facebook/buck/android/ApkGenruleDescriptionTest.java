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

package com.facebook.buck.android;

import static org.junit.Assert.assertThat;

import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.FakeBuildRule;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.FakeTargetNodeBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.testutil.TargetGraphFactory;
import java.nio.file.Paths;
import org.hamcrest.Matchers;
import org.junit.Test;

public class ApkGenruleDescriptionTest {

  @Test
  public void testClasspathTransitiveDepsBecomeFirstOrderDeps() throws Exception {
    SourcePathResolver emptyPathResolver =
        new SourcePathResolver(
            new SourcePathRuleFinder(
                new BuildRuleResolver(
                    TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())));

    BuildTarget installableApkTarget = BuildTargetFactory.newInstance("//:installable");
    TargetNode<?, ?> installableApkNode =
        FakeTargetNodeBuilder.build(new FakeInstallable(installableApkTarget, emptyPathResolver));
    TargetNode<?, ?> transitiveDepNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//exciting:dep"))
            .addSrc(Paths.get("Dep.java"))
            .build();
    TargetNode<?, ?> depNode =
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//exciting:target"))
            .addSrc(Paths.get("Other.java"))
            .addDep(transitiveDepNode.getBuildTarget())
            .build();
    TargetNode<?, ?> genruleNode =
        ApkGenruleBuilder.create(BuildTargetFactory.newInstance("//:rule"))
            .setOut("out")
            .setCmd("$(classpath //exciting:target)")
            .setApk(installableApkTarget)
            .build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(installableApkNode, transitiveDepNode, depNode, genruleNode);
    BuildRuleResolver resolver =
        new BuildRuleResolver(targetGraph, new DefaultTargetNodeToBuildRuleTransformer());

    BuildRule transitiveDep = resolver.requireRule(transitiveDepNode.getBuildTarget());
    BuildRule dep = resolver.requireRule(depNode.getBuildTarget());
    BuildRule genrule = resolver.requireRule(genruleNode.getBuildTarget());

    assertThat(genrule.getBuildDeps(), Matchers.hasItems(dep, transitiveDep));
  }

  private static class FakeInstallable extends FakeBuildRule implements HasInstallableApk {

    SourcePath apkPath =
        new ExplicitBuildTargetSourcePath(getBuildTarget(), Paths.get("buck-out", "app.apk"));

    public FakeInstallable(BuildTarget buildTarget, SourcePathResolver resolver) {
      super(buildTarget, resolver);
    }

    @Override
    public ApkInfo getApkInfo() {
      return ApkInfo.builder()
          .setApkPath(apkPath)
          .setManifestPath(new FakeSourcePath("nothing"))
          .build();
    }

    @Override
    public SourcePath getSourcePathToOutput() {
      return apkPath;
    }
  }
}
