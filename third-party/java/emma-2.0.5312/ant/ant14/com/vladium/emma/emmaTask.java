/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: emmaTask.java,v 1.1.1.1.2.2 2004/07/10 03:34:52 vlad_r Exp $
 */
package com.vladium.emma;

import com.vladium.emma.IAppConstants;
import java.util.ArrayList;
import java.util.List;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;

import com.vladium.emma.ant.NestedTask;
import com.vladium.emma.ant.SuppressableTask;
import com.vladium.emma.data.mergeTask;
import com.vladium.emma.instr.instrTask;
import com.vladium.emma.report.reportTask;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
final class emmaTask extends SuppressableTask
{
    // public: ................................................................
    
    // TODO: this and related tasks should be designed for external extensibility
    // [make non final and use virtual prop getters]
    
    public emmaTask ()
    {
        m_tasks = new ArrayList ();
    }
    
    
    public synchronized void execute () throws BuildException
    {
        log (IAppConstants.APP_VERBOSE_BUILD_ID, Project.MSG_VERBOSE);
        
        if (isEnabled ())
        {
            while (! m_tasks.isEmpty ())
            {
                final NestedTask task = (NestedTask) m_tasks.remove (0);
                
                final String name = getTaskName ();
                try
                {
                    setTaskName (task.getTaskName ());
                    
                    task.execute ();
                }
                finally
                {
                    setTaskName (name);
                }
            }
        }
    }

    
    public NestedTask createInstr ()
    {
        return addTask (new instrTask (this), getNestedTaskName ("instr"));
    }
    
    public NestedTask createMerge ()
    {
        return addTask (new mergeTask (this), getNestedTaskName ("merge"));
    }
    
    public NestedTask createReport ()
    {
        return addTask (new reportTask (this), getNestedTaskName ("report"));
    }
    
    // protected: .............................................................


    protected NestedTask addTask (final NestedTask task, final String pseudoName)
    {
        initTask (task, pseudoName);
        
        m_tasks.add (task);
        return task;
    }
    
    protected void initTask (final NestedTask task, final String pseudoName)
    {
        task.setTaskName (pseudoName);
        task.setProject (getProject ());
        task.setLocation (getLocation ());
        task.setOwningTarget (getOwningTarget ());
        
        task.init ();
    }
    
    protected String getNestedTaskName (final String subname)
    {
        return getTaskName ().concat (".").concat (subname);
    }
        
    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private final List /* NestedTask */ m_tasks;

} // end of class
// ----------------------------------------------------------------------------