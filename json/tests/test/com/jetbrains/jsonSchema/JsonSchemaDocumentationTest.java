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
package com.jetbrains.jsonSchema;

import com.intellij.codeInsight.documentation.DocumentationManager;
import com.intellij.json.JsonLanguage;
import com.intellij.lang.LanguageDocumentation;
import com.intellij.lang.documentation.DocumentationProvider;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixtureTestCase;
import com.jetbrains.jsonSchema.ide.JsonSchemaDocumentationProvider;
import org.jetbrains.annotations.NotNull;

import java.io.FileReader;


public class JsonSchemaDocumentationTest extends LightPlatformCodeInsightFixtureTestCase {
  @Override
  protected boolean isCommunity() {
    return true;
  }

  @Override
  protected String getBasePath() {
    return "/json/tests/testData/jsonSchema/documentation";
  }

  public void testSimple() throws Exception {
    doTest(true);
  }

  public void testSecondLevel() throws Exception {
    doTest(true);
  }


  private void doTest(boolean hasDoc) throws Exception {
    String pathToSchema = getTestDataPath() + "/" + getTestName(true) + "_schema.json";
    String schemaText = FileUtil.loadTextAndClose(new FileReader(pathToSchema));
    JsonSchemaHighlightingTest.registerProvider(getProject(), schemaText);
    final JsonSchemaDocumentationProvider provider = new JsonSchemaDocumentationProvider();
    LanguageDocumentation.INSTANCE.addExplicitExtension(JsonLanguage.INSTANCE, provider);
    Disposer.register(getTestRootDisposable(), new Disposable() {
      @Override
      public void dispose() {
        LanguageDocumentation.INSTANCE.removeExplicitExtension(JsonLanguage.INSTANCE, provider);
        JsonSchemaTestServiceImpl.setProvider(null);
      }
    });
    myFixture.configureByFile(getTestName(true) + ".json");
    Editor editor = myFixture.getEditor();
    PsiFile file = myFixture.getFile();
    PsiElement psiElement = DocumentationManager.getInstance(getProject()).findTargetElement(editor, file);
    assertDocumentation(psiElement, psiElement, hasDoc);
  }

  private void assertDocumentation(@NotNull PsiElement docElement, @NotNull PsiElement context, boolean shouldHaveDoc) {
    DocumentationProvider documentationProvider = DocumentationManager.getProviderFromElement(context);
    String inlineDoc = documentationProvider.generateDoc(docElement, context);
    if (shouldHaveDoc) {
      assertNotNull("inline help is null", inlineDoc);
    }
    else {
      assertNull("inline help is not null", inlineDoc);
    }
    if (shouldHaveDoc) {
      assertSameLinesWithFile(getTestDataPath() + "/" + getTestName(true) + ".html", inlineDoc);
    }
  }
}
