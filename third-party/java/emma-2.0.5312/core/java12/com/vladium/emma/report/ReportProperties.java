/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: ReportProperties.java,v 1.1.1.1 2004/05/09 16:57:38 vlad_r Exp $
 */
package com.vladium.emma.report;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.StringTokenizer;

import com.vladium.util.Files;
import com.vladium.util.IProperties;
import com.vladium.util.IntIntMap;
import com.vladium.util.IntVector;
import com.vladium.util.ObjectIntMap;
import com.vladium.util.Property;
import com.vladium.util.asserts.$assert;
import com.vladium.emma.IAppErrorCodes;
import com.vladium.emma.EMMARuntimeException;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
abstract class ReportProperties implements IAppErrorCodes
{
    // public: ................................................................
    
    
    public static final IProperties.IMapper REPORT_PROPERTY_MAPPER; // set in <clinit>
    
       
    public static final class ParsedProperties
    {
        public void setOutEncoding (final String outEncoding)
        {
            if ($assert.ENABLED) $assert.ASSERT (outEncoding != null, "null input: outEncoding");
            
            m_outEncoding = outEncoding;
        }

        public String getOutEncoding ()
        {
            return m_outEncoding;
        }

        public void setOutDir (final File outDir)
        {
            if ($assert.ENABLED) $assert.ASSERT (outDir != null, "null input: outDir");
            
            m_outDir = outDir;
        }

        public File getOutDir ()
        {
            return m_outDir;
        }

        public void setOutFile (final File outFile)
        {
            if ($assert.ENABLED) $assert.ASSERT (outFile != null, "null input: outFile");
            
            m_outFile = outFile;
        }

        public File getOutFile ()
        {
            return m_outFile;
        }

        public void setUnitsType (final int unitsType)
        {
            if ($assert.ENABLED) $assert.ASSERT (unitsType >= IItemAttribute.UNITS_COUNT && unitsType <= IItemAttribute.UNITS_INSTR, "invalid units type: " + unitsType);
            
            m_unitsType = unitsType;
        }

        public int getUnitsType ()
        {
            return m_unitsType;
        }

        public void setViewType (final int viewType)
        {
            if ($assert.ENABLED) $assert.ASSERT (viewType >= IReportDataView.HIER_CLS_VIEW && viewType <= IReportDataView.HIER_SRC_VIEW, "invalid view type: " + viewType);
            
            m_viewType = viewType;
        }

        public int getViewType ()
        {
            return m_viewType;
        }

        public void setDepth (final int depth)
        {
            if ($assert.ENABLED) $assert.ASSERT (depth >= IItemMetadata.TYPE_ID_ALL && depth <= IItemMetadata.TYPE_ID_METHOD, "invalid depth: " + depth);
            
            m_depth = depth;
        }

        public int getDepth()
        {
            return m_depth;
        }

        public void setHideClasses (final boolean hideClasses)
        {
            m_hideClasses = hideClasses;
        }

        public boolean getHideClasses ()
        {
            return m_hideClasses;
        }

        public void setColumnOrder (final int [] columnOrder)
        {
            if ($assert.ENABLED) $assert.ASSERT (columnOrder != null && columnOrder.length != 0, "null/empty input: outEncoding");
            
            m_columnOrder = columnOrder;
        }

        public int [] getColumnOrder ()
        {
            return m_columnOrder;
        }

        public void setSortOrder (final int [] sortOrder)
        {
            if ($assert.ENABLED) $assert.ASSERT (sortOrder != null, "null input: sortOrder");
            
            m_sortOrder = sortOrder;
        }

        public int [] getSortOrder ()
        {
            return m_sortOrder;
        }

        public void setMetrics (final IntIntMap metrics)
        {
            if ($assert.ENABLED) $assert.ASSERT (metrics != null, "null input: metrics");
            
            m_metrics = metrics;
        }

        public IntIntMap getMetrics ()
        {
            return m_metrics;
        }
        
        // TODO: toString/logging
        
        void validate () throws IllegalArgumentException
        {
            if ($assert.ENABLED)
            {
                $assert.ASSERT (m_outEncoding != null, "m_outEncoding not set");
                $assert.ASSERT (m_outDir != null || m_outFile != null, "either m_outDir or m_outFile must be set");
                $assert.ASSERT (m_columnOrder != null, "m_columnOrder not set");
                $assert.ASSERT (m_sortOrder != null, "m_sortOrder not set");
                $assert.ASSERT (m_metrics != null, "m_metrics not set");
            }
        }


        private String m_outEncoding;
        private File m_outDir;
        private File m_outFile;
        
        private int m_unitsType;
        private int m_viewType;
        
        private boolean m_hideClasses;
        private int m_depth;
        
        // TODO: fraction/number format strings...
        
        private int [] m_columnOrder; // attribute IDs [order indicates column order] 
        private int [] m_sortOrder; // if m_sortOrder[i+1]>0 , sort m_columnOrder[m_sortOrder[i]] in ascending order
        private IntIntMap m_metrics; // pass criteria (column attribute ID -> metric)
        
    } // end of nested class
    
    
//    /**
//     * Creates a property view specific to 'reportType' report type.
//     * 
//     * @param appProperties
//     * @param reportType
//     * @return
//     */
//    public static Properties getReportProperties (final Properties appProperties, final String reportType)
//    {
//        if ((reportType == null) || (reportType.length () == 0))
//             throw new IllegalArgumentException ("null/empty input: reportType");
//
//        if (appProperties == null) return new XProperties ();
//             
//        return new ReportPropertyLookup (appProperties, reportType);
//    }
    
    
//    /**
//     * @param type [null/empty indicates type-neutral property]
//     */
//    public static String getReportProperty (final String type, final Map properties, final String key)
//    {
//        if (properties == null) throw new IllegalArgumentException ("null input: properties");
//        if (key == null) throw new IllegalArgumentException ("null input: key");
//        
//        String fullKey;
//        
//        if ((type == null) || (type.length () == 0))
//            fullKey = IReportParameters.PREFIX.concat (key);
//        else
//        {
//            fullKey = IReportParameters.PREFIX.concat (type).concat (".").concat (key);
//            
//            if (! properties.containsKey (fullKey)) // default to type-neutral lookup
//                fullKey = IReportParameters.PREFIX.concat (key);
//        }
//            
//        return (String) properties.get (fullKey);        
//    }
//    
//    public static String getReportParameter (final String type, final Map properties, final String key, final String def)
//    {
//        final String value = getReportProperty (type, properties, key);
//        
//        return (value == null) ? def : value; 
//    }

    
    public static ParsedProperties parseProperties (final IProperties properties, final String type)
    {
        if ($assert.ENABLED) $assert.ASSERT (properties != null, "properties = null");
        
        final ParsedProperties result = new ParsedProperties ();
        {
            result.setOutEncoding (getReportProperty (properties, type, IReportProperties.OUT_ENCODING, false));
        }
        
        // TODO: outDirName is no longer supported
        
        {
            final String outDirName = getReportProperty (properties, type, IReportProperties.OUT_DIR, true);
            final String outFileName = getReportProperty (properties, type, IReportProperties.OUT_FILE, false);
    
            // renormalize the out dir and file combination:
        
            if (outFileName != null)
            {
                final File fullOutFile = Files.newFile (outDirName, outFileName);
                
                final File dir = fullOutFile.getParentFile ();
                if (dir != null) result.setOutDir (dir);
            
                result.setOutFile (new File (fullOutFile.getName ()));
            }
            else if (outDirName != null)
            {
                result.setOutDir (new File (outDirName));
            }
        }

        {
            final String unitsType = getReportProperty (properties, type, IReportProperties.UNITS_TYPE, true, IReportProperties.DEFAULT_UNITS_TYPE);
            result.setUnitsType (IReportProperties.COUNT_UNITS.equals (unitsType) ? IItemAttribute.UNITS_COUNT : IItemAttribute.UNITS_INSTR);
            
            // TODO: invalid setting not checked
        }
        {
            /* view type is no longer a user-overridable property [it is driven by SourceFile attribute presence]

            final String viewType = getReportProperty (properties, type, IReportProperties.VIEW_TYPE, IReportProperties.DEFAULT_VIEW_TYPE);
            result.setViewType (IReportProperties.SRC_VIEW.equals (viewType) ? IReportDataView.HIER_SRC_VIEW : IReportDataView.HIER_CLS_VIEW);
            */
            
            result.setViewType (IReportDataView.HIER_SRC_VIEW);
        } 
        
        {
            final String hideClasses = getReportProperty (properties, type, IReportProperties.HIDE_CLASSES, true, IReportProperties.DEFAULT_HIDE_CLASSES);
            result.setHideClasses (Property.toBoolean (hideClasses));
        
            // TODO: log this
            if (result.getViewType () == IReportDataView.HIER_CLS_VIEW)
                result.setHideClasses (false);
        }
        {
            final String depth = getReportProperty (properties, type, IReportProperties.DEPTH, false, IReportProperties.DEFAULT_DEPTH);
            
            if (IReportProperties.DEPTH_ALL.equals (depth))
                result.setDepth (AllItem.getTypeMetadata ().getTypeID ());
            else if (IReportProperties.DEPTH_PACKAGE.equals (depth))
                result.setDepth (PackageItem.getTypeMetadata ().getTypeID ());
            else if (IReportProperties.DEPTH_SRCFILE.equals (depth))
                result.setDepth (SrcFileItem.getTypeMetadata ().getTypeID ());
            else if (IReportProperties.DEPTH_CLASS.equals (depth))
                result.setDepth (ClassItem.getTypeMetadata ().getTypeID ());
            else if (IReportProperties.DEPTH_METHOD.equals (depth))
                result.setDepth (MethodItem.getTypeMetadata ().getTypeID ());
            else
                // TODO: properly prefixes prop name
                throw new EMMARuntimeException (INVALID_PARAMETER_VALUE, new Object [] {IReportProperties.DEPTH, depth});
        }
        
        if (result.getHideClasses () &&
           (result.getViewType () == IReportDataView.HIER_SRC_VIEW) &&
           (result.getDepth () == IItemMetadata.TYPE_ID_CLASS))
        {
            result.setDepth (IItemMetadata.TYPE_ID_SRCFILE);
        }
        
        final Set /* String */ columnNames = new HashSet ();
        {
            final String columnList = getReportProperty (properties, type, IReportProperties.COLUMNS, false, IReportProperties.DEFAULT_COLUMNS);
            final IntVector _columns = new IntVector ();
            
            final int [] out = new int [1];
            
            for (StringTokenizer tokenizer = new StringTokenizer (columnList, ","); tokenizer.hasMoreTokens (); )
            {
                final String columnName = tokenizer.nextToken ().trim ();
                if (! COLUMNS.get (columnName, out))
                {
                    // TODO: generate the entire enum list in the err msg
                    throw new EMMARuntimeException (INVALID_COLUMN_NAME, new Object [] {columnName});
                }
                
                if (! REMOVE_DUPLICATE_COLUMNS || ! columnNames.contains (columnName))
                {
                    columnNames.add (columnName);
                    _columns.add (out [0]);
                }
            }
            
            result.setColumnOrder (_columns.values ());
        }
        // [assertion: columnNames contains all columns for the report (some
        // may get removed later by individual report generators if some debug info
        // is missing)]
        
        {
            final String sortList = getReportProperty (properties, type, IReportProperties.SORT, false, IReportProperties.DEFAULT_SORT);
            final IntVector _sort = new IntVector ();
            
            final int [] out = new int [1];
            
            for (StringTokenizer tokenizer = new StringTokenizer (sortList, ","); tokenizer.hasMoreTokens (); )
            {
                final String sortSpec = tokenizer.nextToken ().trim ();
                final String columnName;
                final int dir;
                
                switch (sortSpec.charAt (0))
                {
                    case IReportProperties.ASC:
                    {
                        dir = +1;
                        columnName = sortSpec.substring (1);
                    }
                    break;
                    
                    case IReportProperties.DESC:
                    {
                        dir = -1;
                        columnName = sortSpec.substring (1);
                    }
                    break;
                    
                    default:
                    {
                        dir = +1;
                        columnName = sortSpec;
                    }
                    break;
                    
                } // end of switch
                
                // silently ignore columns not in the column list:
                if (columnNames.contains (columnName))
                {
                    COLUMNS.get (columnName, out);
                    
                    _sort.add (out [0]);    // sort attribute ID
                    _sort.add (dir);        // sort direction
                }
                
                result.setSortOrder (_sort.values ());
            }
        }
        {
            final String metricList = getReportProperty (properties, type, IReportProperties.METRICS, true, IReportProperties.DEFAULT_METRICS);
            final IntIntMap _metrics = new IntIntMap ();
            
            final int [] out = new int [1];
            
            // TODO: perhaps should throw on invalid input here
            for (StringTokenizer tokenizer = new StringTokenizer (metricList, ","); tokenizer.hasMoreTokens (); )
            {
                final String metricSpec = tokenizer.nextToken ().trim ();
                final String columnName;
                final double criterion;
                
                final int separator = metricSpec.indexOf (IReportProperties.MSEPARATOR);
                if (separator > 0) // silently ignore invalid entries
                {
                    // silently ignore invalid cutoff values:
                    try
                    {
                        criterion = Double.parseDouble (metricSpec.substring (separator + 1));
                        if ((criterion < 0.0) || (criterion > 101.0)) continue;
                    }
                    catch (NumberFormatException nfe)
                    {
                        nfe.printStackTrace (System.out);
                        continue;
                    }
                    
                    columnName = metricSpec.substring (0, separator);
                    
                    // silently ignore columns not in the column list:
                    if (columnNames.contains (columnName))
                    {
                        COLUMNS.get (columnName, out);
                    
                        _metrics.put (out [0], (int) Math.round (((criterion * IItem.PRECISION) / 100.0)));
                    }
                }
            }
                                        
            result.setMetrics (_metrics);
        }
        
        result.validate ();
        
        return result;
    }

    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................
    
    
    private static final class ReportPropertyMapper implements IProperties.IMapper
    {
        public String getMappedKey (final String key)
        {
            if (key != null)
            {
                if (key.startsWith (IReportProperties.PREFIX))
                {
                    final int secondDot = key.indexOf ('.', IReportProperties.PREFIX.length ());
                    if (secondDot > 0) 
                    {
                        // TODO: make this more precise (actually check the report type value string)
                        
                        return IReportProperties.PREFIX.concat (key.substring (secondDot + 1));
                    }
                }
            }
            
            return null;
        }

    } // end of nested class
    
    
//    private static final class ReportPropertyLookup extends XProperties
//    {
//        // note: due to incredibly stupid coding in java.util.Properties
//        // (getProperty() uses a non-virtual call to super.get(), while propertyNames()
//        // uses a virtual call to the same method instead of delegating to getProperty())
//        // I must override both methods below
//        
//        public String getProperty (String key)
//        {
//            return (String) get (key);
//        }
//        
//        // TODO: this kind of lookup makes the property listing confusing
//        
//        public Object get (final Object _key)
//        {
//            if (! (_key instanceof String)) return null;
//            
//            String key = (String) _key;
//            
//            if (key.startsWith (IReportProperties.PREFIX))
//                key = key.substring (IReportProperties.PREFIX.length ());
//            
//            if (key.startsWith (m_reportType))
//                key = key.substring (m_reportType.length () + 1);
//             
//            String fullKey = IReportProperties.PREFIX.concat (m_reportType).concat (".").concat (key);
//            
//            String result = defaults.getProperty (fullKey, null);
//            if (result != null) return result;
//            
//            // fall back to report type-neutral namespace:
//            fullKey = IReportProperties.PREFIX.concat (key);
//            
//            result = defaults.getProperty (fullKey, null);
//            if (result != null) return result;
//            
//            return null;
//        }
//        
//        
//        ReportPropertyLookup (final Properties appProperties, final String reportType)
//        {
//            super (appProperties);
//                 
//            m_reportType = reportType;
//        }
//
//        
//        private final String m_reportType; // never null or empty [factory-ensured]
//        
//    } // end of nested class
    
    
    private ReportProperties () {} // prevent subclassing
    
    
    private static String getReportProperty (final IProperties properties, final String type, final String key, final boolean allowBlank)
    {
        return getReportProperty (properties, type, key, allowBlank, null);
    }
    
    private static String getReportProperty (final IProperties properties, final String type, final String key, final boolean allowBlank, final String dflt)
    {
        if ($assert.ENABLED) $assert.ASSERT (properties != null, "null input: properties");
        if ($assert.ENABLED) $assert.ASSERT (key != null, "null input: key");
        
        final String result = properties.getProperty (IReportProperties.PREFIX.concat (type).concat (".").concat (key), dflt);
        
        if (! allowBlank && (result != null) && (result.trim ().length () == 0))
            return dflt;
        else
            return result;
    }

    
    private static final boolean REMOVE_DUPLICATE_COLUMNS = true;
    private static final ObjectIntMap /* col name:String -> metadata:IItemMetadata */ COLUMNS; // set in <clinit>
    
    static
    {
        REPORT_PROPERTY_MAPPER = new ReportPropertyMapper ();
        
        final ObjectIntMap columns = new ObjectIntMap ();
        
        columns.put (IReportProperties.ITEM_NAME_COLUMN, IItemAttribute.ATTRIBUTE_NAME_ID);
        columns.put (IReportProperties.CLASS_COVERAGE_COLUMN, IItemAttribute.ATTRIBUTE_CLASS_COVERAGE_ID);
        columns.put (IReportProperties.METHOD_COVERAGE_COLUMN, IItemAttribute.ATTRIBUTE_METHOD_COVERAGE_ID);
        columns.put (IReportProperties.BLOCK_COVERAGE_COLUMN, IItemAttribute.ATTRIBUTE_BLOCK_COVERAGE_ID);
        columns.put (IReportProperties.LINE_COVERAGE_COLUMN, IItemAttribute.ATTRIBUTE_LINE_COVERAGE_ID);
        
        COLUMNS = columns;
    }

} // end of class
// ----------------------------------------------------------------------------