/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: ISimpleElement.java,v 1.1.1.1 2004/05/09 16:57:41 vlad_r Exp $
 */
package com.vladium.emma.report.html.doc;

import com.vladium.util.asserts.$assert;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
interface ISimpleElement extends IContent
{
    // public: ................................................................

    Tag getTag ();
    ISimpleElement setClass (String classID);
    AttributeSet getAttributes ();     
    
    abstract class Factory
    {
        public static ISimpleElement create (final Tag tag)
        {
            return new SimpleElementImpl (tag, AttributeSet.create ());
        }
        
        public static ISimpleElement create (final Tag tag, final AttributeSet attrs)
        {
            return new SimpleElementImpl (tag, attrs);
        }
        
        static class SimpleElementImpl implements ISimpleElement
        {
            public String toString ()
            {
                return "<" + m_tag.getName () + "/>";
            }
            
            public Tag getTag ()
            {
                return m_tag;
            }
            
            public ISimpleElement setClass (final String classID)
            {
                if ((classID != null) && (classID.length () > 0))
                {
                    getAttributes ().set (Attribute.CLASS, classID);
                }
                
                return this;
            }
            
            public AttributeSet getAttributes ()
            {
                return m_attrs;
            }
    
            public void emit (final HTMLWriter out)
            {
                out.write ('<');
                out.write (m_tag.getName ());
                
                if (! m_attrs.isEmpty ())
                {
                    out.write (' ');
                    m_attrs.emit (out);
                }
                
                out.write ("/>");
            }
            
            SimpleElementImpl (final Tag tag, final AttributeSet attrs)
            {
                if ($assert.ENABLED) $assert.ASSERT (tag != null, "tag = null");
                if ($assert.ENABLED) $assert.ASSERT (attrs != null, "attrs = null");
                
                m_tag = tag;
                m_attrs = attrs;
            }
            
            
            protected final Tag m_tag;
            protected final AttributeSet m_attrs;
            
        } // end of nested class
        
    } // end of nested class
    


} // end of interface
// ----------------------------------------------------------------------------