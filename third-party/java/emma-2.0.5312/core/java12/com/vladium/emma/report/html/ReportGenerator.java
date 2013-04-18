/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: ReportGenerator.java,v 1.2.2.1 2004/07/16 23:32:04 vlad_r Exp $
 */
package com.vladium.emma.report.html;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.text.DecimalFormat;
import java.text.FieldPosition;
import java.text.NumberFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;

import com.vladium.util.Descriptors;
import com.vladium.util.Files;
import com.vladium.util.IProperties;
import com.vladium.util.IntObjectMap;
import com.vladium.util.IntVector;
import com.vladium.util.ObjectIntMap;
import com.vladium.util.Property;
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
import com.vladium.emma.report.IItemMetadata;
import com.vladium.emma.report.ItemComparator;
import com.vladium.emma.report.MethodItem;
import com.vladium.emma.report.PackageItem;
import com.vladium.emma.report.SourcePathCache;
import com.vladium.emma.report.SrcFileItem;
import com.vladium.emma.report.html.doc.*;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
final class ReportGenerator extends AbstractReportGenerator
                            implements IAppErrorCodes
{
    // public: ................................................................
    
    // TODO: make sure relative file names are converted to relative URLs in all anchors/hrefs

    public ReportGenerator ()
    {
        m_format = (DecimalFormat) NumberFormat.getPercentInstance (); // TODO: locale
        m_fieldPosition = new FieldPosition (DecimalFormat.INTEGER_FIELD);
        
        m_format.setMaximumFractionDigits (0);
    }

        
    // IReportGenerator:
    
    public final String getType ()
    {
        return TYPE;
    }
    
    public void process (final IMetaData mdata, final ICoverageData cdata,
                         final SourcePathCache cache, final IProperties properties)
        throws EMMARuntimeException
    {
        initialize (mdata, cdata, cache, properties);
        
        m_pageTitle = null;
        m_footerBottom = null;
        
        File outDir = m_settings.getOutDir ();
        if ((outDir == null) /* this should never happen */ || (outDir.equals (new File (Property.getSystemProperty ("user.dir", "")))))
        {
            outDir = new File ("coverage");
            m_settings.setOutDir (outDir);
        }        
        
        long start = 0, end;
        final boolean trace1 = m_log.atTRACE1 ();
        
        if (trace1) start = System.currentTimeMillis ();
        
        {
            m_queue = new LinkedList ();
            m_reportIDNamespace = new IDGenerator (mdata.size ());
            
            for (m_queue.add (m_view.getRoot ()); ! m_queue.isEmpty (); )
            {
                final IItem head = (IItem) m_queue.removeFirst ();
                
                head.accept (this, null);
            }
            
            m_reportIDNamespace = null;
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
        m_reportIDNamespace = null;
        
        super.cleanup ();
    }

        
    // IItemVisitor:
    
    public Object visit (final AllItem item, final Object ctx)
    {
        HTMLWriter out = null;
        try
        {
            File outFile = m_settings.getOutFile ();
            if (outFile == null)
            {
                outFile = new File ("index".concat (FILE_EXTENSION));
                m_settings.setOutFile (outFile);
            }
            
            final File fullOutFile = Files.newFile (m_settings.getOutDir (), outFile);
            
            m_log.info ("writing [" + getType () + "] report to [" + fullOutFile.getAbsolutePath () + "] ...");
            
            out = openOutFile (fullOutFile, m_settings.getOutEncoding (), true);
            
            final int [] columns = m_settings.getColumnOrder ();
            final StringBuffer buf = new StringBuffer ();
            
            final String title;
            {
                final StringBuffer _title = new StringBuffer (REPORT_HEADER_TITLE);
                
                _title.append (" (generated ");
                _title.append (new Date (EMMAProperties.getTimeStamp ()));
                _title.append (')');
                
                title = _title.toString ();
            }
            
            final HTMLDocument page = createPage (title);            
            {
                final IItem [] path = getParentPath (item);
                
                addPageHeader (page, item, path);
                addPageFooter (page, item, path);
            }
                        
            // [all] coverage summary table:
            
            page.addH (1, "OVERALL COVERAGE SUMMARY", null);
            
            final HTMLTable summaryTable = new HTMLTable ("100%", null, null, "0");
            {
                // header row:
                final HTMLTable.IRow header = summaryTable.newTitleRow ();
                // coverage row:
                final HTMLTable.IRow coverage = summaryTable.newRow ();
                
                for (int c = 0; c < columns.length; ++ c)
                {
                    final int attrID = columns [c];
                    final IItemAttribute attr = item.getAttribute (attrID, m_settings.getUnitsType ());
                    
                    final HTMLTable.ICell headercell = header.newCell ();
                    headercell.setText (attr.getName (), true);
                    
                    if (attr != null)
                    {
                        boolean fail = (m_metrics [attrID] > 0) && ! attr.passes (item, m_metrics [attrID]);
                        
                        buf.setLength (0);
                        attr.format (item, buf);
                        
                        final HTMLTable.ICell cell = coverage.newCell (); 
                        cell.setText (buf.toString (), true);
                        if (fail) cell.setClass (CSS_DATA_HIGHLIGHT);
                    }
                }
            }
            page.add (summaryTable);
            
            // [all] stats summary table ([all] only):

            page.addH (2, "OVERALL STATS SUMMARY", null);
            
            final HTMLTable statsTable = new HTMLTable (null, null, null, "0");
            statsTable.setClass (CSS_INVISIBLE_TABLE);
            {
                HTMLTable.IRow row = statsTable.newRow ();
                row.newCell ().setText ("total packages:", true);
                row.newCell ().setText ("" + item.getChildCount (), false);
                
                if (m_srcView && m_hasSrcFileInfo)
                {
                    row = statsTable.newRow ();
                    row.newCell ().setText ("total executable files:", true);
                    row.newCell ().setText ("" + item.getAggregate (IItem.TOTAL_SRCFILE_COUNT), false);
                }
                
                row = statsTable.newRow ();
                row.newCell ().setText ("total classes:", true);
                row.newCell ().setText ("" + item.getAggregate (IItem.TOTAL_CLASS_COUNT), true);
                row = statsTable.newRow ();
                row.newCell ().setText ("total methods:", true);
                row.newCell ().setText ("" + item.getAggregate (IItem.TOTAL_METHOD_COUNT), true);
                
                if (m_srcView && m_hasSrcFileInfo && m_hasLineNumberInfo)
                {
                    row = statsTable.newRow ();
                    row.newCell ().setText ("total executable lines:", true);
                    row.newCell ().setText ("" + item.getAggregate (IItem.TOTAL_LINE_COUNT), true);
                }
            }
            /*
            {
                final HTMLTable.IRow first = statsTable.newRow (); // stats always available
                
                first.newCell ().setText ("total packages: " + item.getChildCount (), true);
                first.newCell ().setText ("total classes: " + item.getAggregate (IItem.TOTAL_CLASS_COUNT), true);
                first.newCell ().setText ("total methods: " + item.getAggregate (IItem.TOTAL_METHOD_COUNT), true);
                
                if (m_srcView && m_hasSrcFileInfo)
                {
                    final HTMLTable.IRow second = statsTable.newRow ();
                    
                    final HTMLTable.ICell cell1 = second.newCell ();
                    cell1.setText ("total source files: " + item.getAggregate (IItem.TOTAL_SRCFILE_COUNT), true);
                
                    if (m_hasLineNumberInfo)
                    {
                        final HTMLTable.ICell cell2 = second.newCell ();
                        
                        cell2.setText ("total executable source lines: " + item.getAggregate (IItem.TOTAL_LINE_COUNT), true);
                        cell2.getAttributes ().set (Attribute.COLSPAN, "2");
                    }
                    else
                    {
                        cell1.getAttributes ().set (Attribute.COLSPAN, "3");
                    }
                }
            }
            */
            page.add (statsTable);
            
            final boolean deeper = (m_settings.getDepth () > item.getMetadata ().getTypeID ());
            
            // render package summary tables on the same page:
            
            page.addH (2, "COVERAGE BREAKDOWN BY PACKAGE", null);
            
            final HTMLTable childSummaryTable = new HTMLTable ("100%", null, null, "0");
            {
                int [] headerColumns = null;
                
                boolean odd = true;
                final ItemComparator order = m_typeSortComparators [PackageItem.getTypeMetadata ().getTypeID ()];
                for (Iterator packages = item.getChildren (order); packages.hasNext (); odd = ! odd)
                {
                    final IItem pkg = (IItem) packages.next ();
                    
                    if (headerColumns == null)
                    {
                        // header row:
                        headerColumns = addHeaderRow (pkg, childSummaryTable, columns);                        
                    }
                    
                    // coverage row:
                    String childHREF = null;
                    if (deeper)
                    {
                        childHREF = getItemHREF (item, pkg);
                    }
                    addItemRow (pkg, odd, childSummaryTable, headerColumns, childHREF, false);
                    
                    if (deeper) m_queue.addLast (pkg);
                }
            }
            page.add (childSummaryTable);
            
            
            page.emit (out);            
            out.flush ();
        }
        finally
        {
            if (out != null) out.close ();
            out = null;
        }
        
        return ctx;
    }
    
    public Object visit (final PackageItem item, final Object ctx)
    {
        HTMLWriter out = null;
        try
        {
            if (m_verbose) m_log.verbose ("  report: processing package [" + item.getName () + "] ...");
            
            final File outFile = getItemFile (NESTED_ITEMS_PARENT_DIR, m_reportIDNamespace.getID (getItemKey (item)));
            
            out = openOutFile (Files.newFile (m_settings.getOutDir (), outFile), m_settings.getOutEncoding (), true);
            
            final int [] columns = m_settings.getColumnOrder ();            
            final StringBuffer buf = new StringBuffer ();
            
            // TODO: set title [from a prop?]
            final HTMLDocument page = createPage (REPORT_HEADER_TITLE);
            {
                final IItem [] path = getParentPath (item);
                
                addPageHeader (page, item, path);
                addPageFooter (page, item, path);
            }
                        
            // summary table:
            
            {
                final IElement itemname = IElement.Factory.create (Tag.SPAN);
                itemname.setText (item.getName (), true);
                itemname.setClass (CSS_ITEM_NAME);
                
                final IElementList title = new ElementList ();
                title.add (new Text ("COVERAGE SUMMARY FOR PACKAGE [", true));
                title.add (itemname);
                title.add (new Text ("]", true));
                
                page.addH (1, title, null);
            }
            
            final HTMLTable summaryTable = new HTMLTable ("100%", null, null, "0");
            {
                // header row:
                final HTMLTable.IRow header = summaryTable.newTitleRow ();
                // coverage row:
                final HTMLTable.IRow coverage = summaryTable.newRow ();
                
                for (int c = 0; c < columns.length; ++ c)
                {
                    final int attrID = columns [c];
                    final IItemAttribute attr = item.getAttribute (attrID, m_settings.getUnitsType ());
                    
                    final HTMLTable.ICell headercell = header.newCell ();
                    headercell.setText (attr.getName (), true);
                    
                    if (attr != null)
                    {
                        boolean fail = (m_metrics [attrID] > 0) && ! attr.passes (item, m_metrics [attrID]);
                        
                        buf.setLength (0);
                        attr.format (item, buf);
                        
                        final HTMLTable.ICell cell = coverage.newCell (); 
                        cell.setText (buf.toString (), true);
                        if (fail) cell.setClass (CSS_DATA_HIGHLIGHT);
                    }
                }
            }
            page.add (summaryTable);
            
            final boolean deeper = (m_settings.getDepth () > item.getMetadata ().getTypeID ());
            
            // render child summary tables on the same page:
            
            final String summaryTitle = m_srcView ? "COVERAGE BREAKDOWN BY SOURCE FILE" : "COVERAGE BREAKDOWN BY CLASS";
            page.addH (2, summaryTitle, null);
            
            final HTMLTable childSummaryTable = new HTMLTable ("100%", null, null, "0");
            {
                int [] headerColumns = null;
                
                boolean odd = true;
                final ItemComparator order = m_typeSortComparators [m_srcView ? SrcFileItem.getTypeMetadata ().getTypeID () : ClassItem.getTypeMetadata ().getTypeID ()];                
                for (Iterator srcORclsFiles = item.getChildren (order); srcORclsFiles.hasNext (); odd = ! odd)
                {
                    final IItem srcORcls = (IItem) srcORclsFiles.next ();
                    
                    if (headerColumns == null)
                    {
                        // header row:
                        headerColumns = addHeaderRow (srcORcls, childSummaryTable, columns);                        
                    }
                    
                    // coverage row:
                    String childHREF = null;
                    if (deeper)
                    {
                        childHREF = getItemHREF (item, srcORcls);
                    }
                    addItemRow (srcORcls, odd, childSummaryTable, headerColumns, childHREF, false);
                                        
                    if (deeper) m_queue.addLast (srcORcls);
                }
            }
            page.add (childSummaryTable);
            
            
            page.emit (out);            
            out.flush ();
        }
        finally
        {
            if (out != null) out.close ();
            out = null;
        }
        
        return ctx;
    }

    public Object visit (final SrcFileItem item, final Object ctx)
    {
        // this visit only takes place in src views
        
        HTMLWriter out = null;
        try
        {
            final File outFile = getItemFile (NESTED_ITEMS_PARENT_DIR, m_reportIDNamespace.getID (getItemKey (item)));
            
            out = openOutFile (Files.newFile (m_settings.getOutDir (), outFile), m_settings.getOutEncoding (), true);
            
            final int [] columns = m_settings.getColumnOrder ();            
            final StringBuffer buf = new StringBuffer ();
            
            // TODO: set title [from a prop?]
            final HTMLDocument page = createPage (REPORT_HEADER_TITLE);
            {
                final IItem [] path = getParentPath (item);
                
                addPageHeader (page, item, path);
                addPageFooter (page, item, path);
            }
            
            // summary table:
            
            {
                final IElement itemname = IElement.Factory.create (Tag.SPAN);
                if (item.getParent() instanceof PackageItem && srcFileAvailable(item, m_cache)) {
                    PackageItem packageItem = (PackageItem) item.getParent();
                    // Get the relative path of the source file with respect to the project directory.
                    File src = m_cache.find(packageItem.getVMName(), item.getName());
                    String srcAbsolutePath = src.getAbsolutePath();
                    String projectDirectory = new File(".").getAbsolutePath();

                    // Strip the trailing . at the end of the absolute path.
                    projectDirectory = projectDirectory.substring(0, projectDirectory.length() - 1);
                        
                    if (!srcAbsolutePath.startsWith(projectDirectory)) {
                        throw new RuntimeException(projectDirectory + " should be a prefix of " + srcAbsolutePath);
                    }
                    
                    String relativePath = srcAbsolutePath.substring(projectDirectory.length());

                    itemname.setText (relativePath, true);
                } else {
                    itemname.setText (item.getName(), true);
                }
                itemname.setClass (CSS_ITEM_NAME);
                
                final IElementList title = new ElementList ();
                title.add (new Text ("COVERAGE SUMMARY FOR SOURCE FILE [", true));
                title.add (itemname);
                title.add (new Text ("]", true));
                
                page.addH (1, title, null);
            }
            
            final HTMLTable summaryTable = new HTMLTable ("100%", null, null, "0");
            {
                // header row:
                final HTMLTable.IRow header = summaryTable.newTitleRow ();
                // coverage row:
                final HTMLTable.IRow coverage = summaryTable.newRow ();
                
                for (int c = 0; c < columns.length; ++ c)
                {
                    final int attrID = columns [c];
                    final IItemAttribute attr = item.getAttribute (attrID, m_settings.getUnitsType ());
                    
                    final HTMLTable.ICell headercell = header.newCell ();
                    headercell.setText (attr.getName (), true);
                    
                    if (attr != null)
                    {
                        boolean fail = (m_metrics [attrID] > 0) && ! attr.passes (item, m_metrics [attrID]);
                        
                        buf.setLength (0);
                        attr.format (item, buf);
                        
                        final HTMLTable.ICell cell = coverage.newCell (); 
                        cell.setText (buf.toString (), true);
                        if (fail) cell.setClass (CSS_DATA_HIGHLIGHT);
                    }
                }
            }
            page.add (summaryTable);    
            
            final boolean deeper = (m_settings.getDepth () > ClassItem.getTypeMetadata ().getTypeID ());
            final boolean embedSrcFile = deeper && srcFileAvailable (item, m_cache);
            final boolean createAnchors = embedSrcFile && m_hasLineNumberInfo;
            
            final IDGenerator pageIDNamespace = createAnchors ? new IDGenerator () : null;
            
            // child summary table is special for srcfile items:
            
            page.addH (2, "COVERAGE BREAKDOWN BY CLASS AND METHOD", null);

            final IntObjectMap lineAnchorIDMap = embedSrcFile ? new IntObjectMap () : null;
            final HTMLTable childSummaryTable = new HTMLTable ("100%", null, null, "0");
            
            childSummaryTable.setClass (CSS_CLS_NOLEFT);
            
            {
                int [] headerColumns = null;

                final ItemComparator order = m_typeSortComparators [ClassItem.getTypeMetadata ().getTypeID ()];
                int clsIndex = 0;                
                for (Iterator classes = item.getChildren (order); classes.hasNext (); ++ clsIndex)
                {
                    final ClassItem cls = (ClassItem) classes.next ();

                    if (headerColumns == null)
                    {
                        // header row:
                        headerColumns = addHeaderRow (cls, childSummaryTable, columns);                        
                    }
                    
                    String HREFname = null;
                    
                    // special class subheader:
                    if (createAnchors)
                    {
                        if ($assert.ENABLED)
                        {
                            $assert.ASSERT (lineAnchorIDMap != null);
                            $assert.ASSERT (pageIDNamespace != null);
                        }
                        
                        final String childKey = getItemKey (cls);
                        
                        HREFname = addLineAnchorID (cls.getFirstLine (), pageIDNamespace.getID (childKey), lineAnchorIDMap);
                    }

                    addClassRow (cls, clsIndex, childSummaryTable, headerColumns, HREFname, createAnchors);
                    
//                    // row to separate this class's methods:
//                    final HTMLTable.IRow subheader = childSummaryTable.newTitleRow ();
//                    final HTMLTable.ICell cell = subheader.newCell ();
//                    // TODO: cell.setColspan (???)
//                    cell.setText ("class " + child.getName () + " methods:", true);
                    
                    boolean odd = false;
                    final ItemComparator order2 = m_typeSortComparators [MethodItem.getTypeMetadata ().getTypeID ()];                
                    for (Iterator methods = cls.getChildren (order2); methods.hasNext (); odd = ! odd)
                    {
                        final MethodItem method = (MethodItem) methods.next ();
                        
                        HREFname = null;
                        
                        if (createAnchors)
                        {
                            if ($assert.ENABLED)
                            {
                                $assert.ASSERT (lineAnchorIDMap != null);
                                $assert.ASSERT (pageIDNamespace != null);
                            }
                            
                            final String child2Key = getItemKey (method);
                            
                            HREFname = addLineAnchorID (method.getFirstLine (), pageIDNamespace.getID (child2Key), lineAnchorIDMap);
                        }

                        addClassItemRow (method, odd, childSummaryTable, headerColumns, HREFname, createAnchors);
                    }
                }
            }
            page.add (childSummaryTable);
            
            
            // embed source file:
             
            if (deeper)
            {
                //page.addHR (1);
                page.addEmptyP ();
                {
                    embedSrcFile (item, page, lineAnchorIDMap, m_cache);
                }
                //page.addHR (1);
            }
                
            
            page.emit (out);            
            out.flush ();
        }
        finally
        {
            if (out != null) out.close ();
            out = null;
        }

        return ctx;
    }

    public Object visit (final ClassItem item, final Object ctx)
    {
        // this visit only takes place in class views
        
        HTMLWriter out = null;
        try
        {
            final File outFile = getItemFile (NESTED_ITEMS_PARENT_DIR, m_reportIDNamespace.getID (getItemKey (item)));
            
            // TODO: deal with overwrites
            out = openOutFile (Files.newFile (m_settings.getOutDir (), outFile), m_settings.getOutEncoding (), true);
            
            final int [] columns = m_settings.getColumnOrder ();            
            final StringBuffer buf = new StringBuffer ();
            
            // TODO: set title [from a prop?]
            final HTMLDocument page = createPage (REPORT_HEADER_TITLE);
            {
                final IItem [] path = getParentPath (item);
                
                addPageHeader (page, item, path);
                addPageFooter (page, item, path);
            }
            
            
            // summary table:
            
            {
                final IElement itemname = IElement.Factory.create (Tag.SPAN);
                itemname.setText (item.getName (), true);
                itemname.setClass (CSS_ITEM_NAME);
                
                final IElementList title = new ElementList ();
                title.add (new Text ("COVERAGE SUMMARY FOR CLASS [", true));
                title.add (itemname);
                title.add (new Text ("]", true));
                
                page.addH (1, title, null);
            }
            
            final HTMLTable summaryTable = new HTMLTable ("100%", null, null, "0");
            {
                // header row:
                final HTMLTable.IRow header = summaryTable.newTitleRow ();
                // coverage row:
                final HTMLTable.IRow coverage = summaryTable.newRow ();
                
                for (int c = 0; c < columns.length; ++ c)
                {
                    final int attrID = columns [c];
                    final IItemAttribute attr = item.getAttribute (attrID, m_settings.getUnitsType ());
                    
                    final HTMLTable.ICell headercell = header.newCell ();
                    headercell.setText (attr.getName (), true);
                    
                    if (attr != null)
                    {
                        boolean fail = (m_metrics [attrID] > 0) && ! attr.passes (item, m_metrics [attrID]);
                        
                        buf.setLength (0);
                        attr.format (item, buf);
                        
                        final HTMLTable.ICell cell = coverage.newCell (); 
                        cell.setText (buf.toString (), true);
                        if (fail) cell.setClass (CSS_DATA_HIGHLIGHT);
                    }
                }
            }
            page.add (summaryTable);
            
            
            // child summary table:
            
            page.addH (2, "COVERAGE BREAKDOWN BY METHOD", null);

            final HTMLTable childSummaryTable = new HTMLTable ("100%", null, null, "0");
            {
                int [] headerColumns = null;
                
                boolean odd = true;
                final ItemComparator order = m_typeSortComparators [MethodItem.getTypeMetadata ().getTypeID ()];                
                for (Iterator methods = item.getChildren (order); methods.hasNext (); odd = ! odd)
                {
                    final MethodItem method = (MethodItem) methods.next ();

                    if (headerColumns == null)
                    {
                        // header row:
                        headerColumns = addHeaderRow (method, childSummaryTable, columns);                        
                    }
                    
                    addItemRow (method, odd, childSummaryTable, headerColumns, null, false);
                }
            }
            page.add (childSummaryTable);
            
            
            page.emit (out);            
            out.flush ();
        }
        finally
        {
            if (out != null) out.close ();
            out = null;
        }

        return ctx;
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private static final class IDGenerator
    {
        IDGenerator ()
        {
            m_namespace = new ObjectIntMap (101);
            m_out = new int [1];
        }
        
        IDGenerator (final int initialCapacity)
        {
            m_namespace = new ObjectIntMap (initialCapacity);
            m_out = new int [1];
        }
        
        String getID (final String key)
        {
            final int [] out = m_out;
            final int ID;
            
            if (m_namespace.get (key, out))
                ID = out [0];
            else
            {
                ID = m_namespace.size ();
                m_namespace.put (key, ID);
            }
            
            return Integer.toHexString (ID);
        }
        
        private final ObjectIntMap /* key:String->ID */ m_namespace;
        private final int [] m_out;
        
    } // end of nested class
    
    
    private HTMLDocument createPage (final String title)
    {
        final HTMLDocument page = new HTMLDocument (title, m_settings.getOutEncoding ());
        page.addStyle (CSS); // TODO: split by visit type
        
        return page;
    }
    
    private IElement addPageHeader (final HTMLDocument page, final IItem item, final IItem [] path)
    {
        // TODO: merge header and footer in the same method
        
        if ($assert.ENABLED)
        {
            $assert.ASSERT (page != null);
        }
        
        final HTMLTable header = new HTMLTable ("100%", null, null, "0");
        header.setClass (CSS_HEADER_FOOTER);

        // header row:        
        addPageHeaderTitleRow (header);
        
        // nav row:
        {
            final HTMLTable.IRow navRow = header.newRow ();
            
            final HTMLTable.ICell cell = navRow.newCell ();
            cell.setClass (CSS_NAV);
            
            final int lLimit = path.length > 1 ? path.length - 1 : path.length;
            for (int l = 0; l < lLimit; ++ l)
            {
                cell.add (LEFT_BRACKET);
                
                final String name = path [l].getName ();
                final String HREF = getItemHREF (item, path [l]);
                cell.add (new HyperRef (HREF, name, true));
                
                cell.add (RIGHT_BRACKET);
            }
        }
        
        page.setHeader (header);
        
        return header;
    }
    
    private IElement addPageFooter (final HTMLDocument page, final IItem item, final IItem [] path)
    {
        if ($assert.ENABLED)
        {
            $assert.ASSERT (page != null);
        }

        final HTMLTable footerTable = new HTMLTable ("100%", null, null, "0");
        footerTable.setClass (CSS_HEADER_FOOTER);

        // nav row:
        {
            final HTMLTable.IRow navRow = footerTable.newRow ();
            
            final HTMLTable.ICell cell = navRow.newCell ();
            cell.setClass (CSS_NAV);
            
            final int lLimit = path.length > 1 ? path.length - 1 : path.length;
            for (int l = 0; l < lLimit; ++ l)
            {
                cell.add (LEFT_BRACKET);
                
                final String name = path [l].getName ();
                final String HREF = getItemHREF (item, path [l]);
                cell.add (new HyperRef (HREF, name, true));
                
                cell.add (RIGHT_BRACKET);
            }
        }
        
        // title row:
        {
            final HTMLTable.IRow titleRow = footerTable.newRow ();
            
            final HTMLTable.ICell cell = titleRow.newCell ();
            cell.setClass (CSS_TITLE);
            
            cell.add (getFooterBottom ());
        }
        
        final ElementList footer = new ElementList ();
        
        footer.add (IElement.Factory.create (Tag.P)); // spacer
        footer.add (footerTable);
        
        page.setFooter (footer);
        
        return footerTable;
    }
    
    private int [] addHeaderRow (final IItem item, final HTMLTable table, final int [] columns)
    {
        if ($assert.ENABLED)
        {
            $assert.ASSERT (item != null, "null input: item");
            $assert.ASSERT (table != null, "null input: table");
            $assert.ASSERT (columns != null, "null input: columns");
        }
        
        // header row:
        final HTMLTable.IRow header = table.newTitleRow ();
        
        // determine the set of columns actually present in the header [may be narrower than 'columns']:
        final IntVector headerColumns = new IntVector (columns.length);
        
        for (int c = 0; c < columns.length; ++ c)
        {
            final int attrID = columns [c];
            final IItemAttribute attr = item.getAttribute (attrID, m_settings.getUnitsType ());
            
            if (attr != null)
            {
                final HTMLTable.ICell cell = header.newCell ();
            
                cell.setText (attr.getName (), true);//.getAttributes ().set (Attribute.WIDTH, "20%");
                cell.setClass (headerCellStyle (c));
                headerColumns.add (attrID);
            }
            
            // note: by design this does not create columns for nonexistent attribute types
        }
        
        return headerColumns.values ();
    }
    
    /*
     * No header row, just data rows.
     */
    private void addItemRow (final IItem item, final boolean odd, final HTMLTable table, final int [] columns, final String nameHREF, final boolean anchor)
    {
        if ($assert.ENABLED)
        {
            $assert.ASSERT (item != null, "null input: item");
            $assert.ASSERT (table != null, "null input: table");
            $assert.ASSERT (columns != null, "null input: columns");
        }
        
        final HTMLTable.IRow row = table.newRow ();
        if (odd) row.setClass (CSS_ODDROW);
        
        final StringBuffer buf = new StringBuffer (11); // TODO: reuse a buffer
        
        for (int c = 0; c < columns.length; ++ c)
        {
            final int attrID = columns [c];
            final IItemAttribute attr = item.getAttribute (attrID, m_settings.getUnitsType ());
            
            if (attr != null)
            {
                final HTMLTable.ICell cell = row.newCell ();
                
                if ((nameHREF != null) && (attrID == IItemAttribute.ATTRIBUTE_NAME_ID))
                {
                    buf.setLength (0);
                    attr.format (item, buf);
                    
                    trimForDisplay (buf);
                    
                    final String fullHREFName = anchor ? "#".concat (nameHREF) : nameHREF; 
                    
                    cell.add (new HyperRef (fullHREFName, buf.toString (), true));
                }
                else
                {
                    final boolean fail = (m_metrics [attrID] > 0) && ! attr.passes (item, m_metrics [attrID]);
                    
                    buf.setLength (0);
                    attr.format (item, buf);
                    
                    trimForDisplay (buf);
                     
                    cell.setText (buf.toString (), true);
                    if (fail) cell.setClass (CSS_DATA_HIGHLIGHT);
                }
            }
            else
            {
                // note: by design this puts empty cells for nonexistent attribute types
                
                final HTMLTable.ICell cell = row.newCell (); 
                cell.setText (" ", true);
            }
        }
    }
    
    private void addClassRow (final ClassItem item, final int clsIndex, final HTMLTable table, final int [] columns,
                              final String itemHREF, final boolean isAnchor)
    {
        if ($assert.ENABLED)
        {
            $assert.ASSERT (item != null, "null input: item");
            $assert.ASSERT (table != null, "null input: table");
            $assert.ASSERT (columns != null, "null input: columns");
        }
        
        final HTMLTable.IRow blank = table.newRow ();
        
        final HTMLTable.IRow row = table.newRow ();
        row.setClass (CSS_CLASS_ITEM_SPECIAL);
        
        final StringBuffer buf = new StringBuffer (11);
        
        for (int c = 0; c < columns.length; ++ c)
        {
            final int attrID = columns [c];
            final IItemAttribute attr = item.getAttribute (attrID, m_settings.getUnitsType ());
            
            if (attr != null)
            {
                buf.setLength (0);
                attr.format (item, buf);
                
                final HTMLTable.ICell blankcell = blank.newCell ();
                blankcell.setClass (clsIndex == 0 ? CSS_BLANK : CSS_BOTTOM);
                blankcell.setText (" ", true);
                
                final HTMLTable.ICell cell = row.newCell ();
                
                boolean fail = false;
                if (attrID == IItemAttribute.ATTRIBUTE_NAME_ID)
                {
                    if (itemHREF != null)
                    {
                        final String fullItemHREF = isAnchor ? "#".concat (itemHREF) : itemHREF;
                        
                        cell.add (new Text ("class ", true));
                        cell.add (new HyperRef (fullItemHREF, buf.toString (), true));
                    }
                    else
                    {
                        cell.setText ("class " + buf.toString (), true);
                    }
                }
                else
                {
                    fail = (m_metrics [attrID] > 0) && ! attr.passes (item, m_metrics [attrID]);
                    
                    cell.setText (buf.toString (), true);
                }
                
                cell.setClass (dataCellStyle (c, fail));
            }
            else
            {
                final HTMLTable.ICell cell = row.newCell (); 
                cell.setText (" ", true);
                cell.setClass (dataCellStyle (c, false));
            }
        }
    }
    
    
    private void addClassItemRow (final IItem item, final boolean odd, final HTMLTable table, final int [] columns, final String nameHREF, final boolean anchor)
    {
        if ($assert.ENABLED)
        {
            $assert.ASSERT (item != null, "null input: item");
            $assert.ASSERT (table != null, "null input: table");
            $assert.ASSERT (columns != null, "null input: columns");
        }
        
        final HTMLTable.IRow row = table.newRow ();
        if (odd) row.setClass (CSS_ODDROW);
        
        final StringBuffer buf = new StringBuffer (11); // TODO: reuse a buffer
        
        for (int c = 0; c < columns.length; ++ c)
        {
            final int attrID = columns [c];
            final IItemAttribute attr = item.getAttribute (attrID, m_settings.getUnitsType ());
            
            if (attr != null)
            {
                final HTMLTable.ICell cell = row.newCell ();
                
                boolean fail = false;
                if ((nameHREF != null) && (attrID == IItemAttribute.ATTRIBUTE_NAME_ID))
                {
                    buf.setLength (0);
                    attr.format (item, buf);
                    
                    trimForDisplay (buf);
                    
                    final String fullHREFName = anchor ? "#".concat (nameHREF) : nameHREF; 
                    
                    cell.add (new HyperRef (fullHREFName, buf.toString (), true));
                }
                else
                {
                    fail = (m_metrics [attrID] > 0) && ! attr.passes (item, m_metrics [attrID]);
                    
                    buf.setLength (0);
                    attr.format (item, buf);
                    
                    trimForDisplay (buf);
                     
                    cell.setText (buf.toString (), true);
                }
                
                cell.setClass (dataCellStyle (c, fail));
            }
            else
            {
                // note: by design this puts empty cells for nonexistent attribute types
                
                final HTMLTable.ICell cell = row.newCell (); 
                cell.setText (" ", true);
                cell.setClass (dataCellStyle (c, false));
            }
        }
    }
    
    
    private boolean srcFileAvailable (final SrcFileItem item, final SourcePathCache cache)
    {
        if (cache == null) return false;
        
        if ($assert.ENABLED) $assert.ASSERT (item != null, "null input: item");
        
        final String fileName = item.getName ();
        if ($assert.ENABLED) $assert.ASSERT (fileName.endsWith (".java"), "cache only handles .java extensions");
        
        // TODO: should I keep VM names in package items?
        final String packageVMName = ((PackageItem) item.getParent ()).getVMName ();
        
        return (cache.find (packageVMName, fileName) != null);
    }
    
//    private boolean srcFileAvailable (final ClassItem item, final SourcePathCache cache)
//    {
//        if (cache == null) return false;
//        
//        if ($assert.ENABLED) $assert.ASSERT (item != null, "null input: item");
//        
//        final String fileName = item.getSrcFileName ();
//        if ($assert.ENABLED) $assert.ASSERT (fileName.endsWith (".java"), "cache only handles .java extensions");
//        
//        // TODO: should I keep VM names in package items?
//        final String packageVMName = ((PackageItem) item.getParent ()).getVMName ();
//        
//        return (cache.find (packageVMName, fileName) != null);
//    }
    
    private void embedSrcFile (final SrcFileItem item, final HTMLDocument page,
                               final IntObjectMap /* line num:int->anchor name:String */anchorMap,
                               final SourcePathCache cache)
    {
        if ($assert.ENABLED)
        {
            $assert.ASSERT (item != null, "null input: item");
            $assert.ASSERT (page != null, "null input: page");
        }
        
        final String fileName = item.getName ();
        if ($assert.ENABLED) $assert.ASSERT (fileName.endsWith (".java"), "cache only handles .java extensions");
        
        // TODO: should I keep VM names in package items?
        final String packageVMName = ((PackageItem) item.getParent ()).getVMName ();
        
        boolean success = false;

        final HTMLTable srcTable = new HTMLTable ("100%", null, null, "0");
        
        if (cache != null) // TODO: do this check earlier, in outer scope
        {
            srcTable.setClass (CSS_SOURCE);
            final File srcFile = cache.find (packageVMName, fileName);
            
            if (srcFile != null)
            {
                BufferedReader in = null;
                try
                {
                    in = new BufferedReader (new FileReader (srcFile), IO_BUF_SIZE);
                    
                    final boolean markupCoverage = m_hasLineNumberInfo;
                    
                    final int unitsType = m_settings.getUnitsType ();
                    IntObjectMap /* line num:int -> SrcFileItem.LineCoverageData */ lineCoverageMap = null;
                    StringBuffer tooltipBuffer = null;
                    
                    
                    if (markupCoverage)
                    {
                        lineCoverageMap = item.getLineCoverage ();
                        $assert.ASSERT (lineCoverageMap != null, "null: lineCoverageMap");
                        
                        tooltipBuffer = new StringBuffer (64);
                    }
                    
                    int l = 1;
                    for (String line; (line = in.readLine ()) != null; ++ l)
                    {
                        final HTMLTable.IRow srcline = srcTable.newRow ();
                        final HTMLTable.ICell lineNumCell = srcline.newCell ();
                        lineNumCell.setClass (CSS_LINENUM);
                        
                        if (anchorMap != null)
                        {
                            final int adjustedl = l < SRC_LINE_OFFSET ? l : l + SRC_LINE_OFFSET;
                            
                            final String anchor = (String) anchorMap.get (adjustedl);
                            if (anchor != null)
                            {
                                final IElement a = IElement.Factory.create (Tag.A);
                                //a.getAttributes ().set (Attribute.ID, anchor); ID anchoring does not work in NS 4.0
                                a.getAttributes ().set (Attribute.NAME, anchor);
                                
                                a.setText (Integer.toString (l), true);
                                
                                lineNumCell.add (a);
                            }
                            else
                            {
                                lineNumCell.setText (Integer.toString (l), true);
                            }
                        }
                        else
                        {   
                            lineNumCell.setText (Integer.toString (l), true);
                        }
                        
                        final HTMLTable.ICell lineTxtCell = srcline.newCell ();
                        lineTxtCell.setText (line.length () > 0 ? line : " ", true);
                        
                        if (markupCoverage)
                        {
                            final SrcFileItem.LineCoverageData lCoverageData = (SrcFileItem.LineCoverageData) lineCoverageMap.get (l);
                            
                            if (lCoverageData != null)
                            {
                                switch (lCoverageData.m_coverageStatus)
                                {
                                    case SrcFileItem.LineCoverageData.LINE_COVERAGE_ZERO:
                                        srcline.setClass (CSS_COVERAGE_ZERO);
                                    break;
                                    
                                    case SrcFileItem.LineCoverageData.LINE_COVERAGE_PARTIAL:
                                    {
                                        srcline.setClass (CSS_COVERAGE_PARTIAL);
                                        
                                        if (USE_LINE_COVERAGE_TOOLTIPS)
                                        {
                                            tooltipBuffer.setLength (0);
                                            
                                            final int [] coverageRatio = lCoverageData.m_coverageRatio [unitsType];
                                            
                                            final int d = coverageRatio [0];
                                            final int n = coverageRatio [1];

                                            m_format.format ((double) n / d, tooltipBuffer, m_fieldPosition);
                                            
                                            tooltipBuffer.append (" line coverage (");
                                            tooltipBuffer.append (n);
                                            tooltipBuffer.append (" out of ");
                                            tooltipBuffer.append (d);

                                            switch (unitsType)
                                            {
                                                case IItemAttribute.UNITS_COUNT:
                                                    tooltipBuffer.append (" basic blocks)");
                                                break;
                                                
                                                case IItemAttribute.UNITS_INSTR:
                                                    tooltipBuffer.append (" instructions)");
                                                break;
                                            }                                            
                                            
                                            // [Opera does not display TITLE tooltios on <TR> elements]
                                            
                                            lineNumCell.getAttributes ().set (Attribute.TITLE, tooltipBuffer.toString ());
                                            lineTxtCell.getAttributes ().set (Attribute.TITLE, tooltipBuffer.toString ());
                                        }
                                    }
                                    break;
                                        
                                    case SrcFileItem.LineCoverageData.LINE_COVERAGE_COMPLETE:
                                        srcline.setClass (CSS_COVERAGE_COMPLETE);
                                    break;
                                    
                                    default: $assert.ASSERT (false, "invalid line coverage status: " + lCoverageData.m_coverageStatus);
                                    
                                } // end of switch
                            }
                        }
                    }
                    
                    success = true;
                }
                catch (Throwable t)
                {
                    t.printStackTrace (System.out); // TODO: logging
                    success = false;
                }
                finally
                {
                    if (in != null) try { in.close (); } catch (Throwable ignore) {}
                    in = null;
                }
            }
        }
        
        if (! success)
        {
            srcTable.setClass (CSS_INVISIBLE_TABLE);
            
            final HTMLTable.IRow row = srcTable.newTitleRow ();
            row.newCell ().setText ("[source file '" + Descriptors.combineVMName (packageVMName, fileName) + "' not found in sourcepath]", false);
        }
        
        page.add (srcTable);
    }
    
    
    private static String addLineAnchorID (final int line, final String anchorID,
                                           final IntObjectMap /* line num:int->anchorID:String */lineAnchorIDMap)
    {
        if (line > 0)
        {
            final String _anchorID = (String) lineAnchorIDMap.get (line);
            if (_anchorID != null)
                return _anchorID;
            else
            {
                lineAnchorIDMap.put (line, anchorID);

                return anchorID;
            }
        }
        
        return null;
    }
    
    /*
     * Always includes AllItem
     */
    private IItem [] getParentPath (IItem item)
    {
        final LinkedList /* IItem */ _result = new LinkedList ();
        
        for ( ; item != null; item = item.getParent ())
        {
            _result.add (item);
        }
        
        final IItem [] result = new IItem [_result.size ()];
        int j = result.length - 1;
        for (Iterator i = _result.iterator (); i.hasNext (); -- j)
        {
            result [j] = (IItem) i.next ();
        }
        
        return result;
    }

    /*
     * 
     */
    private String getItemHREF (final IItem base, final IItem item)
    {
        final String itemHREF;
        if (item instanceof AllItem)
            itemHREF = m_settings.getOutFile ().getName (); // note that this is always a simple filename [no parent path]
        else
            itemHREF = m_reportIDNamespace.getID (getItemKey (item)).concat (FILE_EXTENSION);
             
        final String fullHREF;
        
        if (base == null)
            fullHREF = itemHREF;
        else
        {
            final int nesting = NESTING [base.getMetadata ().getTypeID ()] [item.getMetadata ().getTypeID ()];
            if (nesting == 1)
                fullHREF = NESTED_ITEMS_PARENT_DIRNAME.concat ("/").concat (itemHREF);
            else if (nesting == -1)
                fullHREF = "../".concat (itemHREF);
            else
                fullHREF = itemHREF;
        }
        
        return fullHREF;
    }
    
    
    private IContent getPageTitle ()
    { 
        IContent title = m_pageTitle;
        if (title == null)
        {
            final IElementList _title = new ElementList ();
            
            _title.add (new HyperRef (IAppConstants.APP_HOME_SITE_LINK, IAppConstants.APP_NAME, true));
            
            final StringBuffer s = new StringBuffer (" Coverage Report (generated ");
            s.append (new Date (EMMAProperties.getTimeStamp ()));
            s.append (')');
            
            _title.add (new Text (s.toString (), true));
            
            m_pageTitle = title = _title;
        }
        
        return title;
    }
    
    private IContent getFooterBottom ()
    { 
        IContent bottom = m_footerBottom;
        if (bottom == null)
        {
            final IElementList _bottom = new ElementList ();
            
            _bottom.add (new HyperRef (IAppConstants.APP_BUG_REPORT_LINK, IAppConstants.APP_NAME + " " + IAppConstants.APP_VERSION_WITH_BUILD_ID_AND_TAG, true));
            _bottom.add (new Text (" " + IAppConstants.APP_COPYRIGHT, true));
            
            m_footerBottom = bottom = _bottom;
        }
        
        return bottom;
    }
    
    private void addPageHeaderTitleRow (final HTMLTable header)
    {
        final HTMLTable.IRow titleRow = header.newTitleRow ();
        
        final HTMLTable.ICell cell = titleRow.newCell ();
        cell.setClass (CSS_TITLE);
        cell.add (getPageTitle ());
    }
    
    private static void trimForDisplay (final StringBuffer buf)
    {
        if (buf.length () > MAX_DISPLAY_NAME_LENGTH)
        {
            buf.setLength (MAX_DISPLAY_NAME_LENGTH - 3);
            buf.append ("...");
        }
    }
    
    /*
     * Assumes relative pathnames.
     */
    private static File getItemFile (final File parentDir, final String itemKey)
    {
        if (parentDir == null)
            return new File (itemKey.concat (FILE_EXTENSION));
        else
            return new File (parentDir, itemKey.concat (FILE_EXTENSION));
    }

    private static String getItemKey (IItem item)
    {
        final StringBuffer result = new StringBuffer ();
        
        for ( ; item != null; item = item.getParent ())
        {
            result.append (item.getName ());
            result.append (':');
        }
        
        return result.toString ();
    }

    private static HTMLWriter openOutFile (final File file, final String encoding, final boolean mkdirs)
    {
        BufferedWriter out = null;
        try
        {
            if (mkdirs)
            {
                final File parent = file.getParentFile ();
                if (parent != null) parent.mkdirs ();
            }
            
            out = new BufferedWriter (new OutputStreamWriter (new FileOutputStream (file), encoding), IO_BUF_SIZE);
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
        
        return new HTMLWriter (out);
    }
    
    private static String dataCellStyle (final int column, final boolean highlight)
    {
        if (column == 0)
            return highlight ? CSS_DATA_HIGHLIGHT_FIRST : CSS_DATA_FIRST;
        else
            return highlight ? CSS_DATA_HIGHLIGHT : CSS_DATA;
    }
    
    private static String headerCellStyle (final int column)
    {
        return (column == 0) ? CSS_HEADER_FIRST : CSS_HEADER;
    }

    
    private final DecimalFormat m_format;
    private final FieldPosition m_fieldPosition;
        
    private LinkedList /* IITem */ m_queue;
    private IDGenerator m_reportIDNamespace;
    
    private IContent m_pageTitle, m_footerBottom;
    
    private static final boolean USE_LINE_COVERAGE_TOOLTIPS = true;
    
    private static final String TYPE = "html";
    private static final String REPORT_HEADER_TITLE = IAppConstants.APP_NAME + " Coverage Report";
    private static final IContent LEFT_BRACKET = new Text ("[", false);
    private static final IContent RIGHT_BRACKET = new Text ("]", false);

    private static final int MAX_DISPLAY_NAME_LENGTH = 80;
    private static final int SRC_LINE_OFFSET = 4;
    
    
    private static final String CSS_HEADER_FOOTER       = "hdft";
    private static final String CSS_TITLE               = "tl";
    private static final String CSS_NAV                 = "nv";
    
    private static final String CSS_COVERAGE_ZERO       = "z";
    private static final String CSS_COVERAGE_PARTIAL    = "p";
    private static final String CSS_COVERAGE_COMPLETE   = "c";
    
    private static final String DARKER_BACKGROUND   = "#F0F0F0";
    private static final String TITLE_BACKGROUND    = "#6699CC";
    private static final String NAV_BACKGROUND      = "#6633DD";
    
    private static final String CSS_INVISIBLE_TABLE     = "it";
    
    private static final String CSS_ITEM_NAME           = "in";
    
    private static final String CSS_CLASS_ITEM_SPECIAL  = "cis";
    
    private static final String CSS_SOURCE              = "s";
    private static final String CSS_LINENUM             = "l";
    
    private static final String CSS_BOTTOM              = "bt";
    private static final String CSS_ODDROW              = "o";
    private static final String CSS_BLANK               = "b";
    
    private static final String CSS_DATA = "";
    private static final String CSS_DATA_HIGHLIGHT = CSS_DATA + "h";
    private static final String CSS_DATA_FIRST = CSS_DATA + "f";
    private static final String CSS_DATA_HIGHLIGHT_FIRST = CSS_DATA + "hf";
    private static final String CSS_HEADER = "";
    private static final String CSS_HEADER_FIRST = CSS_HEADER + "f";
    
    private static final String CSS_CLS_NOLEFT          = "cn";
    
    // TODO: optimize this
    
    private static final String CSS =
        " TABLE,TD,TH {border-style:solid; border-color:black;}" +
        " TD,TH {background:white;margin:0;line-height:100%;padding-left:0.5em;padding-right:0.5em;}" +
        
        " TD {border-width:0 1px 0 0;}" +
        " TH {border-width:1px 1px 1px 0;}" +
        " TR TD." + CSS_DATA_HIGHLIGHT + " {color:red;}" +

        " TABLE {border-spacing:0; border-collapse:collapse;border-width:0 0 1px 1px;}" +
            
        " P,H1,H2,H3,TH {font-family:verdana,arial,sans-serif;font-size:10pt;}" +
        " TD {font-family:courier,monospace;font-size:10pt;}" + 
         
        " TABLE." + CSS_HEADER_FOOTER + " {border-spacing:0;border-collapse:collapse;border-style:none;}" + 
        " TABLE." + CSS_HEADER_FOOTER + " TH,TABLE." + CSS_HEADER_FOOTER + " TD {border-style:none;line-height:normal;}" +
        
        " TABLE." + CSS_HEADER_FOOTER + " TH." + CSS_TITLE + ",TABLE." + CSS_HEADER_FOOTER + " TD." + CSS_TITLE + " {background:" + TITLE_BACKGROUND + ";color:white;}" +
        " TABLE." + CSS_HEADER_FOOTER + " TD." + CSS_NAV + " {background:" + NAV_BACKGROUND + ";color:white;}" +
                
        " ." + CSS_NAV + " A:link {color:white;}" +
        " ." + CSS_NAV + " A:visited {color:white;}" +
        " ." + CSS_NAV + " A:active {color:yellow;}" +
        
        " TABLE." + CSS_HEADER_FOOTER + " A:link {color:white;}" +
        " TABLE." + CSS_HEADER_FOOTER + " A:visited {color:white;}" +
        " TABLE." + CSS_HEADER_FOOTER + " A:active {color:yellow;}" +
        
        //" ." + CSS_ITEM_NAME + " {color:#6633FF;}" + 
        //" ." + CSS_ITEM_NAME + " {color:#C000E0;}" +
        " ." + CSS_ITEM_NAME + " {color:#356085;}" +
        
        //" A:hover {color:#0066FF; text-decoration:underline; font-style:italic}" + 
        
        " TABLE." + CSS_SOURCE + " TD {padding-left:0.25em;padding-right:0.25em;}" +
        " TABLE." + CSS_SOURCE + " TD." + CSS_LINENUM + " {padding-left:0.25em;padding-right:0.25em;text-align:right;background:" + DARKER_BACKGROUND + ";}" +
        " TABLE." + CSS_SOURCE + " TR." + CSS_COVERAGE_ZERO + " TD {background:#FF9999;}" +
        " TABLE." + CSS_SOURCE + " TR." + CSS_COVERAGE_PARTIAL + " TD {background:#FFFF88;}" +
        " TABLE." + CSS_SOURCE + " TR." + CSS_COVERAGE_COMPLETE + " TD {background:#CCFFCC;}" +
        
        " A:link {color:#0000EE;text-decoration:none;}" + 
        " A:visited {color:#0000EE;text-decoration:none;}" +
        " A:hover {color:#0000EE;text-decoration:underline;}" +
        
        " TABLE." + CSS_CLS_NOLEFT + " {border-width:0 0 1px 0;}" +
        " TABLE." + CSS_SOURCE + " {border-width:1px 0 1px 1px;}" +
        
//        " TD {border-width: 0px 1px 0px 0px; }" +
        " TD." + CSS_DATA_HIGHLIGHT + " {color:red;border-width:0 1px 0 0;}" +
        " TD." + CSS_DATA_FIRST + " {border-width:0 1px 0 1px;}" +
        " TD." + CSS_DATA_HIGHLIGHT_FIRST + " {color:red;border-width:0 1px 0 1px;}" +

//        " TH {border-width: 1px 1px 1px 0px; }" +        
        " TH." + CSS_HEADER_FIRST + " {border-width:1px 1px 1px 1px;}" +

        " TR." + CSS_CLASS_ITEM_SPECIAL + " TD {background:" + DARKER_BACKGROUND + ";}" +
        " TR." + CSS_CLASS_ITEM_SPECIAL + " TD {border-width:1px 1px 1px 0;}" +
        " TR." + CSS_CLASS_ITEM_SPECIAL + " TD." + CSS_DATA_HIGHLIGHT + " {color:red;border-width:1px 1px 1px 0;}" +        
        " TR." + CSS_CLASS_ITEM_SPECIAL + " TD." + CSS_DATA_FIRST + " {border-width:1px 1px 1px 1px;}" +
        " TR." + CSS_CLASS_ITEM_SPECIAL + " TD." + CSS_DATA_HIGHLIGHT_FIRST + " {color:red;border-width:1px 1px 1px 1px;}" +
        
        " TD." + CSS_BLANK + " {border-style:none;background:transparent;line-height:50%;} " +         
        " TD." + CSS_BOTTOM + " {border-width:1px 0 0 0;background:transparent;line-height:50%;}" +
        " TR." + CSS_ODDROW + " TD {background:" + DARKER_BACKGROUND + ";}" +
        
        "TABLE." + CSS_INVISIBLE_TABLE + " {border-style:none;}" +
        "TABLE." + CSS_INVISIBLE_TABLE + " TD,TABLE." + CSS_INVISIBLE_TABLE + " TH {border-style:none;}" +
        
        "";

    private static final String NESTED_ITEMS_PARENT_DIRNAME = "_files";
    private static final File NESTED_ITEMS_PARENT_DIR = new File (NESTED_ITEMS_PARENT_DIRNAME);
    private static final int [][] NESTING; // set in <clinit>; this reflects the dir structure for the report
    
    private static final String FILE_EXTENSION = ".html";
    private static final int IO_BUF_SIZE = 32 * 1024;
    
    private static final long [] ATTRIBUTE_SETS; // set in <clinit>
    
    static
    {
        final IItemMetadata [] allTypes = IItemMetadata.Factory.getAllTypes (); 
        
        ATTRIBUTE_SETS = new long [allTypes.length];
        for (int t = 0; t < allTypes.length; ++ t)
        {
            ATTRIBUTE_SETS [allTypes [t].getTypeID ()] = allTypes [t].getAttributeIDs ();
        }
        
        NESTING = new int [4][4];
        
        int base = AllItem.getTypeMetadata().getTypeID (); 
        NESTING [base][PackageItem.getTypeMetadata ().getTypeID ()] = 1;
        NESTING [base][SrcFileItem.getTypeMetadata ().getTypeID ()] = 1;
        NESTING [base][ClassItem.getTypeMetadata ().getTypeID ()] = 1;
        
        base = PackageItem.getTypeMetadata().getTypeID ();
        NESTING [base][AllItem.getTypeMetadata ().getTypeID ()] = -1;
        
        base = SrcFileItem.getTypeMetadata().getTypeID ();
        NESTING [base][AllItem.getTypeMetadata ().getTypeID ()] = -1;
        
        base = ClassItem.getTypeMetadata().getTypeID ();
        NESTING [base][AllItem.getTypeMetadata ().getTypeID ()] = -1;
    }

} // end of class
// ----------------------------------------------------------------------------
