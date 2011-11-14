/*
 * Copyright 2009 Google Inc.
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

package com.google.common.css.compiler.passes;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.css.compiler.ast.CssAtRuleNode;
import com.google.common.css.compiler.ast.CssAtRuleNode.Type;
import com.google.common.css.compiler.ast.CssBlockNode;
import com.google.common.css.compiler.ast.CssCompilerPass;
import com.google.common.css.compiler.ast.CssCompositeValueNode;
import com.google.common.css.compiler.ast.CssConditionalBlockNode;
import com.google.common.css.compiler.ast.CssDeclarationBlockNode;
import com.google.common.css.compiler.ast.CssFunctionNode;
import com.google.common.css.compiler.ast.CssImportRuleNode;
import com.google.common.css.compiler.ast.CssLiteralNode;
import com.google.common.css.compiler.ast.CssMediaRuleNode;
import com.google.common.css.compiler.ast.CssNode;
import com.google.common.css.compiler.ast.CssPageRuleNode;
import com.google.common.css.compiler.ast.CssPageSelectorNode;
import com.google.common.css.compiler.ast.CssRulesetNode;
import com.google.common.css.compiler.ast.CssUnknownAtRuleNode;
import com.google.common.css.compiler.ast.CssValueNode;
import com.google.common.css.compiler.ast.DefaultTreeVisitor;
import com.google.common.css.compiler.ast.ErrorManager;
import com.google.common.css.compiler.ast.GssError;
import com.google.common.css.compiler.ast.MutatingVisitController;

import java.util.List;
import java.util.Set;

/**
 * A compiler pass that transforms standard {@link CssUnknownAtRuleNode}s
 * to more specific at-rule nodes, or deletes them.
 *
 */
public class CreateStandardAtRuleNodes extends DefaultTreeVisitor implements CssCompilerPass {
  @VisibleForTesting
  static final String NO_BLOCK_ERROR_MESSAGE =
    "This @-rule has to have a block";
  @VisibleForTesting
  static final String BLOCK_ERROR_MESSAGE =
    "This @-rule is not allowed to have a block";
  @VisibleForTesting
  static final String ONLY_DECLARATION_BLOCK_ERROR_MESSAGE =
    "Only declaration blocks are allowed for this @-rule";
  @VisibleForTesting
  static final String INVALID_PARAMETERS_ERROR_MESSAGE =
    "This @-rule has invalid parameters";

  @VisibleForTesting
  static final String MEDIA_INVALID_CHILD_ERROR_MESSAGE =
    "This is not valid inside an @media block";
  @VisibleForTesting
  static final String MEDIA_WITHOUT_PARAMETERS_ERROR_MESSAGE =
    "@media without parameters";

  @VisibleForTesting
  static final String PAGE_SELECTOR_PARAMETERS_ERROR_MESSAGE =
    "Page selectors are not allowed to have parameters";

  private static final List<Type> PAGE_SELECTORS =
    ImmutableList.of(Type.TOP_LEFT_CORNER,
        Type.TOP_LEFT, Type.TOP_CENTER, Type.TOP_RIGHT, Type.TOP_RIGHT_CORNER,
        Type.LEFT_TOP, Type.LEFT_MIDDLE, Type.LEFT_BOTTOM, Type.RIGHT_TOP,
        Type.RIGHT_MIDDLE, Type.RIGHT_BOTTOM, Type.BOTTOM_LEFT_CORNER,
        Type.BOTTOM_LEFT, Type.BOTTOM_CENTER, Type.BOTTOM_RIGHT,
        Type.BOTTOM_RIGHT_CORNER);
  private static final Set<String> PSEUDO_PAGES = ImmutableSet.of(
      ":left", ":right", ":first");
  // The @-rules are restricted because it only makes sense for them to be used
  // inside an @media block. Especially @def should never be allowed because it
  // can be misleading; @def rules are processed by the compiler but @media
  // rules are handled by the browser.
  private static final Set<String> ALLOWED_AT_RULES_IN_MEDIA = ImmutableSet.of(
      "page", "if", "elseif", "else");

  private final MutatingVisitController visitController;
  private final ErrorManager errorManager;

  public CreateStandardAtRuleNodes(MutatingVisitController visitController,
                                   ErrorManager errorManager) {
    this.visitController = visitController;
    this.errorManager = errorManager;
  }

  @Override
  public boolean enterUnknownAtRule(CssUnknownAtRuleNode node) {
    String charsetName = CssAtRuleNode.Type.CHARSET.getCanonicalName();
    String importName = CssAtRuleNode.Type.IMPORT.getCanonicalName();
    String mediaName = CssAtRuleNode.Type.MEDIA.getCanonicalName();
    String pageName = CssAtRuleNode.Type.PAGE.getCanonicalName();

    List<CssValueNode> params = node.getParameters();

    if (node.getName().getValue().equals(charsetName)) {
      // We don't have a specific node class for this, should be handled at the parser level.
      // TODO(user): Give a warning instead that the node has been removed without processing. (?)
      reportError("@" + charsetName + " removed", node);
      return false;

    } else if (node.getName().getValue().equals(importName)) {
      if (params.isEmpty()) {
        reportError("@" + importName + " without a following string or uri", node);
        return false;
      }
      if (params.size() > 2) {
        reportError("@" + importName + " with too many parameters", node);
        return false;
      }
      CssValueNode param = params.get(0);
      if (!(checkIfString(param) || checkIfUri(param))) {
        reportError("@" + importName + "'s first parameter has to be a string or an url", node);
        return false;
      }
      List<CssValueNode> paramlist = Lists.newArrayList(param);
      if (params.size() == 2) {
        CssValueNode param2 = params.get(1);
        if (param2 instanceof CssCompositeValueNode || param2 instanceof CssLiteralNode) {
          paramlist.add(param2);
        } else {
          reportError("@" + importName + " has illegal parameter", node);
          return false;
        }
      }
      CssImportRuleNode importRule = new CssImportRuleNode(node.getComments());
      importRule.setParameters(paramlist);
      importRule.setSourceCodeLocation(node.getSourceCodeLocation());
      visitController.replaceCurrentBlockChildWith(Lists.newArrayList((CssNode) importRule), false);
      return false;

    } else if (node.getName().getValue().equals(mediaName)) {
      createMediaRule(node);
    } else if (node.getName().getValue().equals(pageName)) {
      createPageRule(node);
    } else {
      for (Type type : PAGE_SELECTORS) {
        if (node.getName().getValue().equals(type.getCanonicalName())) {
          createPageSelector(type, node);
          break;
        }
      }
    }
    return true;
  }

  /**
   * See the
   * <a href="http://www.w3.org/TR/CSS21/grammar.html#grammar">CSS 2.1 syntax
   * </a>, and
   * <a href="http://www.w3.org/TR/css3-mediaqueries/#syntax">CSS 3 syntax</a>
   * for more information.
   */
  private void createMediaRule(CssUnknownAtRuleNode node) {
    if (node.getBlock() == null) {
      reportError(NO_BLOCK_ERROR_MESSAGE, node);
      return;
    }

    if (!(node.getBlock() instanceof CssBlockNode)) {
      reportError(ONLY_DECLARATION_BLOCK_ERROR_MESSAGE, node);
      return;
    }

    CssBlockNode block = (CssBlockNode) node.getBlock();
    for (CssNode part : block.childIterable()) {
      if (!isValidInMediaRule(part)) {
        reportError(MEDIA_INVALID_CHILD_ERROR_MESSAGE, part);
        return;
      }
    }

    List<CssValueNode> params = node.getParameters();
    if (params.isEmpty()) {
      reportError(MEDIA_WITHOUT_PARAMETERS_ERROR_MESSAGE, node);
      return;
    }

    // TODO(user): Perform this check depending on the CSS version set in the
    // options
    if (!checkMediaParameter(params)) {
      reportError(INVALID_PARAMETERS_ERROR_MESSAGE, node);
      return;
    }
    CssMediaRuleNode mediaRule = new CssMediaRuleNode(node.getComments(),
        block);
    mediaRule.setParameters(params);
    mediaRule.setSourceCodeLocation(node.getSourceCodeLocation());
    visitController.replaceCurrentBlockChildWith(
        Lists.newArrayList(mediaRule), true /* visitTheReplacementNodes */);
  }

  /**
   * Checks whether the parameters of a @media rule match the grammar of the
   * CSS 3 draft.
   */
  private boolean checkMediaParameter(List<CssValueNode> params) {
    if (params.get(0) instanceof CssCompositeValueNode) {
      return checkMediaCompositeExpression(params, 0);
    } else if (!(params.get(0) instanceof CssLiteralNode)) {
      // nodes like the function node are invalid
      return false;
    }
    int numberOfStartingLiterals = 1;
    String firstValue = params.get(0).getValue();
    if (firstValue.equals("only") || firstValue.equals("not")) {
      numberOfStartingLiterals = 2;
    }
    if (params.size() < numberOfStartingLiterals) {
      // only 'only' or 'not'
      return false;
    }
    if (params.size() - numberOfStartingLiterals > 0) {
      return checkMediaExpression(params, numberOfStartingLiterals);
    }
    return true;
  }

  /**
   * Checks for [ AND S* expression ]* where expression is some value.
   */
  private boolean checkMediaExpression(List<CssValueNode> params, int start) {
    if (params.size() - start < 2) {
      // at least two more: 'and X'
      return false;
    }
    if (!params.get(start).getValue().equals("and")) {
      // expected 'and'
      return false;
    }
    if (params.get(start + 1) instanceof CssCompositeValueNode) {
      return checkMediaCompositeExpression(params, start + 1);
    } else if (params.size() > start + 2) {
      return checkMediaExpression(params, start + 2);
    }
    return true;
  }

  /**
   * Splits up a composite value so that it can be checked. Two values with
   * a comma in between are combined to a composite value.
   * For example:
   * {@code screen and (device-width:800px), print}
   * turns into this list of values:
   * {@code screen}, {@code and}, {@code (device-width:800px), print}
   * where the last value is a composite value that consists of two values.
   */
  private boolean checkMediaCompositeExpression(List<CssValueNode> params,
      int start) {
    CssCompositeValueNode comp = (CssCompositeValueNode) params.get(start);
    CssValueNode startValue;
    if (comp.getValues().size() == 2) {
      startValue = comp.getValues().get(1);
    } else {
      List<CssValueNode> newChildren = comp.getValues();
      newChildren.remove(0);
      startValue = new CssCompositeValueNode(newChildren,
          comp.getOperator(), comp.getSourceCodeLocation());
    }
    List<CssValueNode> newParams = Lists.newArrayList(startValue);
    for (int i = 0; i < params.size(); i++) {
      if (i > start) {
        newParams.add(params.get(i));
      }
    }
    return checkMediaParameter(newParams);
  }

  private boolean isValidInMediaRule(CssNode node) {
    if (node instanceof CssRulesetNode) {
      // rulesets like .CLASS { ... }
      return true;
    }
    if (node instanceof CssAtRuleNode && ALLOWED_AT_RULES_IN_MEDIA.contains(
        ((CssAtRuleNode) node).getName().getValue())) {
      // like @page or @if, but not @def
      return true;
    }
    if (node instanceof CssConditionalBlockNode) {
      // @if, @elseif, @else after processing because then they are not @-rules
      // anymore
      return true;
    }
    return false;
  }

  /**
   * See the
   * <a href="http://www.w3.org/TR/css3-page/#syntax-page-selector">
   * Page selector grammar</a> for more information.
   */
  private void createPageRule(CssUnknownAtRuleNode node) {
    if (node.getBlock() == null) {
      reportError(NO_BLOCK_ERROR_MESSAGE, node);
      return;
    }
    if (!(node.getBlock() instanceof CssDeclarationBlockNode)) {
      reportError(ONLY_DECLARATION_BLOCK_ERROR_MESSAGE, node);
      return;
    }
    List<CssValueNode> params = node.getParameters();
    int numParams = params.size();
    if (numParams > 2) {
      reportError(INVALID_PARAMETERS_ERROR_MESSAGE, node);
      return;
    } else if (numParams == 2
          && !PSEUDO_PAGES.contains(params.get(1).getValue())) {
      reportError(INVALID_PARAMETERS_ERROR_MESSAGE, node);
      return;
    } else if (numParams == 1
        && !PSEUDO_PAGES.contains(params.get(0).getValue())) {
      reportError(INVALID_PARAMETERS_ERROR_MESSAGE, node);
      return;
    }
    CssDeclarationBlockNode block = (CssDeclarationBlockNode) node.getBlock();
    CssPageRuleNode pageRule = new CssPageRuleNode(node.getComments(),
        block);
    pageRule.setParameters(params);
    pageRule.setSourceCodeLocation(node.getSourceCodeLocation());
    visitController.replaceCurrentBlockChildWith(
        Lists.newArrayList(pageRule), true /* visitTheReplacementNodes */);
  }

  private void createPageSelector(Type type, CssUnknownAtRuleNode node) {
    if (node.getBlock() == null) {
      reportError(NO_BLOCK_ERROR_MESSAGE, node);
      return;
    }
    if (!(node.getBlock() instanceof CssDeclarationBlockNode)) {
      reportError(ONLY_DECLARATION_BLOCK_ERROR_MESSAGE, node);
      return;
    }
    if (!node.getParameters().isEmpty()) {
      reportError(PAGE_SELECTOR_PARAMETERS_ERROR_MESSAGE, node);
      return;
    }
    CssDeclarationBlockNode block = (CssDeclarationBlockNode) node.getBlock();
    CssPageSelectorNode pageSelector = new CssPageSelectorNode(type,
        node.getComments(), block);
    pageSelector.setSourceCodeLocation(node.getSourceCodeLocation());
    visitController.replaceCurrentBlockChildWith(
        Lists.newArrayList(pageSelector), true /* visitTheReplacementNodes */);
  }

  private boolean checkIfString(CssValueNode node) {
    if (!(node instanceof CssLiteralNode)) {
      return false;
    }
    CssLiteralNode literal = (CssLiteralNode)node;
    String value = literal.getValue();
    if (value.startsWith("\"") && value.endsWith("\"")
        || value.startsWith("'") && value.endsWith("'")) {
      return true;
    }
    return false;
  }

  private boolean checkIfUri(CssValueNode node) {
    if (!(node instanceof CssFunctionNode)) {
      return false;
    }
    CssFunctionNode function = (CssFunctionNode)node;
    if (function.getFunctionName().toLowerCase().equals("url")) {
      return true;
    }
    return false;
  }

  private void reportError(String message, CssNode node) {
    errorManager.report(new GssError(message, node.getSourceCodeLocation()));
    visitController.removeCurrentNode();
  }

  @Override
  public void runPass() {
    visitController.startVisit(this);
  }

}