/* Copyright (C) 2003 Vladimir Roubtsov. All rights reserved.
 * 
 * This program and the accompanying materials are made available under
 * the terms of the Common Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/cpl-v10.html
 * 
 * $Id: DataFactory.java,v 1.1.1.1.2.3 2004/07/16 23:32:29 vlad_r Exp $
 */
package com.vladium.emma.data;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URL;
import java.net.URLConnection;

import com.vladium.logging.Logger;
import com.vladium.util.asserts.$assert;
import com.vladium.emma.IAppConstants;

// ----------------------------------------------------------------------------
/**
 * @author Vlad Roubtsov, (C) 2003
 */
public
abstract class DataFactory
{
    // public: ................................................................
    
    // TODO: file compaction
    // TODO: file locking
    
    // TODO: what's the best place for these?
    
    public static final byte TYPE_METADATA          = 0x0; // must start with 0
    public static final byte TYPE_COVERAGEDATA      = 0x1; // must be consistent with mergeload()
    
    
    public static IMergeable [] load (final File file)
        throws IOException
    {
        if (file == null) throw new IllegalArgumentException ("null input: file");
        
        return mergeload (file);
    }
    
    public static void persist (final IMetaData data, final File file, final boolean merge)
        throws IOException
    {
        if (data == null) throw new IllegalArgumentException ("null input: data");
        if (file == null) throw new IllegalArgumentException ("null input: file");
        
        if (! merge && file.exists ())
        {
            if (! file.delete ())
                throw new IOException ("could not delete file [" + file.getAbsolutePath () + "]");
        }
        
        persist (data, TYPE_METADATA, file);
    }
    
    public static void persist (final ICoverageData data, final File file, final boolean merge)
        throws IOException
    {
        if (data == null) throw new IllegalArgumentException ("null input: data");
        if (file == null) throw new IllegalArgumentException ("null input: file");
        
        if (! merge && file.exists ())
        {
            if (! file.delete ())
                throw new IOException ("could not delete file [" + file.getAbsolutePath () + "]");
        }
        
        persist (data, TYPE_COVERAGEDATA, file);
    } 
    
    public static void persist (final ISessionData data, final File file, final boolean merge)
        throws IOException
    {
        if (data == null) throw new IllegalArgumentException ("null input: data");
        if (file == null) throw new IllegalArgumentException ("null input: file");
        
        if (! merge && file.exists ())
        {
            if (! file.delete ())
                throw new IOException ("could not delete file [" + file.getAbsolutePath () + "]");
        }
        
        persist (data.getMetaData (), TYPE_METADATA, file); 
        persist (data.getCoverageData (), TYPE_COVERAGEDATA, file);
    }

    
    public static IMetaData newMetaData (final CoverageOptions options)
    {
        return new MetaData (options);
    }
    
    public static ICoverageData newCoverageData ()
    {
        return new CoverageData (); 
    }

    public static IMetaData readMetaData (final URL url)
        throws IOException, ClassNotFoundException
    {
        ObjectInputStream oin = null;
        
        try
        {
            oin = new ObjectInputStream (new BufferedInputStream (url.openStream (), 32 * 1024));
            
            return (IMetaData) oin.readObject ();
        }
        finally
        {
            if (oin != null) try { oin.close (); } catch (Exception ignore) {} 
        }
    }
    
    public static void writeMetaData (final IMetaData data, final OutputStream out)
        throws IOException
    {
        ObjectOutputStream oout = new ObjectOutputStream (out);
        oout.writeObject (data);
    }
    
    public static void writeMetaData (final IMetaData data, final URL url)
        throws IOException
    {
        final URLConnection connection = url.openConnection ();
        connection.setDoOutput (true);
        
        OutputStream out = null;
        try
        {
            out = connection.getOutputStream ();
            
            writeMetaData (data, out);
            out.flush ();
        }
        finally
        {
            if (out != null) try { out.close (); } catch (Exception ignore) {} 
        }
    }
    
    public static ICoverageData readCoverageData (final URL url)
        throws IOException, ClassNotFoundException
    {
        ObjectInputStream oin = null;
        
        try
        {
            oin = new ObjectInputStream (new BufferedInputStream (url.openStream (), 32 * 1024));
            
            return (ICoverageData) oin.readObject ();
        }
        finally
        {
            if (oin != null) try { oin.close (); } catch (Exception ignore) {} 
        }
    }
    
    public static void writeCoverageData (final ICoverageData data, final OutputStream out)
        throws IOException
    {
        // TODO: prevent concurrent modification problems here
        
        ObjectOutputStream oout = new ObjectOutputStream (out);
        oout.writeObject (data);
    }
    
    public static int [] readIntArray (final DataInput in)
        throws IOException
    {
        final int length = in.readInt ();
        if (length == NULL_ARRAY_LENGTH)
            return null;
        else
        {
            final int [] result = new int [length];
            
            // read array in reverse order:
            for (int i = length; -- i >= 0; )
            {
                result [i] = in.readInt ();
            }
            
            return result;
        }
    }
    
    public static boolean [] readBooleanArray (final DataInput in)
        throws IOException
    {
        final int length = in.readInt ();
        if (length == NULL_ARRAY_LENGTH)
            return null;
        else
        {
            final boolean [] result = new boolean [length];
            
            // read array in reverse order:
            for (int i = length; -- i >= 0; )
            {
                result [i] = in.readBoolean ();
            }
            
            return result;
        }
    }
    
    public static void writeIntArray (final int [] array, final DataOutput out)
        throws IOException
    {
        if (array == null)
            out.writeInt (NULL_ARRAY_LENGTH);
        else
        {
            final int length = array.length;
            out.writeInt (length);
            
            // write array in reverse order:
            for (int i = length; -- i >= 0; )
            {
                out.writeInt (array [i]);
            }
        }
    }
    
    public static void writeBooleanArray (final boolean [] array, final DataOutput out)
        throws IOException
    {
        if (array == null)
            out.writeInt (NULL_ARRAY_LENGTH);
        else
        {
            final int length = array.length;
            out.writeInt (length);
            
            // write array in reverse order:
            for (int i = length; -- i >= 0; )
            {
                out.writeBoolean (array [i]);
            }
        }
    }
    
    // protected: .............................................................

    // package: ...............................................................
    
    // private: ...............................................................


    private static final class UCFileInputStream extends FileInputStream
    {
        public void close ()
        {
        }
        
        UCFileInputStream (final FileDescriptor fd)
        {
            super (fd);
            
            if ($assert.ENABLED) $assert.ASSERT (fd.valid (), "UCFileInputStream.<init>: FD invalid");
        }
        
    } // end of nested class

    private static final class UCFileOutputStream extends FileOutputStream
    {
        public void close ()
        {
        }
        
        UCFileOutputStream (final FileDescriptor fd)
        {
            super (fd);
            
            if ($assert.ENABLED) $assert.ASSERT (fd.valid (), "UCFileOutputStream.<init>: FD invalid");
        }
        
    } // end of nested class

    
    private static final class RandomAccessFileInputStream extends BufferedInputStream
    {
        public final int read () throws IOException
        {
            final int rc = super.read ();
            if (rc >= 0) ++ m_count;
            
            return rc;
        }

        public final int read (final byte [] b, final int off, final int len)
            throws IOException
        {
            final int rc = super.read (b, off, len);
            if (rc >= 0) m_count += rc;
            
            return rc;
        }

        public final int read (final byte [] b) throws IOException
        {
            final int rc = super.read (b);
            if (rc >= 0) m_count += rc;
            
            return rc;
        }
        
        public void close ()
        {
        }        


        RandomAccessFileInputStream (final RandomAccessFile raf, final int bufSize)
            throws IOException
        {
            super (new UCFileInputStream (raf.getFD ()), bufSize);
        }
        
        final long getCount ()
        {
            return m_count;
        }
        
        private long m_count;

    } // end of nested class
    
    private static final class RandomAccessFileOutputStream extends BufferedOutputStream
    {
        public final void write (final byte [] b, final int off, final int len) throws IOException
        {
            super.write (b, off, len);
            m_count += len;
        }

        public final void write (final byte [] b) throws IOException
        {
            super.write (b);
            m_count += b.length;
        }

        public final void write (final int b) throws IOException
        {
            super.write (b);
            ++ m_count;
        }
        
        public void close ()
        {
        }
        
        
        RandomAccessFileOutputStream (final RandomAccessFile raf, final int bufSize)
            throws IOException
        {
            super (new UCFileOutputStream (raf.getFD ()), bufSize);
        }
        
        final long getCount ()
        {
            return m_count;
        }
        
        private long m_count;

    } // end of nested class
    
    
    private DataFactory () {} // prevent subclassing   

    /*
     * input checked by the caller
     */
    private static IMergeable [] mergeload (final File file)
        throws IOException
    {
        final Logger log = Logger.getLogger ();
        final boolean trace1 = log.atTRACE1 ();
        final boolean trace2 = log.atTRACE2 ();
        final String method = "mergeload";
        
        long start = 0, end;
        
        if (trace1) start = System.currentTimeMillis ();
        
        final IMergeable [] result = new IMergeable [2];
        
        if (! file.exists ())
        {
            throw new IOException ("input file does not exist: [" + file.getAbsolutePath () +  "]");
        }
        else
        {
            RandomAccessFile raf = null;
            try
            {
                raf = new RandomAccessFile (file, "r");
                
                // 'file' is a valid existing file, but it could still be of 0 length or otherwise corrupt:
                final long length = raf.length ();
                if (trace1) log.trace1 (method, "[" + file + "]: file length = " + length);
                
                if (length < FILE_HEADER_LENGTH)
                {
                    throw new IOException ("file [" + file.getAbsolutePath () + "] is corrupt or was not created by " + IAppConstants.APP_NAME);
                }
                else
                {
                    // TODO: data version checks parallel to persist()
                    
                    if (length > FILE_HEADER_LENGTH) // return {null, null} in case of equality
                    {
                        raf.seek (FILE_HEADER_LENGTH);
                                
                        // [assertion: file length > FILE_HEADER_LENGTH]
                        
                        // read entries until the first corrupt entry or the end of the file:
                        
                        long position = FILE_HEADER_LENGTH;
                        long entryLength;
                        
                        long entrystart = 0;
                        
                        while (true)
                        {
                            if (trace2) log.trace2 (method, "[" + file + "]: position " + raf.getFilePointer ());
                            if (position >= length) break;
                            
                            entryLength = raf.readLong ();
                            
                            if ((entryLength <= 0) || (position + entryLength + ENTRY_HEADER_LENGTH > length))
                                break;
                            else
                            {
                                final byte type = raf.readByte ();
                                if ((type < 0) || (type >= result.length))
                                    break;
                                    
                                if (trace2) log.trace2 (method, "[" + file + "]: found valid entry of size " + entryLength + " and type " + type);
                                {
                                    if (trace2) entrystart = System.currentTimeMillis ();
                                    final IMergeable data = readEntry (raf, type, entryLength);
                                    if (trace2) log.trace2 (method, "entry read in " + (System.currentTimeMillis () - entrystart) + " ms");                                    
                                    
                                    final IMergeable current = result [type];
                                    
                                    if (current == null)
                                        result [type] = data;
                                    else
                                        result [type] = current.merge (data); // note: later entries overrides earlier entries
                                }
                                
                                position += entryLength + ENTRY_HEADER_LENGTH;
                                
                                if ($assert.ENABLED) $assert.ASSERT (raf.getFD ().valid (), "FD invalid"); 
                                raf.seek (position);
                            }
                        }                        
                    }
                }
            }
            finally
            {
                if (raf != null) try { raf.close (); } catch (Throwable ignore) {}
                raf = null;
            }
        }
        
        if (trace1)
        {
            end = System.currentTimeMillis ();
            
            log.trace1 (method, "[" + file + "]: file processed in " + (end - start) + " ms"); 
        }
        
        return result;
    }


    /*
     * input checked by the caller
     */
    private static void persist (final IMergeable data, final byte type, final File file)
        throws IOException
    {
        final Logger log = Logger.getLogger ();
        final boolean trace1 = log.atTRACE1 ();
        final boolean trace2 = log.atTRACE2 ();
        final String method = "persist";
        
        long start = 0, end;
        
        if (trace1) start = System.currentTimeMillis ();
        
        // TODO: 1.4 adds some interesting RAF open mode options as well
        // TODO: will this benefit from extra buffering?
        
        // TODO: data version checks

        RandomAccessFile raf = null;
        try
        {
            boolean overwrite = false;
            boolean truncate = false;
                        
            if (file.exists ())
            {
                // 'file' exists:
                
                if (! file.isFile ()) throw new IOException ("can persist in normal files only: " + file.getAbsolutePath ());
                
                raf = new RandomAccessFile (file, "rw");
                
                // 'file' is a valid existing file, but it could still be of 0 length or otherwise corrupt:
                final long length = raf.length ();
                if (trace1) log.trace1 (method, "[" + file + "]: existing file length = " + length);
                
                
                if (length < 4)
                {
                    overwrite = true;
                    truncate = (length > 0);
                }
                else
                {
                    // [assertion: file length >= 4]
                    
                    // check header info before reading further:
                    final int magic = raf.readInt ();
                    if (magic != MAGIC)
                        throw new IOException ("cannot overwrite [" + file.getAbsolutePath () + "]: not created by " + IAppConstants.APP_NAME);
                    
                    if (length < FILE_HEADER_LENGTH)
                    {
                        // it's our file, but the header is corrupt: overwrite
                        overwrite = true;
                        truncate = true;
                    }  
                    else
                    {
                        // [assertion: file length >= FILE_HEADER_LENGTH]
                        
//                        if (! append)
//                        {
//                            // overwrite any existing data:
//                            
//                            raf.seek (FILE_HEADER_LENGTH);
//                            writeEntry (raf, FILE_HEADER_LENGTH, data, type);
//                        }
//                        else
                        {
                            // check data format version info:
                            final long dataVersion = raf.readLong ();
                            
                            if (dataVersion != IAppConstants.DATA_FORMAT_VERSION)
                            {
                                // read app version info for the error message:
                                
                                int major = 0, minor = 0, build = 0;
                                boolean gotAppVersion = false;
                                try
                                {
                                    major = raf.readInt ();
                                    minor = raf.readInt ();
                                    build = raf.readInt ();
                                    
                                    gotAppVersion = true;
                                }
                                catch (Throwable ignore) {}
                                
                                // TODO: error code here?
                                if (gotAppVersion)
                                {
                                    throw new IOException ("cannot merge new data into [" + file.getAbsolutePath () + "]: created by another " + IAppConstants.APP_NAME + " version [" + makeAppVersion (major, minor, build) + "]");
                                }
                                else
                                {
                                    throw new IOException ("cannot merge new data into [" + file.getAbsolutePath () + "]: created by another " + IAppConstants.APP_NAME + " version"); 
                                }
                            }
                            else
                            {
                                // [assertion: file header is valid and data format version is consistent]
                                
                                raf.seek (FILE_HEADER_LENGTH);
                                
                                if (length == FILE_HEADER_LENGTH)
                                {
                                    // no previous data entries: append 'data'
                                    
                                    writeEntry (log, raf, FILE_HEADER_LENGTH, data, type);
                                }
                                else
                                {
                                    // [assertion: file length > FILE_HEADER_LENGTH]
                                    
                                    // write 'data' starting with the first corrupt entry or the end of the file:
                                    
                                    long position = FILE_HEADER_LENGTH;
                                    long entryLength;
                                    
                                    while (true)
                                    {
                                        if (trace2) log.trace2 (method, "[" + file + "]: position " + raf.getFilePointer ());
                                        if (position >= length) break;
                                        
                                        entryLength = raf.readLong ();
                                        
                                        if ((entryLength <= 0) || (position + entryLength + ENTRY_HEADER_LENGTH > length))
                                            break;
                                        else
                                        {
                                            if (trace2) log.trace2 (method, "[" + file + "]: found valid entry of size " + entryLength);
                                            
                                            position += entryLength + ENTRY_HEADER_LENGTH; 
                                            raf.seek (position);
                                        }
                                    }
                                    
                                    if (trace2) log.trace2 (method, "[" + file + "]: adding entry at position " + position);
                                    writeEntry (log, raf, position, data, type);
                                }
                            }
                        }
                    }
                }
            }
            else
            {
                // 'file' does not exist:
                
                if (trace1) log.trace1 (method, "[" + file + "]: creating a new file");
                
                final File parent = file.getParentFile ();
                if (parent != null) parent.mkdirs ();
                
                raf = new RandomAccessFile (file, "rw");
                
                overwrite = true;
            }
            
            
            if (overwrite)
            {
                // persist starting from 0 offset:
                
                if ($assert.ENABLED) $assert.ASSERT (raf != null, "raf = null");
                
                if (truncate) raf.seek (0);
                writeFileHeader (raf);
                if ($assert.ENABLED) $assert.ASSERT (raf.getFilePointer () == FILE_HEADER_LENGTH, "invalid header length: " + raf.getFilePointer ());
                
                writeEntry (log, raf, FILE_HEADER_LENGTH, data, type);
            }
        }
        finally
        {
            if (raf != null) try { raf.close (); } catch (Throwable ignore) {}
            raf = null;
        }
        
        if (trace1)
        {
            end = System.currentTimeMillis ();
            
            log.trace1 (method, "[" + file + "]: file processed in " + (end - start) + " ms"); 
        }
    }
    
    private static void writeFileHeader (final DataOutput out)
        throws IOException
    {
        out.writeInt (MAGIC);
        
        out.writeLong (IAppConstants.DATA_FORMAT_VERSION);
        
        out.writeInt (IAppConstants.APP_MAJOR_VERSION);
        out.writeInt (IAppConstants.APP_MINOR_VERSION);
        out.writeInt (IAppConstants.APP_BUILD_ID);
    }
    
    private static void writeEntryHeader (final DataOutput out, final byte type)
        throws IOException
    {
        out.writeLong (UNKNOWN); // length placeholder
        out.writeByte (type);
    }
    
    private static void writeEntry (final Logger log, final RandomAccessFile raf, final long marker, final IMergeable data, final byte type)
        throws IOException
    {
        // [unfinished] entry header:
        writeEntryHeader (raf, type);
        
        // serialize 'data' starting with the current raf position:
        RandomAccessFileOutputStream rafout = new RandomAccessFileOutputStream (raf, IO_BUF_SIZE); // note: no new file descriptors created here
        {
//            ObjectOutputStream oout = new ObjectOutputStream (rafout);
//            
//            oout.writeObject (data);
//            oout.flush ();
//            oout = null;
            
            DataOutputStream dout = new DataOutputStream (rafout);
            switch (type)
            {
                case TYPE_METADATA: MetaData.writeExternal ((MetaData) data, dout);
                    break;
                    
                default /* TYPE_COVERAGEDATA */: CoverageData.writeExternal ((CoverageData) data, dout);
                    break;
                    
            } // end of switch
            dout.flush ();
            dout = null;
            
            // truncate:
            raf.setLength (raf.getFilePointer ());
        }

        // transact this entry [finish the header]:
        raf.seek (marker);
        raf.writeLong (rafout.getCount ());
        if (DO_FSYNC) raf.getFD ().sync ();
        
        if (log.atTRACE2 ()) log.trace2 ("writeEntry", "entry [" + data.getClass ().getName () + "] length: " + rafout.getCount ());
    }
    
    private static IMergeable readEntry (final RandomAccessFile raf, final byte type, final long entryLength)
        throws IOException
    {
        final Object data;
        
        RandomAccessFileInputStream rafin = new RandomAccessFileInputStream (raf, IO_BUF_SIZE); // note: no new file descriptors created here
        {
//           ObjectInputStream oin = new ObjectInputStream (rafin);
//            
//            try
//            {
//                data = oin.readObject ();
//            }
//            catch (ClassNotFoundException cnfe)
//            {
//                // TODO: EMMA exception here
//                throw new IOException ("could not read data entry: " + cnfe.toString ());
//            }

            DataInputStream din = new DataInputStream (rafin);
            switch (type)
            {
                case TYPE_METADATA: data = MetaData.readExternal (din);
                    break;
                    
                default /* TYPE_COVERAGEDATA */: data = CoverageData.readExternal (din);
                    break;
                    
            } // end of switch
        }
        
        if ($assert.ENABLED) $assert.ASSERT (rafin.getCount () == entryLength, "entry length mismatch: " + rafin.getCount () + " != " + entryLength);
        
        return (IMergeable) data;
    }
    
    
    /*
     * This is cloned from EMMAProperties by design, to eliminate a CONSTANT_Class_info
     * dependency between this and EMMAProperties classes.
     */
    private static String makeAppVersion (final int major, final int minor, final int build)
    {
        final StringBuffer buf = new StringBuffer ();
        
        buf.append (major);
        buf.append ('.');
        buf.append (minor);
        buf.append ('.');
        buf.append (build);
        
        return buf.toString ();
    }
    

    private static final int NULL_ARRAY_LENGTH = -1;
    
    private static final int MAGIC = 0x454D4D41; // "EMMA"
    private static final long UNKNOWN = 0L;
    private static final int FILE_HEADER_LENGTH = 4 + 8 + 3 * 4; // IMPORTANT: update on writeFileHeader() changes
    private static final int ENTRY_HEADER_LENGTH = 8 + 1; // IMPORTANT: update on writeEntryHeader() changes
    private static final boolean DO_FSYNC = true;
    private static final int IO_BUF_SIZE = 32 * 1024;
    
} // end of class
// ----------------------------------------------------------------------------
