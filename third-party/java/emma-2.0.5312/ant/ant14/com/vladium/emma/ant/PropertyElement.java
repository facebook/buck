/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: PropertyElement.java,v 1.1.1.1.2.1 2004/07/10 03:34:52 vlad_r Exp $
 */
package com.vladium.emma.ant;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
final class PropertyElement
{
    // public: ................................................................
    
    
    public PropertyElement ()
    {
        // ensure the constructor is always public
    }
    

    public String getName ()
    {
        return m_name;
    }
    
    public String getValue ()
    {
        return m_value;
    }

    public void setName (final String name)
    {
        m_name = name;
    }
    
    public void setValue (final String value)
    {
        m_value = value;
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................


    private String m_name, m_value;

} // end of class
// ----------------------------------------------------------------------------