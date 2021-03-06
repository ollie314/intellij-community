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
package com.intellij.openapi.vcs.ui;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.util.Condition;
import com.intellij.ui.popup.PopupFactoryImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class FlatSpeedSearchPopup extends PopupFactoryImpl.ActionGroupPopup {

  public FlatSpeedSearchPopup(String title,
                              @NotNull DefaultActionGroup actionGroup,
                              @NotNull DataContext dataContext,
                              @Nullable Condition<AnAction> preselectActionCondition, boolean showDisableActions) {
    super(title, actionGroup, dataContext, false, false, showDisableActions, false,
          null, -1, preselectActionCondition, null);
  }

  @NotNull
  public static AnAction createSpeedSearchWrapper(@NotNull AnAction child) {
    return new MySpeedSearchAction(child);
  }

  @NotNull
  public static ActionGroup createSpeedSearchActionGroupWrapper(@NotNull ActionGroup child) {
    return new MySpeedSearchActionGroup(child);
  }

  protected static boolean isSpeedsearchAction(@NotNull AnAction action) {
    return action instanceof SpeedsearchAction;
  }

  public interface SpeedsearchAction {
  }

  private static class MySpeedSearchAction extends EmptyAction.MyDelegatingAction implements SpeedsearchAction {

    public MySpeedSearchAction(@NotNull AnAction action) {
      super(action);
    }
  }

  private static class MySpeedSearchActionGroup extends EmptyAction.MyDelegatingActionGroup implements SpeedsearchAction {
    public MySpeedSearchActionGroup(@NotNull ActionGroup actionGroup) {
      super(actionGroup);
    }
  }
}
