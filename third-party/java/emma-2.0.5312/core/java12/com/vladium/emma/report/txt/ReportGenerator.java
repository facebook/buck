/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: ReportGenerator.java,v 1.1.1.1.2.1 2004/07/16 23:32:30 vlad_r Exp $
 */
package com.vladium.emma.report.txt;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;

import com.vladium.util.Files;
import com.vladium.util.IProperties;
import com.vladium.util.asserts.$assert;
import com.vladium.emma.IAppConstants;
import com.vladium.emma.IAppErrorCodes;
import com.vladium.emma.EMMAProperties;
import com.vladium.emma.EMMARuntimeException;
import com.vladium.emma.data.ICoverageData;
import com.vladium.emma.data.IMetaData;
import com.vladium.emma.report.AbstractReportGenerator;
import com.vladium.emma.report.AllItem;
import com.vladium.emma.report.ClassItem;
import com.vladium.emma.report.IItem;
import com.vladium.emma.report.IItemAttribute;
import com.vladium.emma.report.ItemComparator;
import com.vladium.emma.report.MethodItem;
import com.vladium.emma.report.PackageItem;
import com.vladium.emma.report.SourcePathCache;
import com.vladium.emma.report.SrcFileItem;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
final class ReportGenerator extends AbstractReportGenerator
                            implements IAppErrorCodes
{
    // public: ................................................................
    
    // TODO: this is prototype quality, needs major cleanup
    
    // IReportGenerator:
    
    public String getType ()
    {
        return TYPE;
    }
    
    public void process (final IMetaData mdata, final ICoverageData cdata,
                         final SourcePathCache cache, final IProperties properties)
        throws EMMARuntimeException
    {
        initialize (mdata, cdata, cache, properties);
        
        long start = 0, end;
        final boolean trace1 = m_log.atTRACE1 ();
        
        if (trace1) start = System.currentTimeMillis ();
        
        {
            m_queue = new LinkedList ();
            for (m_queue.add (m_view.getRoot ()); ! m_queue.isEmpty (); )
            {
                final IItem head = (IItem) m_queue.removeFirst ();
                
                head.accept (this, null);
            }
            line ();
            
            close ();
        }
                
        if (trace1)
        {
            end = System.currentTimeMillis ();
            
            m_log.trace1 ("process", "[" + getType () + "] report generated in " + (end - start) + " ms");
        }
    }
    
    public void cleanup ()
    {
        m_queue = null;
        close ();
        
        super.cleanup ();
    }
    
    
    // IItemVisitor:
    
    public Object visit (final AllItem item, final Object ctx)
    {
        File outFile = m_settings.getOutFile ();
        if (outFile == null)
        {
            outFile = new File ("coverage.txt");
            m_settings.setOutFile (outFile);
        }
        
        final File fullOutFile = Files.newFile (m_settings.getOutDir (), outFile);
        
        m_log.info ("writing [" + getType () + "] report to [" + fullOutFile.getAbsolutePath () + "] ...");
        
        openOutFile (fullOutFile, m_settings.getOutEncoding (), true);
        
        // build ID stamp:
        try
        {
            final StringBuffer label = new StringBuffer (101);
            
            label.append ("[");
            label.append (IAppConstants.APP_NAME);
            label.append (" v"); label.append (IAppConstants.APP_VERSION_WITH_BUILD_ID_AND_TAG);
            label.append (" report, generated ");
            label.append (new Date (EMMAProperties.getTimeStamp ()));
            label.append ("]");
            
            m_out.write (label.toString ());
            m_out.newLine ();
            
            m_out.flush ();
        }
        catch (IOException ioe)
        {
            throw new EMMARuntimeException (IAppErrorCodes.REPORT_IO_FAILURE, ioe);
        }
        
        final int [] columns = m_settings.getColumnOrder ();
         
        line ();
                
        // [all] coverage summary row:
        addTitleRow ("OVERALL COVERAGE SUMMARY", 0, 1);            
        {
            // header row:
            addHeaderRow (item, columns);
            
            // coverage row:
            addItemRow (item, columns);
        }
                
        // [all] stats summary table ([all] only):
        addTitleRow ("OVERALL STATS SUMMARY", 1, 1);
        {
            row ("total packages:" + m_separator + item.getChildCount ());
            row ("total classes:" + m_separator + item.getAggregate (IItem.TOTAL_CLASS_COUNT)); 
            row ("total methods:" + m_separator + item.getAggregate (IItem.TOTAL_METHOD_COUNT));
            
            if (m_srcView && m_hasSrcFileInfo)
            {
                row ("total executable files:" + m_separator + item.getAggregate (IItem.TOTAL_SRCFILE_COUNT));
                
                if (m_hasLineNumberInfo)
                    row ("total executable lines:" + m_separator + item.getAggregate (IItem.TOTAL_LINE_COUNT));
            }
        }
        
        final boolean deeper = (m_settings.getDepth () > item.getMetadata ().getTypeID ());
        
        // render package summary rows:
        addTitleRow ("COVERAGE BREAKDOWN BY PACKAGE", 1, 1);
        {
            boolean headerDone = false;
            final ItemComparator order = m_typeSortComparators [PackageItem.getTypeMetadata ().getTypeID ()];                
            for (Iterator packages = item.getChildren (order); packages.hasNext (); )
            {
                final IItem pkg = (IItem) packages.next ();
                
                if (! headerDone)
                {
                    // header row:
                    addHeaderRow (pkg, columns);                        
                    headerDone = true;
                }
                
                // coverage row:
                addItemRow (pkg, columns);
                
                if (deeper) m_queue.addLast (pkg);
            }
        }

        return ctx;
    }
    
    public Object visit (final PackageItem item, final Object ctx)
    {
        if (m_verbose) m_log.verbose ("  report: processing package [" + item.getName () + "] ...");
        
        final int [] columns = m_settings.getColumnOrder ();
        
        line ();
        
        // coverage summary row:
        addTitleRow ("COVERAGE SUMMARY FOR PACKAGE [".concat (item.getName ()).concat ("]"), 0, 1);            
        {
            // header row:
            addHeaderRow (item, columns);
            
            // coverage row:
            addItemRow (item, columns);
        }
        
        
        final boolean deeper = (m_settings.getDepth () > item.getMetadata ().getTypeID ());
                
        // render child summary rows:
        
        final String summaryTitle = m_srcView ? "COVERAGE BREAKDOWN BY SOURCE FILE" : "COVERAGE BREAKDOWN BY CLASS";
        addTitleRow (summaryTitle, 1, 1);
        {
            boolean headerDone = false;
            final ItemComparator order = m_typeSortComparators [m_srcView ? SrcFileItem.getTypeMetadata ().getTypeID () : ClassItem.getTypeMetadata ().getTypeID ()];                
            for (Iterator srcORclsFiles = item.getChildren (order); srcORclsFiles.hasNext (); )
            {
                final IItem srcORcls = (IItem) srcORclsFiles.next ();
                
                if (! headerDone)
                {
                    // header row:
                    addHeaderRow (srcORcls, columns);                        
                    headerDone = true;
                }
                
                // coverage row:
                addItemRow (srcORcls, columns);
                
                if (deeper) m_queue.addLast (srcORcls);
            }
        }

        return ctx;
    }

    public Object visit (final SrcFileItem item, final Object ctx)
    {
        final int [] columns = m_settings.getColumnOrder ();
        
        line ();
        
        // coverage summary row:
        addTitleRow ("COVERAGE SUMMARY FOR SOURCE FILE [".concat (item.getName ()).concat ("]"), 0, 1);            
        {
            // header row:
            addHeaderRow (item, columns);
            
            // coverage row:
            addItemRow (item, columns);
        }
        
        // render child summary rows:
        addTitleRow ("COVERAGE BREAKDOWN BY CLASS AND METHOD", 1, 1);
        {
            boolean headerDone = false;
            final ItemComparator order = m_typeSortComparators [ClassItem.getTypeMetadata ().getTypeID ()];                
            for (Iterator classes = item.getChildren (order); classes.hasNext (); )
            {
                final IItem cls = (IItem) classes.next ();
                
                if (! headerDone)
                {
                    // header row:
                    addHeaderRow (cls, columns);                        
                    headerDone = true;
                }
                
                // coverage row:
                //addItemRow (child, columns);
                
                addTitleRow ("class [" + cls.getName () + "] methods", 0, 0);
                
                // TODO: select the right comparator here
                final ItemComparator order2 = m_typeSortComparators [MethodItem.getTypeMetadata ().getTypeID ()];                
                for (Iterator methods = cls.getChildren (order2); methods.hasNext (); )
                {
                    final MethodItem method = (MethodItem) methods.next ();
                    
                    addItemRow (method, columns);
                }
            }
        }

        return ctx;
    }
    
    public Object visit (final ClassItem item, final Object ctx)
    {
        final int [] columns = m_settings.getColumnOrder ();
        
        line ();
        
        // coverage summary row:
        addTitleRow ("COVERAGE SUMMARY FOR CLASS [".concat (item.getName ()).concat ("]"), 0, 1);            
        {
            // header row:
            addHeaderRow (item, columns);
            
            // coverage row:
            addItemRow (item, columns);
        }
        
        // render child summary rows:
        addTitleRow ("COVERAGE BREAKDOWN BY METHOD", 1, 1);
        {
            final ItemComparator order = m_typeSortComparators [MethodItem.getTypeMetadata ().getTypeID ()];                
            for (Iterator methods = item.getChildren (order); methods.hasNext (); )
            {
                final IItem method = (IItem) methods.next ();
                
                // coverage row:
                addItemRow (method, columns);                
            }
        }

        return ctx;
    }
        
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private void addTitleRow (final String text, final int hlines, final int flines)
    {
        for (int i = 0; i < hlines; ++ i) eol ();
        row (new StringBuffer (text).append (":"));
        for (int i = 0; i < flines; ++ i) eol ();
    } 
    
    private void addHeaderRow (final IItem item, final int [] columns)
    {
        if ($assert.ENABLED)
        {
            $assert.ASSERT (item != null, "null input: item");
            $assert.ASSERT (columns != null, "null input: columns");
        }
        
        // header row:
        final StringBuffer buf = new StringBuffer ();
        
        for (int c = 0, cLimit = columns.length; c < cLimit; ++ c)
        {
            final int attrID = columns [c];
            final IItemAttribute attr = item.getAttribute (attrID, m_settings.getUnitsType ());
            
            if (attr != null)
            {
                buf.append ('[');
                buf.append (attr.getName ());
                buf.append (']');
            }
            if (c != cLimit - 1) buf.append (m_separator);
        }
        
        row (buf);
    }
    
    
    /*
     * No header row, just data rows.
     */
    private void addItemRow (final IItem item, final int [] columns)
    {
        if ($assert.ENABLED)
        {
            $assert.ASSERT (item != null, "null input: item");
            $assert.ASSERT (columns != null, "null input: columns");
        }
        
        final StringBuffer buf = new StringBuffer (11); // TODO: reuse a buffer
        
        for (int c = 0, cLimit = columns.length; c < cLimit; ++ c)
        {
            final int attrID = columns [c];
            final IItemAttribute attr = item.getAttribute (attrID, m_settings.getUnitsType ());
            
            if (attr != null)
            {
                boolean fail = (m_metrics [attrID] > 0) && ! attr.passes (item, m_metrics [attrID]);
                
                if (fail)
                {
                    //buf.append ('(');
                    //buf.append ("! ");
                    attr.format (item, buf);
                    buf.append ('!');
                    //buf.append (')');
                }
                else
                {
                    attr.format (item, buf);
                }
            }
            if (c != cLimit - 1) buf.append (m_separator);
        }
        
        row (buf);
    }
    
    
    private void row (final StringBuffer str)
    {
        if ($assert.ENABLED) $assert.ASSERT (str != null, "str = null");
        
        try
        {
            m_out.write (str.toString ());
            m_out.newLine ();
        }
        catch (IOException ioe)
        {
            throw new EMMARuntimeException (IAppErrorCodes.REPORT_IO_FAILURE, ioe);
        }
    }
    
    private void row (final String str)
    {
        if ($assert.ENABLED) $assert.ASSERT (str != null, "str = null");
        
        try
        {
            m_out.write (str);
            m_out.newLine ();
        }
        catch (IOException ioe)
        {
            throw new EMMARuntimeException (IAppErrorCodes.REPORT_IO_FAILURE, ioe);
        }
    }
    
    private void line ()
    {
        try
        {
            m_out.write (LINE);
            m_out.newLine ();
        }
        catch (IOException ioe)
        {
            throw new EMMARuntimeException (IAppErrorCodes.REPORT_IO_FAILURE, ioe);
        }
    }
    
    private void eol ()
    {
        try
        {
            m_out.newLine ();
        }
        catch (IOException ioe)
        {
            throw new EMMARuntimeException (IAppErrorCodes.REPORT_IO_FAILURE, ioe);
        }
    }
    
    private void close ()
    {
        if (m_out != null)
        {
            try
            {
                m_out.flush ();
                m_out.close ();
            }
            catch (IOException ioe)
            {
                throw new EMMARuntimeException (IAppErrorCodes.REPORT_IO_FAILURE, ioe);
            }
            finally
            {
                m_out = null;
            }
        }
    }
    
    private void openOutFile (final File file, final String encoding, final boolean mkdirs)
    {
        try
        {
            if (mkdirs)
            {
                final File parent = file.getParentFile ();
                if (parent != null) parent.mkdirs ();
            }
            
            m_out = new BufferedWriter (new OutputStreamWriter (new FileOutputStream (file), encoding), IO_BUF_SIZE);
        }
        catch (UnsupportedEncodingException uee)
        {
            // TODO: error code
            throw new EMMARuntimeException (uee);
        }
        // note: in J2SDK 1.3 FileOutputStream constructor's throws clause
        // was narrowed to FileNotFoundException:
        catch (IOException fnfe) // FileNotFoundException
        {
            // TODO: error code
            throw new EMMARuntimeException (fnfe);
        }
    }
    
    
    private char m_separator = '\t'; // TODO: set this
    
    private LinkedList /* IITem */ m_queue;
    private BufferedWriter m_out;
    
    private static final String TYPE = "txt";
    private static final String LINE = "-------------------------------------------------------------------------------";
    
    private static final int IO_BUF_SIZE = 32 * 1024;

} // end of class
// ----------------------------------------------------------------------------