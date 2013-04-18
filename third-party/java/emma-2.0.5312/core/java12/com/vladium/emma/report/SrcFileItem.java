/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: SrcFileItem.java,v 1.1.1.1.2.1 2004/06/20 20:07:22 vlad_r Exp $
 */
package com.vladium.emma.report;

import java.util.Iterator;

import com.vladium.util.IntObjectMap;
import com.vladium.util.asserts.$assert;
import com.vladium.emma.data.ClassDescriptor;
import com.vladium.emma.data.MethodDescriptor;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
final class SrcFileItem extends Item
{
    // public: ................................................................
    
    
    public final class LineCoverageData
    {
        public static final int LINE_COVERAGE_ZERO = 0;
        public static final int LINE_COVERAGE_PARTIAL = 1;
        public static final int LINE_COVERAGE_COMPLETE = 2;
        
        public final int m_coverageStatus;
        public final int [/* units mode */][/* total, coverage */] m_coverageRatio; // not null in LINE_COVERAGE_PARTIAL status only]
        
        LineCoverageData (final int coverageStatus, final int [][] coverageRatio)
        {
            m_coverageStatus = coverageStatus;
            m_coverageRatio = coverageRatio;
        }
        
    } // end of nested class
    

    public SrcFileItem (final IItem parent, final String name, final String fullVMName)
    {
        super (parent);
        
        m_name = name;
        m_fullVMName = fullVMName;
    }
    
    public String getName ()
    {
        return m_name;
    }
    
    public String getFullVMName ()
    {
        return m_fullVMName;
    }
    
    public int getFirstLine ()
    {
        // TODO: state validation
        
        if (m_firstLine == 0)
        {
            getAggregate (TOTAL_LINE_COUNT); // fault line coverage calculation
        }
        
        return m_firstLine;
    }

    
    
    
    public IntObjectMap /* line_no:int -> LineCoverageData */ getLineCoverage ()
    {
        if (m_lineCoverage == null)
        {
            getAggregate (TOTAL_LINE_COUNT); // fault line coverage calculation
        }   
        
        return m_lineCoverage;
    }
    
        
    public int getAggregate (final int type)
    {
        final int [] aggregates = m_aggregates;

        int value = aggregates [type];
        
        if (value < 0)
        {
            switch (type)
            {
                case COVERAGE_CLASS_COUNT:
                case    TOTAL_CLASS_COUNT:
                {                    
                    aggregates [TOTAL_CLASS_COUNT] = getChildCount ();
                    
                    value = 0;
                    for (Iterator children = getChildren (); children.hasNext (); )
                    {
                        // SF BUG 972725: this was incorrectly using 'type' instead
                        // of the COVERAGE_CLASS_COUNT aggregate type, making class
                        // coverage computation dependent on the order of how item
                        // nodes were traversed in report generators
                        value += ((IItem) children.next ()).getAggregate (COVERAGE_CLASS_COUNT);
                    }
                    aggregates [COVERAGE_CLASS_COUNT] = value;

                    return aggregates [type];
                }
                //break;
                
                
                case TOTAL_SRCFILE_COUNT:
                {
                    return aggregates [TOTAL_SRCFILE_COUNT] = 1;
                }
                //break;
                
                
                case COVERAGE_LINE_COUNT:
                case    TOTAL_LINE_COUNT:
                
                case COVERAGE_LINE_INSTR:
                {
                    // line aggregate types are special when used on srcfile items:
                    // unlike all others, they do not simply add up when the line
                    // info is available; instead, lines from all classes belonging
                    // to the same srcfile parent are set-merged 
                    
                    final IntObjectMap /* line -> int[2] */ fldata = new IntObjectMap ();
                    
                    for (Iterator classes = getChildren (); classes.hasNext (); )
                    {
                        final ClassItem cls = (ClassItem) classes.next ();
                    
                        final boolean [][] ccoverage = cls.getCoverage (); // this can be null
                        final ClassDescriptor clsdesc = cls.getClassDescriptor ();
                        final MethodDescriptor [] methoddescs = clsdesc.getMethods ();
                        
                        for (Iterator methods = cls.getChildren (); methods.hasNext (); )
                        {
                            final MethodItem method = (MethodItem) methods.next ();
                            final int methodID = method.getID ();
                            
                            final boolean [] mcoverage = ccoverage == null ? null : ccoverage [methodID];
                            
                            final MethodDescriptor methoddesc = methoddescs [methodID];                        
                            final int [] mbsizes = methoddesc.getBlockSizes ();
                            final IntObjectMap mlineMap = methoddesc.getLineMap ();
                            if ($assert.ENABLED) $assert.ASSERT (mlineMap != null);
                            
                            final int [] mlines = mlineMap.keys ();
                            for (int ml = 0, mlLimit = mlines.length; ml < mlLimit; ++ ml)
                            {
                                final int mline = mlines [ml];
                                
                                int [] data = (int []) fldata.get (mline);
                                if (data == null)
                                {
                                    data = new int [4]; // { totalcount, totalinstr, coveragecount, coverageinstr }
                                    fldata.put (mline, data);
                                }
                                
                                final int [] lblocks = (int []) mlineMap.get (mline);
                                
                                final int bCount = lblocks.length; 
                                data [0] += bCount;
                                
                                for (int bID = 0; bID < bCount; ++ bID)
                                {
                                    final int block = lblocks [bID];
                                    
                                    final boolean bcovered = mcoverage != null && mcoverage [block];
                                    final int instr = mbsizes [block];
                                    
                                    data [1] += instr;
                                    if (bcovered)
                                    {
                                        ++ data [2];
                                        data [3] += instr;
                                    }
                                }
                            }
                        }
                    }
                    
                    final int lineCount = fldata.size ();
                    
                    aggregates [TOTAL_LINE_COUNT] = lineCount;
                    
                    int coverageLineCount = 0;
                    int coverageLineInstr = 0;
                    
                    final IntObjectMap /* line_no:int -> LineCoverageData */ lineCoverage = new IntObjectMap (lineCount);
                    int firstLine = Integer.MAX_VALUE;
                    
                    final int [] clines = fldata.keys ();
                    
                    for (int cl = 0; cl < lineCount; ++ cl)
                    {
                        final int cline = clines [cl];
                        final int [] data = (int []) fldata.get (cline);
                        
                        final int ltotalCount = data [0];
                        final int ltotalInstr = data [1];
                        final int lcoverageCount = data [2];
                        final int lcoverageInstr = data [3];
                        
                        if (lcoverageInstr > 0)
                        {
                            coverageLineCount += (PRECISION * lcoverageCount) / ltotalCount;
                            coverageLineInstr += (PRECISION * lcoverageInstr) / ltotalInstr;
                        }
                        
                        // side effect: populate line coverage data map [used by getLineCoverage()]
                        
                        final int lcoverageStatus;
                        int [][] lcoverageRatio = null;
                        
                        if (lcoverageInstr == 0)
                            lcoverageStatus = LineCoverageData.LINE_COVERAGE_ZERO;
                        else if (lcoverageInstr == ltotalInstr)
                            lcoverageStatus = LineCoverageData.LINE_COVERAGE_COMPLETE;
                        else
                        {
                            lcoverageStatus = LineCoverageData.LINE_COVERAGE_PARTIAL;
                            lcoverageRatio = new int [][] {{ltotalCount, lcoverageCount}, {ltotalInstr, lcoverageInstr}}; // note: ordering depends on IItemAttribute.UNITS_xxx 
                        }
                        
                        lineCoverage.put (cline, new LineCoverageData (lcoverageStatus, lcoverageRatio));
                        
                        // side effect: compute m_firstLine
                        
                        if (cline < firstLine) firstLine = cline;
                    }
                    
                    m_lineCoverage = lineCoverage; // side effect
                    m_firstLine = firstLine; // side effect
                    
                    aggregates [COVERAGE_LINE_COUNT] = coverageLineCount;
                    aggregates [COVERAGE_LINE_INSTR] = coverageLineInstr;
                    
                    return aggregates [type];
                }
                //break;

                            
                default: return super.getAggregate (type);
            }
        }
        
        return value;
    }

    
    public void accept (final IItemVisitor visitor, final Object ctx)
    {
        visitor.visit (this, ctx);
    }
    
    public final IItemMetadata getMetadata ()
    {
        return METADATA;
    }
    
    public static IItemMetadata getTypeMetadata ()
    {
        return METADATA;
    }
    
    // protected: .............................................................

    // package: ...............................................................

    // private: ...............................................................
    
    
    private final String m_name, m_fullVMName;
    private IntObjectMap /* line_no:int -> LineCoverageData */ m_lineCoverage;
    private int m_firstLine;
    
    private static final Item.ItemMetadata METADATA; // set in <clinit>
        
    static
    {
        METADATA = new Item.ItemMetadata (IItemMetadata.TYPE_ID_SRCFILE, "srcfile",
            1 << IItemAttribute.ATTRIBUTE_NAME_ID |
            1 << IItemAttribute.ATTRIBUTE_CLASS_COVERAGE_ID |
            1 << IItemAttribute.ATTRIBUTE_METHOD_COVERAGE_ID |
            1 << IItemAttribute.ATTRIBUTE_BLOCK_COVERAGE_ID |
            1 << IItemAttribute.ATTRIBUTE_LINE_COVERAGE_ID);
    }

} // end of class
// ----------------------------------------------------------------------------