// -*- mode:Java; tab-width:2; c-basic-offset:2; indent-tabs-mode:t -*- 

/**
 *
 * Licensed under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * 
 * Implements the Hadoop FS interfaces to allow applications to store
 * files in Ceph.
 */

package org.apache.hadoop.fs.ceph;


import java.io.IOException;
import java.io.OutputStream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.util.Progressable;

import com.ceph.fs.CephMount;

/**
 * <p>
 * An {@link OutputStream} for a CephFileSystem and corresponding
 * Ceph instance.
 */
public class CephOutputStream extends OutputStream {
  private static final Log LOG = LogFactory.getLog(CephOutputStream.class);
  private boolean closed;

  private CephFS ceph;

  private int fileHandle;

  private byte[] buffer;
  private int bufUsed = 0;

  /**
   * Construct the CephOutputStream.
   * @param conf The FileSystem configuration.
   * @param fh The Ceph filehandle to connect to.
   */
  public CephOutputStream(Configuration conf, CephFS cephfs,
      int fh, int bufferSize) {
    ceph = cephfs;
    fileHandle = fh;
    closed = false;
    buffer = new byte[bufferSize];
  }

  /** Ceph likes things to be closed before it shuts down,
   *so closing the IOStream stuff voluntarily is good
   */
  protected void finalize() throws Throwable {
    try {
      if (!closed) {
        close();
      }
    } finally {
      super.finalize();
    }
  }

  /**
   * Ensure that the stream is opened.
   */
  private synchronized void checkOpen(String ctx) throws IOException {
    if (closed)
      throw new IOException(ctx + ": operation on closed stream (fd=" + fileHandle + ")");
  }

  /**
   * Get the current position in the file.
   * @return The file offset in bytes.
   */
  public synchronized long getPos() throws IOException {
    checkOpen("getPos");
    return ceph.lseek(fileHandle, 0, CephMount.SEEK_CUR);
  }

  @Override
  public synchronized void write(int b) throws IOException {
    byte buf[] = new byte[1];
    buf[0] = (byte) b;    
    write(buf, 0, 1);
  }

  @Override
  public synchronized void write(byte buf[], int off, int len) throws IOException {
    LOG.trace(
        "CephOutputStream.write: writing " + len + " bytes to fd " + fileHandle);

    checkOpen("write");
		
    int result;
    int write;

    while (len > 0) {
      write = Math.min(len, buffer.length - bufUsed);
      try {
        System.arraycopy(buf, off, buffer, bufUsed, write);
      } catch (IndexOutOfBoundsException ie) {
        throw new IOException(
            "CephOutputStream.write: Indices out of bounds: "
                + "write length is " + len + ", buffer offset is " + off
                + ", and buffer size is " + buf.length);
      } catch (ArrayStoreException ae) {
        throw new IOException(
            "Uh-oh, CephOutputStream failed to do an array"
                + " copy due to type mismatch...");
      } catch (NullPointerException ne) {
        throw new IOException(
            "CephOutputStream.write: cannot write " + len + "bytes to fd "
            + fileHandle + ": buffer is null");
      }
      bufUsed += write;
      len -= write;
      off += write;
      if (bufUsed == buffer.length) {
        result = ceph.write(fileHandle, buffer, bufUsed, -1);
        if (result < 0) {
          throw new IOException(
              "CephOutputStream.write: Buffered write of " + bufUsed
              + " bytes failed!");
        }
        if (result != bufUsed) {
          throw new IOException(
              "CephOutputStream.write: Wrote only " + result + " bytes of "
              + bufUsed + " in buffer! Data may be lost or written"
              + " twice to Ceph!");
        }
        bufUsed = 0;
      }

    }
    return; 
  }
   
  @Override
  public synchronized void flush() throws IOException {
    checkOpen("flush");

    if (!closed) {
      if (bufUsed == 0) {
        ceph.fsync(fileHandle);
        return;
      }
      int result = ceph.write(fileHandle, buffer, bufUsed, -1);

      if (result < 0) {
        throw new IOException(
            "CephOutputStream.write: Write of " + bufUsed + "bytes to fd "
            + fileHandle + " failed");
      }
      if (result != bufUsed) {
        throw new IOException(
            "CephOutputStream.write: Write of " + bufUsed + "bytes to fd "
            + fileHandle + "was incomplete:  only " + result + " of " + bufUsed
            + " bytes were written.");
      }

      ceph.fsync(fileHandle);

      bufUsed = 0;
      return;
    }
  }
  
  @Override
  public synchronized void close() throws IOException {
    LOG.trace("CephOutputStream.close:enter");
    if (!closed) {
      flush();
      ceph.close(fileHandle);
      closed = true;
      LOG.trace("CephOutputStream.close:exit");
    }
  }
}
