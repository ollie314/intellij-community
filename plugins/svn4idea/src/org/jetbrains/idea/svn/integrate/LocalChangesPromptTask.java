/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package org.jetbrains.idea.svn.integrate;

import com.intellij.openapi.util.Condition;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.LocalChangeList;
import com.intellij.openapi.vcs.versionBrowser.CommittedChangeList;
import com.intellij.util.FilePathByPathComparator;
import com.intellij.util.continuation.Where;
import com.intellij.vcsUtil.VcsUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.svn.history.SvnChangeList;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.intellij.openapi.util.Conditions.alwaysTrue;
import static com.intellij.openapi.util.text.StringUtil.isEmptyOrSpaces;
import static com.intellij.openapi.vcs.changes.ChangesUtil.*;
import static com.intellij.util.containers.ContainerUtil.sorted;
import static java.util.stream.Collectors.toSet;
import static org.tmatesoft.svn.core.internal.util.SVNPathUtil.append;
import static org.tmatesoft.svn.core.internal.util.SVNPathUtil.getRelativePath;

public class LocalChangesPromptTask extends BaseMergeTask {

  @Nullable private final List<CommittedChangeList> myChangeListsToMerge;

  public LocalChangesPromptTask(@NotNull QuickMerge mergeProcess) {
    super(mergeProcess, "local changes intersection check", Where.AWT);
    myChangeListsToMerge = null;
  }

  public LocalChangesPromptTask(@NotNull QuickMerge mergeProcess, @NotNull List<CommittedChangeList> changeListsToMerge) {
    super(mergeProcess, "local changes intersection check", Where.AWT);
    myChangeListsToMerge = changeListsToMerge;
  }

  @Nullable
  private File getLocalPath(String repositoryRelativePath) {
    String absoluteUrl = append(myMergeContext.getWcInfo().getRepositoryRoot(), repositoryRelativePath);
    String sourceRelativePath = getRelativePath(myMergeContext.getSourceUrl(), absoluteUrl);

    return !isEmptyOrSpaces(sourceRelativePath) ? new File(myMergeContext.getWcInfo().getPath(), sourceRelativePath) : null;
  }

  @Override
  public void run() {
    List<LocalChangeList> localChangeLists = ChangeListManager.getInstance(myMergeContext.getProject()).getChangeListsCopy();
    Intersection intersection = myChangeListsToMerge != null
                                ? getChangesIntersection(localChangeLists, myChangeListsToMerge)
                                : getAllChangesIntersection(localChangeLists);

    if (intersection != null && !intersection.getChangesSubset().isEmpty()) {
      processIntersection(intersection);
    }
  }

  private void processIntersection(@NotNull Intersection intersection) {
    //noinspection EnumSwitchStatementWhichMissesCases
    switch (myInteraction.selectLocalChangesAction(myChangeListsToMerge == null)) {
      case shelve:
        next(new ShelveLocalChangesTask(myMergeProcess, intersection));
        break;
      case cancel:
        end();
        break;
      case inspect:
        // here's cast is due to generic's bug
        @SuppressWarnings("unchecked") Collection<Change> changes = (Collection<Change>)intersection.getChangesSubset().values();
        myInteraction.showIntersectedLocalPaths(sorted(getPaths(changes), FilePathByPathComparator.getInstance()));
        end();
        break;
    }
  }

  @Nullable
  private Intersection getChangesIntersection(@NotNull List<LocalChangeList> localChangeLists,
                                              @NotNull List<CommittedChangeList> changeListsToMerge) {

    Set<FilePath> pathsToMerge = collectPaths(changeListsToMerge);

    return !changeListsToMerge.isEmpty() ? getChangesIntersection(localChangeLists, change -> hasPathToMerge(change, pathsToMerge)) : null;
  }

  @NotNull
  private Set<FilePath> collectPaths(@NotNull List<CommittedChangeList> lists) {
    return lists.stream()
      .map(SvnChangeList.class::cast)
      .flatMap(list -> list.getAffectedPaths().stream())
      .map(this::getLocalPath)
      .filter(Objects::nonNull)
      .map(localPath -> VcsUtil.getFilePath(localPath, false))
      .collect(toSet());
  }

  @NotNull
  private static Intersection getAllChangesIntersection(@NotNull List<LocalChangeList> localChangeLists) {
    return getChangesIntersection(localChangeLists, alwaysTrue());
  }

  @NotNull
  private static Intersection getChangesIntersection(@NotNull List<LocalChangeList> changeLists, @NotNull Condition<Change> filter) {
    Intersection result = new Intersection();

    for (LocalChangeList changeList : changeLists) {
      for (Change change : changeList.getChanges()) {
        if (filter.value(change)) {
          result.add(changeList.getName(), changeList.getComment(), change);
        }
      }
    }

    return result;
  }

  private static boolean hasPathToMerge(@NotNull Change change, @NotNull Set<FilePath> pathsToMerge) {
    FilePath beforePath = getBeforePath(change);
    FilePath afterPath = getAfterPath(change);

    return beforePath != null && pathsToMerge.contains(beforePath) || afterPath != null && pathsToMerge.contains(afterPath);
  }
}
