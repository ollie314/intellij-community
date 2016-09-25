/*
 * Copyright 2003-2016 Dave Griffith, Bas Leijdekkers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.siyeh.ig.style;

import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import com.siyeh.InspectionGadgetsBundle;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.InspectionGadgetsFix;
import com.siyeh.ig.PsiReplacementUtil;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ControlFlowStatementWithoutBracesInspection
  extends BaseInspection {

  @Override
  @NotNull
  public String getDisplayName() {
    return InspectionGadgetsBundle.message(
      "control.flow.statement.without.braces.display.name");
  }

  @Override
  @NotNull
  protected String buildErrorString(Object... infos) {
    return InspectionGadgetsBundle.message(
      "control.flow.statement.without.braces.problem.descriptor", infos);
  }

  @Override
  public InspectionGadgetsFix buildFix(Object... infos) {
    if (infos.length == 1 && infos[0] instanceof String) {
      return new ControlFlowStatementFix((String)infos[0]);
    }
    return null;
  }

  private static class ControlFlowStatementFix extends InspectionGadgetsFix {
    private final String myKeywordText;

    private ControlFlowStatementFix(String keywordText) {
      myKeywordText = keywordText;
    }

    @Override
    @NotNull
    public String getName() {
      return InspectionGadgetsBundle.message(
        "control.flow.statement.without.braces.message", myKeywordText);
    }

    @Override
    @NotNull
    public String getFamilyName() {
      return InspectionGadgetsBundle.message(
        "control.flow.statement.without.braces.add.quickfix");
    }

    @Override
    protected void doFix(Project project, ProblemDescriptor descriptor)
      throws IncorrectOperationException {
      final PsiElement element = descriptor.getStartElement();
      final PsiElement parent = element.getParent();
      final PsiStatement statement;
      if (element instanceof PsiStatement) {
        statement = (PsiStatement)element;
      }
      else if ((parent instanceof PsiStatement)) {
        statement = (PsiStatement)parent;
      }
      else {
        return;
      }
      final PsiStatement statementWithoutBraces;
      if (statement instanceof PsiLoopStatement) {
        final PsiLoopStatement loopStatement =
          (PsiLoopStatement)statement;
        statementWithoutBraces = loopStatement.getBody();
      }
      else if (statement instanceof PsiIfStatement) {
        final PsiIfStatement ifStatement = (PsiIfStatement)statement;
        if (element == ifStatement.getElseElement()) {
          statementWithoutBraces = ifStatement.getElseBranch();
        }
        else {
          statementWithoutBraces = ifStatement.getThenBranch();
          if (statementWithoutBraces == null) {
            return;
          }
          final PsiElement nextSibling =
            statementWithoutBraces.getNextSibling();
          if (nextSibling instanceof PsiWhiteSpace) {
            // to avoid "else" on new line
            nextSibling.delete();
          }
        }
      }
      else {
        return;
      }
      if (statementWithoutBraces == null) {
        return;
      }
      final String newStatementText =
        "{\n" + statementWithoutBraces.getText() + "\n}";
      PsiReplacementUtil.replaceStatement(statementWithoutBraces, newStatementText);
    }
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new ControlFlowStatementVisitor(this);
  }

  private static class ControlFlowStatementVisitor extends ControlFlowStatementVisitorBase {
    private ControlFlowStatementVisitor(BaseInspection inspection) {
      super(inspection);
    }

    @Contract("null->false")
    @Override
    protected boolean isApplicable(PsiStatement body) {
      if (body instanceof PsiIfStatement && isHighlightOnlyKeyword(body)) {
        final PsiElement parent = body.getParent();
        if (parent instanceof PsiIfStatement) {
          final PsiIfStatement ifStatement = (PsiIfStatement)parent;
          if (ifStatement.getElseBranch() == body) {
            return false;
          }
        }
      }
      return body != null && !(body instanceof PsiBlockStatement);
    }

    @Nullable
    @Override
    protected Pair<PsiElement, PsiElement> getOmittedBodyBounds(PsiStatement body) {
      if (body instanceof PsiLoopStatement || body instanceof PsiIfStatement) {
        final PsiElement lastChild = body.getLastChild();
        return Pair.create(PsiTreeUtil.skipSiblingsBackward(body, PsiWhiteSpace.class, PsiComment.class),
                           lastChild instanceof PsiJavaToken && ((PsiJavaToken)lastChild).getTokenType() == JavaTokenType.SEMICOLON
                           ? lastChild
                           : null);
      }
      return null;
    }
  }
}