/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: HTMLDocument.java,v 1.1.1.1 2004/05/09 16:57:41 vlad_r Exp $
 */
package com.vladium.emma.report.html.doc;

import com.vladium.util.IConstants;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
final class HTMLDocument extends IElement.Factory.ElementImpl
{
    // public: ................................................................
    

    public HTMLDocument ()
    {
        this (null, null);
    }
    
    public HTMLDocument (final String title, final String encoding)
    {
        super (Tag.HTML, AttributeSet.create ());
        
        super.add (m_head = IElement.Factory.create (Tag.HEAD));
        super.add (m_body = IElement.Factory.create (Tag.BODY));

        // specify encoding META before anything else:
        if ((encoding != null) && (encoding.length () != 0))
        {
            final ISimpleElement meta = ISimpleElement.Factory.create (Tag.META);
            
            meta.getAttributes ()
            .set (Attribute.HTTP_EQUIV, "Content-Type")
            .set (Attribute.CONTENT, "text/html; charset=" + encoding);
            
            m_head.add (meta);
        }
        
        if (title != null)
        {
            // TODO: escape
            //getAttributes ().set (Attribute.TITLE, title);
            
            final IElement titleElement = IElement.Factory.create (Tag.TITLE).setText (title, false);
            m_head.add (titleElement);            
        }
        
        m_title = title;
    }
    
    public String getTitle ()
    {
        return m_title;
    }
    
    public IElement getHead ()
    {
        return m_head; 
    }
    
    public IElement getBody ()
    {
        return m_body;
    }
    
    public IContent getHeader ()
    {
        return m_header;
    }
    
    public IContent getFooter ()
    {
        return m_footer;
    }


    public void setHeader (final IContent header)
    {
        if (header != null) m_header = header;
    }
    
    public void setFooter (final IContent footer)
    {
        if (footer != null) m_footer = footer;
    }

    /**
     * Overridden to ensure header/footer appear first/last in the body.
     */
    public void emit (HTMLWriter out)
    {
        if (m_header != null) m_body.add (0, m_header);
        if (m_footer != null) m_body.add (m_body.size (), m_footer);
            
        super.emit(out);
    }

    /**
     * Overridden to add to the doc body.
     */
    public IElementList add (final IContent content)
    {
        m_body.add (content);
        
        return this;
    }
    
    public void addStyle (final String css)
    {
        if (css != null)
        {
            final IElement style = IElement.Factory.create (Tag.STYLE);
            style.getAttributes ().set (Attribute.TYPE, "text/css");
            
            final StringBuffer def = new StringBuffer ("<!--");
            def.append (IConstants.EOL);
            
            style.setText (css, false);
            
            def.append (IConstants.EOL);
            def.append ("-->");
            
            m_head.add (style);
        }
    }
    
    /**
     * Adds a &lt;LINK&gt; to the head.
     */
    public void addLINK (final String type, final String href)
    {
        final ISimpleElement link = ISimpleElement.Factory.create (Tag.LINK);
        
        // TODO: add REL="STYLESHEET"
        
        link.getAttributes ().set (Attribute.TYPE, type); // TODO: escape
        link.getAttributes ().set (Attribute.HREF, href); // TODO: escape
        link.getAttributes ().set (Attribute.SRC, href); // TODO: escape
        
        m_head.add (link);
    }
    
    public void addH (final int level, final String text, final String classID)
    {
        final Tag Hl = Tag.Hs [level];
        
        final IElement h = IElement.Factory.create (Hl);
        h.setText (text, true);
        h.setClass (classID);
         
        add (h);
    }
    
    public void addH (final int level, final IContent text, final String classID)
    {
        final Tag Hl = Tag.Hs [level];
        
        final IElement h = IElement.Factory.create (Hl);
        h.add (text);
        h.setClass (classID);
         
        add (h);
    }
    
    public void addHR (final int size)
    {
        final IElement hr = IElement.Factory.create (Tag.HR);
        hr.getAttributes ().set (Attribute.SIZE, size);
        
        add (hr);
    }
    
    public void addEmptyP ()
    {
        add (IElement.Factory.create (Tag.P));
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private final String m_title;
    private final IElement m_head;
    private final IElement m_body;
    
    private IContent m_header, m_footer;

} // end of class
// ----------------------------------------------------------------------------