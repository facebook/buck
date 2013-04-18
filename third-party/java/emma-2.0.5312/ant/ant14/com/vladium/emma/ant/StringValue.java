/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: StringValue.java,v 1.2 2004/05/20 02:28:06 vlad_r Exp $
 */
package com.vladium.emma.ant;

import org.apache.tools.ant.Task;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
abstract class StringValue
{
    // public: ................................................................
    
    
    public void appendValue (final String value, final String separator)
    {
        if ((value != null) && (value.length () > 0))
        {
            if (m_value == null)
            {
                m_value = new StringBuffer (value); 
            }
            else
            {
                m_value.append (separator);
                m_value.append (value); // no trailing separator kept
            }
        }
    }
                
    public String getValue ()
    {
        return m_value != null ? m_value.toString () : null;  
    }
    
    // protected: .............................................................
    
    
    protected StringValue (final Task task)
    {
        if (task == null) throw new IllegalArgumentException ("null input: task");
        
        m_task = task;
    }


    protected final Task m_task;
    
    // package: ...............................................................
    
    // private: ...............................................................

    
    private StringBuffer m_value;

} // end of class
// ----------------------------------------------------------------------------