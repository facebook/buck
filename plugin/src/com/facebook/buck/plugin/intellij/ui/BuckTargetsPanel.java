/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.plugin.intellij.ui;

import com.facebook.buck.plugin.intellij.BuckPluginComponent;
import com.facebook.buck.plugin.intellij.BuckTarget;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.intellij.ui.components.JBList;

import java.awt.Component;
import java.awt.EventQueue;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JToolBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;

public class BuckTargetsPanel {

  public static String DISPLAY_NAME = "Targets";

  private final BuckPluginComponent component;
  private JPanel targetsPanel;
  private JList targetsList;
  private JButton refreshTargetsButton;
  private JToolBar toolBar;
  private JButton cleanButton;
  private JScrollPane scrollPane;

  public BuckTargetsPanel(BuckPluginComponent component) {
    this.component = Preconditions.checkNotNull(component);
  }

  public JPanel getPanel() {
    return targetsPanel;
  }

  private void createUIComponents() {
    targetsPanel = new JPanel();
    targetsPanel.addAncestorListener(new AncestorListener() {
      @Override
      public void ancestorAdded(AncestorEvent event) {
        // If targets have never been fetched, then fetch targets.
        if (component.getTargets() == null) {
          component.refreshTargetsList();
        }
      }

      @Override
      public void ancestorRemoved(AncestorEvent event) {
      }

      @Override
      public void ancestorMoved(AncestorEvent event) {
      }
    });

    targetsList = Preconditions.checkNotNull(createTargetsList());

    refreshTargetsButton = createToolbarIcon();
    refreshTargetsButton.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        component.refreshTargetsList();
      }
    });

    cleanButton = createToolbarIcon();
    cleanButton.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        component.clean();
      }
    });
  }

  private JButton createToolbarIcon() {
    JButton button = new JButton();
    return button;
  }

  private JList createTargetsList() {
    final JBList targetsList = new JBList(new TargetsListModel(ImmutableList.<BuckTarget>of()));
    targetsList.setCellRenderer(new DefaultListCellRenderer() {
      @Override
      public Component getListCellRendererComponent(JList list,
                                                    Object value,
                                                    int index,
                                                    boolean isSelected,
                                                    boolean cellHasFocus) {
        BuckTarget target = (BuckTarget) value;
        Component renderer = super.getListCellRendererComponent(list,
            target.getFullName(),
            index,
            isSelected,
            cellHasFocus
        );
        ((JComponent) renderer).setToolTipText(target.getFullName());
        return renderer;
      }
    });

    final MouseListener mouseListener = new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
          // Double click
          BuckTarget target = (BuckTarget) targetsList.getSelectedValue();
          component.buildTarget(target);
        }
      }
    };
    targetsList.addMouseListener(mouseListener);
    return targetsList;
  }

  public void updateTargets() {
    EventQueue.invokeLater(new Runnable() {
      @Override
      public void run() {
        targetsList.setModel(new TargetsListModel(component.getTargets()));
      }
    });
  }
}
