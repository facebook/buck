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

package com.facebook.buck.intellij.plugin.format;

import com.facebook.buck.intellij.plugin.lang.psi.BuckArrayElements;
import com.facebook.buck.intellij.plugin.lang.psi.BuckListElements;
import com.facebook.buck.intellij.plugin.lang.psi.BuckRuleBody;
import com.facebook.buck.intellij.plugin.lang.psi.BuckTypes;
import com.intellij.formatting.ASTBlock;
import com.intellij.formatting.Alignment;
import com.intellij.formatting.Block;
import com.intellij.formatting.ChildAttributes;
import com.intellij.formatting.Indent;
import com.intellij.formatting.Spacing;
import com.intellij.formatting.SpacingBuilder;
import com.intellij.formatting.Wrap;
import com.intellij.formatting.WrapType;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.facebook.buck.intellij.plugin.lang.psi.BuckPsiUtils.hasElementType;

/**
 * Describes a single AST block, used for reformatting.
 */
public class BuckBlock implements ASTBlock {

  private static final TokenSet BUCK_CONTAINERS =
      TokenSet.create(BuckTypes.ARRAY_ELEMENTS, BuckTypes.RULE_BODY, BuckTypes.LIST_ELEMENTS);
  private static final TokenSet BUCK_OPEN_BRACES =
      TokenSet.create(BuckTypes.L_BRACKET, BuckTypes.L_PARENTHESES);
  private static final TokenSet BUCK_CLOSE_BRACES =
      TokenSet.create(BuckTypes.R_BRACKET, BuckTypes.R_PARENTHESES);
  private static final TokenSet BUCK_ALL_BRACES =
      TokenSet.orSet(BUCK_OPEN_BRACES, BUCK_CLOSE_BRACES);

  private final Alignment myAlignment;
  private final Indent myIndent;
  private final PsiElement myPsiElement;
  private final ASTNode myNode;
  private final Wrap myWrap;
  private final Wrap myChildWrap;
  private final CodeStyleSettings mySettings;
  private final SpacingBuilder mySpacingBuilder;

  private List<BuckBlock> mySubBlocks = null;

  public BuckBlock(
      final ASTNode node,
      CodeStyleSettings settings,
      @Nullable final Alignment alignment,
      final Indent indent,
      @Nullable final Wrap wrap) {
    myAlignment = alignment;
    myIndent = indent;
    myNode = node;
    myPsiElement = node.getPsi();
    myWrap = wrap;
    mySettings = settings;

    mySpacingBuilder = BuckFormattingModelBuilder.createSpacingBuilder(settings);

    if (myPsiElement instanceof BuckArrayElements ||
        myPsiElement instanceof BuckRuleBody ||
        myPsiElement instanceof BuckListElements) {
      myChildWrap = Wrap.createWrap(CommonCodeStyleSettings.WRAP_ALWAYS, true);
    } else {
      myChildWrap = null;
    }
  }

  @Override
  public ASTNode getNode() {
    return myNode;
  }

  @Override
  public TextRange getTextRange() {
    return myNode.getTextRange();
  }

  @Override
  public List<Block> getSubBlocks() {
    if (mySubBlocks == null) {
      mySubBlocks = buildSubBlocks();
    }
    return new ArrayList<Block>(mySubBlocks);
  }

  /**
   * Recursively build sub blocks.
   */
  private List<BuckBlock> buildSubBlocks() {
    final List<BuckBlock> blocks = new ArrayList<BuckBlock>();
    for (ASTNode child = myNode.getFirstChildNode(); child != null; child = child.getTreeNext()) {
      final IElementType childType = child.getElementType();

      if (child.getTextRange().isEmpty()) {
        continue;
      }
      if (childType == TokenType.WHITE_SPACE) {
        continue;
      }
      // In most cases, glob block doesn't need reformatting. So just ignore it for now.
      if (childType == BuckTypes.GLOB_BLOCK) {
        continue;
      }
      blocks.add(buildSubBlock(child));
    }
    return Collections.unmodifiableList(blocks);
  }

  private BuckBlock buildSubBlock(ASTNode childNode) {
    Indent indent = Indent.getNoneIndent();
    Alignment alignment = null;
    Wrap wrap = null;

    if (hasElementType(myNode, BUCK_CONTAINERS)) {
      if (hasElementType(childNode, BuckTypes.COMMA)) {
        wrap = Wrap.createWrap(WrapType.NONE, true);
      } else if (!hasElementType(childNode, BUCK_ALL_BRACES)) {
        assert myChildWrap != null;
        wrap = myChildWrap;
        indent = Indent.getNormalIndent();
      }
    }
    return new BuckBlock(childNode, mySettings, alignment, indent, wrap);
  }

  @Nullable
  @Override
  public Wrap getWrap() {
    return myWrap;
  }

  @Nullable
  @Override
  public Indent getIndent() {
    assert myIndent != null;
    return myIndent;
  }

  @Nullable
  @Override
  public Alignment getAlignment() {
    return myAlignment;
  }

  @Nullable
  @Override
  public Spacing getSpacing(@Nullable Block child1, Block child2) {
    return mySpacingBuilder.getSpacing(this, child1, child2);
  }

  @Override
  public ChildAttributes getChildAttributes(int newChildIndex) {
    if (hasElementType(myNode, BUCK_CONTAINERS)) {
      return new ChildAttributes(Indent.getNormalIndent(), null);
    } else if (myNode.getPsi() instanceof PsiFile) {
      return new ChildAttributes(Indent.getNoneIndent(), null);
    } else {
      return new ChildAttributes(null, null);
    }
  }

  @Override
  public boolean isIncomplete() {
    final ASTNode lastChildNode = myNode.getLastChildNode();

    boolean ret = false;
    if (hasElementType(myNode, TokenSet.create(BuckTypes.ARRAY_ELEMENTS))) {
      ret = lastChildNode != null && lastChildNode.getElementType() != BuckTypes.R_BRACKET;
    } else if (hasElementType(myNode, TokenSet.create(BuckTypes.RULE_BODY))) {
      ret = lastChildNode != null && lastChildNode.getElementType() != BuckTypes.R_PARENTHESES;
    } else if (hasElementType(myNode, TokenSet.create(BuckTypes.LIST_ELEMENTS))) {
      ret = lastChildNode != null && lastChildNode.getElementType() != BuckTypes.R_PARENTHESES;
    }
    return ret;
  }

  @Override
  public boolean isLeaf() {
    return myNode.getFirstChildNode() == null;
  }
}
