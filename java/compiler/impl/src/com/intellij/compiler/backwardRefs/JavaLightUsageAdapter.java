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
package com.intellij.compiler.backwardRefs;

import com.intellij.ide.highlighter.JavaClassFileType;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.roots.impl.LibraryScopeCache;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.PsiFileWithStubSupport;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ClassInheritorsSearch;
import com.intellij.psi.util.ClassUtil;
import com.intellij.util.ObjectUtils;
import com.intellij.util.Processor;
import com.intellij.util.containers.ContainerUtil;
import com.sun.tools.javac.util.Convert;
import gnu.trove.TIntHashSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.backwardRefs.ByteArrayEnumerator;
import org.jetbrains.jps.backwardRefs.LightRef;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public class JavaLightUsageAdapter implements LanguageLightRefAdapter<PsiClass, PsiFunctionalExpression> {
  @NotNull
  @Override
  public Set<FileType> getFileTypes() {
    return ContainerUtil.set(JavaFileType.INSTANCE, JavaClassFileType.INSTANCE);
  }

  @Override
  public LightRef asLightUsage(@NotNull PsiElement element, @NotNull ByteArrayEnumerator names) {
    if (mayBeVisibleOutsideOwnerFile(element)) {
      if (element instanceof PsiField) {
        final PsiField field = (PsiField)element;
        final PsiClass aClass = field.getContainingClass();
        if (aClass == null || aClass instanceof PsiAnonymousClass) return null;
        final String jvmOwnerName = ClassUtil.getJVMClassName(aClass);
        final String name = field.getName();
        if (name == null || jvmOwnerName == null) return null;
        return new LightRef.JavaLightFieldRef(id(jvmOwnerName, names), id(name, names));
      }
      else if (element instanceof PsiMethod) {
        final PsiClass aClass = ((PsiMethod)element).getContainingClass();
        if (aClass == null || aClass instanceof PsiAnonymousClass) return null;
        final String jvmOwnerName = ClassUtil.getJVMClassName(aClass);
        if (jvmOwnerName == null) return null;
        final PsiMethod method = (PsiMethod)element;
        final String name = method.isConstructor() ? "<init>" : method.getName();
        final int parametersCount = method.getParameterList().getParametersCount();
        return new LightRef.JavaLightMethodRef(id(jvmOwnerName, names), id(name, names), parametersCount);
      }
      else if (element instanceof PsiClass) {
        final String jvmClassName = ClassUtil.getJVMClassName((PsiClass)element);
        if (jvmClassName != null) {
          return new LightRef.JavaLightClassRef(id(jvmClassName, names));
        }
      }
    }
    return null;
  }

  @NotNull
  @Override
  public List<LightRef> getHierarchyRestrictedToLibraryScope(@NotNull LightRef baseRef,
                                                             @NotNull PsiElement basePsi,
                                                             @NotNull ByteArrayEnumerator names, @NotNull GlobalSearchScope libraryScope) {
    final PsiClass baseClass = ObjectUtils.notNull(basePsi instanceof PsiClass ? (PsiClass)basePsi : ReadAction.compute(() -> (PsiMember)basePsi).getContainingClass());

    final List<LightRef> overridden = new ArrayList<>();
    Processor<PsiClass> processor = c -> {
      if (c.hasModifierProperty(PsiModifier.PRIVATE)) return true;
      String qName = ReadAction.compute(() -> c.getQualifiedName());
      if (qName == null) return true;
      overridden.add(baseRef.override(id(qName, names)));
      return true;
    };

    ClassInheritorsSearch.search(baseClass, LibraryScopeCache.getInstance(baseClass.getProject()).getLibrariesOnlyScope(), true).forEach(processor);
    return overridden;

  }

  @NotNull
  @Override
  public Class<? extends LightRef.LightClassHierarchyElementDef> getHierarchyObjectClass() {
    return LightRef.JavaLightClassRef.class;
  }

  @NotNull
  @Override
  public Class<? extends LightRef> getFunExprClass() {
    return LightRef.JavaLightFunExprDef.class;
  }

  @NotNull
  @Override
  public PsiClass[] findDirectInheritorCandidatesInFile(@NotNull Collection<LightRef.LightClassHierarchyElementDef> classes,
                                                        @NotNull ByteArrayEnumerator byteArrayEnumerator,
                                                        @NotNull PsiFileWithStubSupport file,
                                                        @NotNull PsiNamedElement superClass) {
    String[] internalNames = classes.stream().map(LightRef.NamedLightRef::getName).map(byteArrayEnumerator::getName).toArray(String[]::new);
    return JavaCompilerElementRetriever.retrieveClassesByInternalNames(internalNames, superClass, file);
  }

  @NotNull
  @Override
  public PsiFunctionalExpression[] findFunExpressionsInFile(@NotNull Collection<LightRef.LightFunExprDef> funExpressions,
                                                            @NotNull PsiFileWithStubSupport file) {
    TIntHashSet requiredIndices = new TIntHashSet(funExpressions.size());
    for (LightRef.LightFunExprDef funExpr : funExpressions) {
      requiredIndices.add(funExpr.getId());
    }
    return JavaCompilerElementRetriever.retrieveFunExpressionsByIndices(requiredIndices, file);
  }

  private static boolean mayBeVisibleOutsideOwnerFile(@NotNull PsiElement element) {
    if (!(element instanceof PsiModifierListOwner)) return true;
    if (((PsiModifierListOwner)element).hasModifierProperty(PsiModifier.PRIVATE)) return false;
    return true;
  }

  private static int id(String name, ByteArrayEnumerator names) {
    return names.enumerate(Convert.string2utf(name));
  }
}
