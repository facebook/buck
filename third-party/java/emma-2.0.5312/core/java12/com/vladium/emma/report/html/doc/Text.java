/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: Text.java,v 1.1.1.1 2004/05/09 16:57:42 vlad_r Exp $
 */
package com.vladium.emma.report.html.doc;

import com.vladium.util.Strings;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public 
final class Text implements IContent
{
    // public: ................................................................

    public Text (final String text, final boolean nbsp)
    {
        m_text = text;
        m_nbsp = nbsp;
    }
    
    public void emit (final HTMLWriter out)
    {
        if (m_text != null)
        {
            if (m_nbsp)
                out.write (Strings.HTMLEscapeSP (m_text));
            else
                out.write (Strings.HTMLEscape (m_text));
        }
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private final String m_text;
    private final boolean m_nbsp;

} // end of class
// ----------------------------------------------------------------------------