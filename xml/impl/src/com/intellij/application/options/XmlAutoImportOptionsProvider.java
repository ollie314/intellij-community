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
package com.intellij.application.options;

import com.intellij.application.options.editor.AutoImportOptionsProvider;
import com.intellij.openapi.options.ConfigurationException;

import javax.swing.*;

/**
 * @author Dmitry Avdeev
 */
public class XmlAutoImportOptionsProvider implements AutoImportOptionsProvider {

  private JPanel myPanel;
  private JCheckBox myShowAutoImportPopups;

  @Override
  public JComponent createComponent() {
    return myPanel;
  }

  @Override
  public boolean isModified() {
    return XmlSettings.getInstance().SHOW_XML_ADD_IMPORT_HINTS != myShowAutoImportPopups.isSelected();
  }

  @Override
  public void apply() throws ConfigurationException {
    XmlSettings.getInstance().SHOW_XML_ADD_IMPORT_HINTS = myShowAutoImportPopups.isSelected();
  }

  @Override
  public void reset() {
    myShowAutoImportPopups.setSelected(XmlSettings.getInstance().SHOW_XML_ADD_IMPORT_HINTS);    
  }
}
