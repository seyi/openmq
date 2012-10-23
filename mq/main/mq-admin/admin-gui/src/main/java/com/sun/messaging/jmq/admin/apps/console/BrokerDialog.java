/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2000-2012 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

/*
 * @(#)BrokerDialog.java	1.7 06/27/07
 */ 

package com.sun.messaging.jmq.admin.apps.console;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.BorderLayout;
import java.awt.GridBagLayout;
import java.awt.GridBagConstraints;
import java.awt.Insets;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;
import java.awt.event.ItemListener;
import java.awt.event.ItemEvent;
import javax.swing.JPanel;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JPasswordField;
import javax.swing.JSeparator;
import javax.swing.JCheckBox;
import javax.swing.JOptionPane;
import javax.swing.JRadioButton;
import javax.swing.ButtonGroup;
import javax.swing.event.EventListenerList;

import com.sun.messaging.jmq.admin.bkrutil.BrokerAdmin;
import com.sun.messaging.jmq.admin.bkrutil.BrokerAdminException;
import com.sun.messaging.jmq.admin.util.Globals;
import com.sun.messaging.jmq.admin.resources.AdminConsoleResources;
import com.sun.messaging.jmq.admin.event.BrokerAdminEvent;
import com.sun.messaging.jmq.admin.apps.console.util.LabelledComponent;
import com.sun.messaging.jmq.admin.apps.console.util.LabelValuePanel;
import com.sun.messaging.jmq.admin.apps.console.util.IntegerField;

/** 
 * This dialog is used for entering information needed to connect
 * to a broker.
 * <P>
 * Subclasses of it are used for creating/adding a new entry to the 
 * broker list in the admin console (BrokerAddDialog) and also for
 * viewing/editing existing entries on the broker list
 * (BrokerPropsDialog).
 */
public abstract class BrokerDialog extends AdminDialog  {
    /*
     * This should be obtained from the admin objects
     * instead of being hardcoded.
     */
    public static final String DEFAULT_BROKER_HOST 	= "localhost";
    public static final String DEFAULT_PRIMARY_PORT 	= "7676";

    protected static final AdminConsoleResources acr = Globals.getAdminConsoleResources();
    protected static String close[] = {acr.getString(acr.I_DIALOG_CLOSE)};

    protected JTextField brokerNameTF;
    protected JTextField hostTF;
    protected IntegerField portTF;
    protected JTextField userTF;
    protected JPasswordField passwdTF;
    protected JTextArea ta;

    public BrokerDialog(Frame parent, String title, int whichButtons) {
	super(parent, title, whichButtons);
    }

    public JPanel createWorkPanel()  {
        JPanel workPanel = new JPanel();
	GridBagLayout workGridbag = new GridBagLayout();
	workPanel.setLayout(workGridbag);
	GridBagConstraints workConstraints = new GridBagConstraints();

	JPanel brokerPanel = new JPanel();
	GridBagLayout brokerGridbag = new GridBagLayout();
	brokerPanel.setLayout(brokerGridbag);
	GridBagConstraints brokerConstraints = new GridBagConstraints();

	brokerConstraints.gridx = 0;
	brokerConstraints.gridy = 0;
	brokerConstraints.anchor = GridBagConstraints.EAST;
	JLabel l = new JLabel(acr.getString(acr.I_BROKER_NAME));
	brokerGridbag.setConstraints(l, brokerConstraints);
	brokerPanel.add(l);

	brokerConstraints.gridx = 1;
	brokerConstraints.gridy = 0;
	brokerConstraints.anchor = GridBagConstraints.WEST;
	brokerConstraints.insets = new Insets(0, 5, 0, 0);

	brokerNameTF = new JTextField(20);
	brokerGridbag.setConstraints(brokerNameTF, brokerConstraints);
	brokerPanel.add(brokerNameTF);

        LabelledComponent items[];
        items = new LabelledComponent[4];

        hostTF = new JTextField(DEFAULT_BROKER_HOST, 10);
        items[0] = new LabelledComponent(acr.getString(acr.I_BROKER_HOST), hostTF);

        portTF = new IntegerField(0, Integer.MAX_VALUE, DEFAULT_PRIMARY_PORT, 10);
        items[1] = new LabelledComponent(acr.getString(acr.I_BROKER_PORT), portTF);

        userTF = new JTextField(BrokerAdmin.DEFAULT_ADMIN_USERNAME, 10);
        items[2] = new LabelledComponent(acr.getString(acr.I_BROKER_USERNAME), userTF);

        passwdTF = new JPasswordField("", 10);
        items[3] = new LabelledComponent(acr.getString(acr.I_BROKER_PASSWD), passwdTF);

        LabelValuePanel lvp = new LabelValuePanel(items, 4, 4);

	workConstraints.gridx = 0;
	workConstraints.anchor = GridBagConstraints.WEST;
	workConstraints.fill = GridBagConstraints.NONE;
	workConstraints.ipadx = 0;
	workConstraints.ipady = 0;
	workConstraints.weightx = 1.0;

	workConstraints.gridy = 0;
	workGridbag.setConstraints(brokerPanel, workConstraints);
	workPanel.add(brokerPanel);

	workConstraints.gridy = 1;
        workConstraints.insets = new Insets(10, 0, 0, 0);
	workConstraints.fill = GridBagConstraints.HORIZONTAL;
	JSeparator sep = new JSeparator();
	workGridbag.setConstraints(sep, workConstraints);
	workPanel.add(sep);

	workConstraints.gridy = 2;
        workConstraints.insets = new Insets(0, 0, 0, 0);  // reset
	workConstraints.fill = GridBagConstraints.NONE;
	workGridbag.setConstraints(lvp, workConstraints);
	workPanel.add(lvp);

	ta = new JTextArea(acr.getString(acr.W_SAVE_AS_CLEAR_TEXT));
	ta.setLineWrap(true);
	ta.setWrapStyleWord(true);
	Color bgColor = brokerPanel.getBackground();
	ta.setBackground(bgColor);
	Color fgColor = l.getForeground();
	ta.setForeground(fgColor);
	ta.setFont(l.getFont());

	// Find longer of:
	// 1) Broker Label: ______
	// 2) LabelValuePanel
	int width1 = l.getPreferredSize().width + 5 + 
		     brokerNameTF.getPreferredSize().width;
	int width2 = lvp.getPreferredSize().width;
	if (width1 >= width2)
	    ta.setSize(width1, 1);
	else
	    ta.setSize(width2, 1);

	ta.setEditable(false);
	Dimension textSize = ta.getPreferredSize();
	ta.setSize(textSize);

	workConstraints.gridy = 3;
	workGridbag.setConstraints(ta, workConstraints);
	workPanel.add(ta);

	return (workPanel);
    }

    protected boolean isValidString(String s) {
	if ((s == null) || ("".equals(s)))
	    return false;
	else
	    return true;
    }

    protected void clearFields() {
	brokerNameTF.setText("");
	hostTF.setText("");
        portTF.setText("");
        userTF.setText("");
        passwdTF.setText("");
    }

    protected void setEditable(boolean editable) {

	if (editable) {
	    brokerNameTF.setEditable(true);
	    hostTF.setEditable(true); 
            portTF.setEditable(true);
            userTF.setEditable(true);
            passwdTF.setEditable(true);
            passwdTF.setBackground(userTF.getBackground());
	    ta.setText(acr.getString(acr.W_SAVE_AS_CLEAR_TEXT));
        } else {
	    brokerNameTF.setEditable(false);
	    hostTF.setEditable(false); 
            portTF.setEditable(false);
            userTF.setEditable(false);
            passwdTF.setEditable(false);
            passwdTF.setBackground(userTF.getBackground());
	    ta.setText(acr.getString(acr.W_BKR_NOT_EDITABLE_TEXT));
        }
    }
}
