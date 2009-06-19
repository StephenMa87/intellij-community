package com.intellij.ide.projectView;

import com.intellij.ide.favoritesTreeView.FavoritesTreeNodeDescriptor;
import com.intellij.ide.util.treeView.AbstractTreeBuilder;
import com.intellij.ide.util.treeView.AbstractTreeNode;
import com.intellij.ide.util.treeView.AbstractTreeStructure;
import com.intellij.ide.util.treeView.NodeDescriptor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.util.StatusBarProgress;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.ActionCallback;
import com.intellij.openapi.util.AsyncResult;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Conditions;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public abstract class BaseProjectTreeBuilder extends AbstractTreeBuilder {
  protected final Project myProject;

  public BaseProjectTreeBuilder(Project project,
                                JTree tree,
                                DefaultTreeModel treeModel,
                                AbstractTreeStructure treeStructure,
                                Comparator<NodeDescriptor> comparator) {
    super(tree, treeModel, treeStructure, comparator);
    myProject = project;
  }

  protected boolean isAlwaysShowPlus(NodeDescriptor nodeDescriptor) {
    return ((AbstractTreeNode)nodeDescriptor).isAlwaysShowPlus();
  }

  protected boolean isAutoExpandNode(NodeDescriptor nodeDescriptor) {
    return nodeDescriptor.getParentDescriptor() == null;
  }

  protected final void expandNodeChildren(final DefaultMutableTreeNode node) {
    Object element = ((NodeDescriptor)node.getUserObject()).getElement();
    VirtualFile virtualFile = getFileToRefresh(element);
    super.expandNodeChildren(node);
    if (virtualFile != null) {
      virtualFile.refresh(true, false);
    }
  }

  private static VirtualFile getFileToRefresh(Object element) {
    return element instanceof PsiDirectory
           ? ((PsiDirectory)element).getVirtualFile()
           : element instanceof PsiFile ? ((PsiFile)element).getVirtualFile() : null;
  }

  private List<AbstractTreeNode> getOrBuildChildren(AbstractTreeNode parent) {
    buildNodeForElement(parent);

    DefaultMutableTreeNode node = getNodeForElement(parent);

    if (node == null) {
      return new ArrayList<AbstractTreeNode>();
    }

    getTree().expandPath(new TreePath(node.getPath()));

    return collectChildren(node);
  }

  private List<AbstractTreeNode> collectChildren(DefaultMutableTreeNode node) {
    int childCount = node.getChildCount();
    List<AbstractTreeNode> result = new ArrayList<AbstractTreeNode>(childCount);
    for (int i = 0; i < childCount; i++) {
      TreeNode childAt = node.getChildAt(i);
      DefaultMutableTreeNode defaultMutableTreeNode = (DefaultMutableTreeNode)childAt;
      if (defaultMutableTreeNode.getUserObject() instanceof AbstractTreeNode) {
        ProjectViewNode treeNode = (ProjectViewNode)defaultMutableTreeNode.getUserObject();
        result.add(treeNode);
      }
      else if (defaultMutableTreeNode.getUserObject() instanceof FavoritesTreeNodeDescriptor) {
        AbstractTreeNode treeNode = ((FavoritesTreeNodeDescriptor)defaultMutableTreeNode.getUserObject()).getElement();
        result.add(treeNode);
      }
    }
    return result;
  }

  public ActionCallback select(Object element, VirtualFile file, final boolean requestFocus) {
    return _select(element, file, requestFocus, Conditions.<AbstractTreeNode>alwaysTrue());
  }

  public ActionCallback selectInWidth(final Object element,
                                      final boolean requestFocus,
                                      final Condition<AbstractTreeNode> nonStopCondition) {
    return _select(element, null, requestFocus, nonStopCondition);
  }

  private ActionCallback _select(Object element,
                                 VirtualFile file,
                                 final boolean requestFocus,
                                 final Condition<AbstractTreeNode> nonStopCondition) {
    final ActionCallback result = new ActionCallback();

    DefaultMutableTreeNode selected = alreadySelectedNode(element);

    final Runnable onDone = new Runnable() {
      public void run() {
        if (requestFocus) {
          IdeFocusManager.getInstance(myProject).requestFocus(getTree(), true);
        }

        result.setDone();
      }
    };

    final Condition<AbstractTreeNode> condition = new Condition<AbstractTreeNode>() {
      public boolean value(AbstractTreeNode abstractTreeNode) {
        if (result.isProcessed()) return false;
        return nonStopCondition.value(abstractTreeNode);
      }
    };

    if (selected == null) {
      expandPathTo(file, (AbstractTreeNode)getTreeStructure().getRootElement(), element, condition)
        .doWhenDone(new AsyncResult.Handler<AbstractTreeNode>() {
          public void run(AbstractTreeNode node) {
            select(node, onDone);
          }
        }).notifyWhenRejected(result);
    }
    else {
      onDone.run();
    }

    return result;
  }

  // returns selected node for element or null if element node is not selected
  private DefaultMutableTreeNode alreadySelectedNode(final Object element) {
    final TreePath[] selectionPaths = getTree().getSelectionPaths();
    if (selectionPaths == null || selectionPaths.length == 0) {
      return null;
    }
    for (TreePath selectionPath : selectionPaths) {
      Object selected = selectionPath.getLastPathComponent();
      if (elementIsEqualTo(selected, element)) {
        return (DefaultMutableTreeNode)selected;
      }
    }
    return null;
  }

  private static boolean elementIsEqualTo(final Object node, final Object element) {
    if (node instanceof DefaultMutableTreeNode) {
      final Object userObject = ((DefaultMutableTreeNode)node).getUserObject();
      if (userObject instanceof ProjectViewNode) {
        final AbstractTreeNode projectViewNode = (ProjectViewNode)userObject;
        return projectViewNode.canRepresent(element);
      }
    }
    return false;
  }

  private AsyncResult<AbstractTreeNode> expandPathTo(final VirtualFile file,
                                                     final AbstractTreeNode root,
                                                     final Object element,
                                                     final Condition<AbstractTreeNode> nonStopCondition) {
    if (root.canRepresent(element)) return new AsyncResult.Done<AbstractTreeNode>(root);
    if (root instanceof ProjectViewNode && file != null && !((ProjectViewNode)root).contains(file)) {
      return new AsyncResult.Rejected<AbstractTreeNode>();
    }

    final AsyncResult<AbstractTreeNode> async = new AsyncResult<AbstractTreeNode>();

    expand(root, new Runnable() {
      public void run() {
        final DefaultMutableTreeNode rootNode = getNodeForElement(root);
        if (rootNode != null) {
          final List<AbstractTreeNode> kids = collectChildren(rootNode);
          expandChild(kids, 0, nonStopCondition, file, element, async);
        }
        else {
          async.setRejected();
        }
      }
    });

    return async;
  }

  private void expandChild(final List<AbstractTreeNode> kids, final int i, final Condition<AbstractTreeNode> nonStopCondition, final VirtualFile file,
                           final Object element,
                           final AsyncResult<AbstractTreeNode> async) {

    if (i >= kids.size()) return;

    final AbstractTreeNode eachKid = kids.get(i);
    final boolean[] nodeWasCollapsed = new boolean[] {true};
    final DefaultMutableTreeNode nodeForElement = getNodeForElement(eachKid);
    if (nodeForElement != null) {
      nodeWasCollapsed[0] = getTree().isCollapsed(new TreePath(nodeForElement.getPath()));
    }

    if (nonStopCondition.value(eachKid)) {
      expandPathTo(file, eachKid, element, nonStopCondition).doWhenDone(new AsyncResult.Handler<AbstractTreeNode>() {
        public void run(AbstractTreeNode abstractTreeNode) {
          async.setDone(abstractTreeNode);
        }
      }).doWhenRejected(new Runnable() {
        public void run() {
          if (nodeWasCollapsed[0]) {
            collapseChildren(eachKid, null);
          }
          expandChild(kids, i + 1, nonStopCondition, file, element, async);
        }
      });
    } else {
      async.setRejected();
    }
  }

  protected boolean validateNode(final Object child) {
    if (child instanceof ProjectViewNode) {
      final ProjectViewNode projectViewNode = (ProjectViewNode)child;
      return projectViewNode.validate();
    }
    return true;
  }

  @NotNull
  protected ProgressIndicator createProgressIndicator() {
    return new StatusBarProgress();
  }
}
