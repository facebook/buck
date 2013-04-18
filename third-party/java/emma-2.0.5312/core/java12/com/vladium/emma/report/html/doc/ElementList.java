/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: ElementList.java,v 1.1.1.1 2004/05/09 16:57:41 vlad_r Exp $
 */
package com.vladium.emma.report.html.doc;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

// ----------------------------------------------------------------------------
/**
 * element list that is not necessarily an element itself
 * 
 * @author Vlad Roubtsov, (C) 2003
 */
public
final class ElementList implements IElementList
{
    // public: ................................................................


    public ElementList ()
    {
        m_contents = new ArrayList ();
    }

    
    public void emit (final HTMLWriter out)
    {
        for (Iterator c = m_contents.iterator (); c.hasNext (); )
        {
            final IContent content = (IContent) c.next ();
            content.emit (out);
        }
    }
                
    public IElementList add (final IContent content)
    {
        if (content != null)
        {
            m_contents.add (content);
        }
        
        return this;
    }
    
    public IElementList add (final int index, final IContent content)
    {
        if (content != null)
        {
            m_contents.add (index, content);
        }
        
        return this;
    }
    
    public int size ()
    {
        return m_contents.size ();
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private final List /* Content */ m_contents;

} // end of class
// ----------------------------------------------------------------------------