/*
 * Copyright 2000-2016 JetBrains s.r.o.
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
package org.jetbrains.plugins.groovy.codeInspection.fixes;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.util.Function;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

public class RemoveElementQuickFix implements LocalQuickFix {

  private final String myName;
  private final Function<PsiElement, PsiElement> myElementFunction;

  public RemoveElementQuickFix(@NotNull String name, @NotNull Function<PsiElement, PsiElement> function) {
    myName = name;
    myElementFunction = function;
  }

  @Nls
  @NotNull
  @Override
  public String getFamilyName() {
    return myName;
  }

  @Override
  public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
    PsiElement descriptorElement = descriptor.getPsiElement();
    if (descriptorElement == null) return;

    PsiElement elementToRemove = myElementFunction.fun(descriptorElement);
    if (elementToRemove == null) return;

    elementToRemove.delete();
  }
}
