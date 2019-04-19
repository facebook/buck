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

package com.facebook.buck.intellij.ideabuck.completion;

import com.facebook.buck.intellij.ideabuck.lang.BuckLanguage;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckTypes;
import com.facebook.buck.intellij.ideabuck.util.BuckPsiUtils;
import com.google.common.collect.ImmutableList;
import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.util.ProcessingContext;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Auto-completion for keywords and rule names */
public class BuckCompletionContributor extends CompletionContributor {

  // TODO(#7908512): Need to pull those information from Buck.
  private static final ImmutableList<String> sPropertyNames =
      ImmutableList.of(
          "res",
          "binary_jar",
          "manifest",
          "manifest_skeleton",
          "package_type",
          "aar",
          "src_target",
          "src_roots",
          "java7_support",
          "source_under_test",
          "test_library_project_dir",
          "contacts",
          "excludes",
          "main",
          "javadoc_url",
          "store",
          "properties",
          "assets",
          "package",
          "proguard_config",
          "source_jar",
          "aidl",
          "import_path",
          "plugins",
          "keystore");

  // TODO(#7908529): Need to pull those information from Buck.
  private static final ImmutableList<String> sRuleNames =
      ImmutableList.of(
          "genrule",
          "remote_file",
          "android_aar",
          "android_binary",
          "android_build_config",
          "android_library",
          "android_manifest",
          "android_prebuilt_aar",
          "android_resource",
          "apk_genrule",
          "cxx_library",
          "gen_aidl",
          "ndk_library",
          "prebuilt_jar",
          "prebuilt_native_library",
          "project_config",
          "cxx_binary",
          "cxx_library",
          "cxx_test",
          "prebuilt_native_library",
          "d_binary",
          "d_library",
          "d_test",
          "cxx_library",
          "java_binary",
          "java_library",
          "java_test",
          "prebuilt_jar",
          "prebuilt_native_library",
          "prebuilt_python_library",
          "python_binary",
          "python_library",
          "python_test",
          "include_defs",
          "robolectric_test",
          "keystore");

  public BuckCompletionContributor() {
    // Auto completion for basic rule names.
    extend(
        CompletionType.BASIC,
        PlatformPatterns.psiElement(BuckTypes.IDENTIFIER_TOKEN).withLanguage(BuckLanguage.INSTANCE),
        BuckKeywordsCompletionProvider.INSTANCE);
  }

  private static class BuckKeywordsCompletionProvider
      extends CompletionProvider<CompletionParameters> {
    private static final BuckKeywordsCompletionProvider INSTANCE =
        new BuckKeywordsCompletionProvider();

    @Override
    protected void addCompletions(
        CompletionParameters parameters, ProcessingContext context, CompletionResultSet result) {
      Optional.of(parameters.getPosition())
          .map(PsiElement::getContainingFile)
          .map(psiFile -> BuckPsiUtils.findSymbolsInPsiTree(psiFile, ""))
          .map(Map::keySet)
          .map(Set::stream)
          .ifPresent(names -> names.map(LookupElementBuilder::create).forEach(result::addElement));
      for (String card : sPropertyNames) {
        result.addElement(LookupElementBuilder.create(card));
      }
      for (String card : sRuleNames) {
        result.addElement(LookupElementBuilder.create(card));
      }
    }
  }
}
