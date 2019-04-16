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
package com.facebook.buck.intellij.ideabuck.lang;

import com.facebook.buck.intellij.ideabuck.lang.psi.BuckIdentifier;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementResolveResult;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReferenceBase;
import com.intellij.psi.ResolveResult;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Reference to a symbol in the Starlark grammar. */
public class BuckIdentifierReference extends PsiReferenceBase<PsiElement>
    implements PsiPolyVariantReference {

  private String key;

  public BuckIdentifierReference(@NotNull BuckIdentifier identifier) {
    this(identifier, identifier.getTextRange());
  }

  public BuckIdentifierReference(@NotNull PsiElement element, TextRange textRange) {
    super(element, textRange);
    key = element.getText().substring(textRange.getStartOffset(), textRange.getEndOffset());
  }

  @NotNull
  @Override
  public ResolveResult[] multiResolve(boolean incompleteCode) {
    Project project = myElement.getProject();
    ResolveResult[] results =
        BuckIdentifierUtil.findIdentifiers(project, key).stream()
            .map(PsiElementResolveResult::new)
            .toArray(ResolveResult[]::new);
    return results;
  }

  @Nullable
  @Override
  public PsiElement resolve() {
    ResolveResult[] resolveResults = multiResolve(false);
    return resolveResults.length == 1 ? resolveResults[0].getElement() : null;
  }

  @NotNull
  @Override
  public Object[] getVariants() {
    Project project = myElement.getProject();
    List<BuckIdentifier> identifiers = BuckIdentifierUtil.findIdentifiers(project);
    List<LookupElement> variants = new ArrayList<LookupElement>();
    for (BuckIdentifier identifier : identifiers) {
      if (identifier.getName() != null && identifier.getName().length() > 0) {
        variants.add(
            LookupElementBuilder.create(identifier)
                // .withIcon(BuckIcon.FILE)
                .withTypeText(identifier.getContainingFile().getName()));
      }
    }
    return variants.toArray();
  }
}
