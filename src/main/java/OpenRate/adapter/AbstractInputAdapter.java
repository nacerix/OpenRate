/* ====================================================================
 * Limited Evaluation License:
 *
 * The exclusive owner of this work is the OpenRate project.
 * This work, including all associated documents and components
 * is Copyright of the OpenRate project 2006-2013.
 *
 * The following restrictions apply unless they are expressly relaxed in a
 * contractual agreement between the license holder or one of its officially
 * assigned agents and you or your organisation:
 *
 * 1) This work may not be disclosed, either in full or in part, in any form
 *    electronic or physical, to any third party. This includes both in the
 *    form of source code and compiled modules.
 * 2) This work contains trade secrets in the form of architecture, algorithms
 *    methods and technologies. These trade secrets may not be disclosed to
 *    third parties in any form, either directly or in summary or paraphrased
 *    form, nor may these trade secrets be used to construct products of a
 *    similar or competing nature either by you or third parties.
 * 3) This work may not be included in full or in part in any application.
 * 4) You may not remove or alter any proprietary legends or notices contained
 *    in or on this work.
 * 5) This software may not be reverse-engineered or otherwise decompiled, if
 *    you received this work in a compiled form.
 * 6) This work is licensed, not sold. Possession of this software does not
 *    imply or grant any right to you.
 * 7) You agree to disclose any changes to this work to the copyright holder
 *    and that the copyright holder may include any such changes at its own
 *    discretion into the work
 * 8) You agree not to derive other works from the trade secrets in this work,
 *    and that any such derivation may make you liable to pay damages to the
 *    copyright holder
 * 9) You agree to use this software exclusively for evaluation purposes, and
 *    that you shall not use this software to derive commercial profit or
 *    support your business or personal activities.
 *
 * This software is provided "as is" and any expressed or impled warranties,
 * including, but not limited to, the impled warranties of merchantability
 * and fitness for a particular purpose are disclaimed. In no event shall
 * Tiger Shore Management or its officially assigned agents be liable to any
 * direct, indirect, incidental, special, exemplary, or consequential damages
 * (including but not limited to, procurement of substitute goods or services;
 * Loss of use, data, or profits; or any business interruption) however caused
 * and on theory of liability, whether in contract, strict liability, or tort
 * (including negligence or otherwise) arising in any way out of the use of
 * this software, even if advised of the possibility of such damage.
 * This software contains portions by The Apache Software Foundation, Robert
 * Half International.
 * ====================================================================
 */

package OpenRate.adapter;

import OpenRate.CommonConfig;
import OpenRate.IPipeline;
import OpenRate.audit.AuditUtils;
import OpenRate.buffer.IConsumer;
import OpenRate.configurationmanager.ClientManager;
import OpenRate.configurationmanager.IEventInterface;
import OpenRate.exception.ExceptionHandler;
import OpenRate.exception.InitializationException;
import OpenRate.exception.ProcessingException;
import OpenRate.logging.ILogger;
import OpenRate.logging.LogUtil;
import OpenRate.record.IRecord;
import OpenRate.utils.PropertyUtils;
import java.util.ArrayList;
import java.util.Collection;


/**
 * The IInputAdapter is responsible for creating the work set
 * that the pipeline will execute on. Common implementations
 * will load records from either a file
 * or from the database.
 */
public abstract class AbstractInputAdapter
  implements IInputAdapter,
             IEventInterface
{
  /**
   * CVS version info - Automatically captured and written to the Framework
   * Version Audit log at Framework startup. For more information
   * please <a target='new' href='http://www.open-rate.com/wiki/index.php?title=Framework_Version_Map'>click here</a> to go to wiki page.
   */
  public static String CVS_MODULE_INFO = "OpenRate, $RCSfile: AbstractInputAdapter.java,v $, $Revision: 1.75 $, $Date: 2013-05-13 18:12:11 $";

  // The symbolic name is used in the management of the pipeline (control and
  // thread monitoring) and logging.
  private String SymbolicName;

  /**
   * The PipeLog is the logger which should be used for all pipeline level
   * messages. This is instantiated during pipe startup, because at this
   * point we don't know the name of the pipe and therefore the logger to use.
   */
  protected ILogger PipeLog = null;

  /**
   * The PipeLog is the logger which should be used for all statistics related
   * messages.
   */
  protected ILogger StatsLog = LogUtil.getLogUtil().getLogger("Statistics");

  /**
   * This is the local variable that we use to determine the batch size. This
   * determines the number of records which is pushed into the output FIFO.
   */
  protected int BatchSize;

 /**
  * This is the local variable that we use to determine the buffer high water
  * mark.
  */
  protected int BufferSize;

  // This is the buffer we will be writing to
  private IConsumer consumer;

 /**
  *  This is the pipeline that we are in, used for logging and property retrieval
  */
  protected String pipeName;

  // The exception handler that we use for reporting errors
  protected ExceptionHandler handler;

  // List of Services that this Client supports
  private final static String SERVICE_BATCHSIZE  = CommonConfig.BATCH_SIZE;
  private final static String SERVICE_BUFFERSIZE = CommonConfig.BUFFER_SIZE;
  private final static String DEFAULT_BATCHSIZE  = CommonConfig.DEFAULT_BATCH_SIZE;
  private final static String DEFAULT_BUFFERSIZE = CommonConfig.DEFAULT_BUFFER_SIZE;
  private final static String SERVICE_STATS      = CommonConfig.STATS;
  private final static String SERVICE_STATSRESET = CommonConfig.STATS_RESET;

  //performance counters
  private long processingTime = 0;
  private long recordsProcessed = 0;
  private long streamsProcessed = 0;
  private int  outBufferCapacity = 0;
  private int  bufferHits = 0;

  /**
   *  The pipeline we belong to - used for scheduling
   */
  protected IPipeline ourPipeline = null;

  /**
   * Default constructor
   */
  public AbstractInputAdapter()
  {
    // Add the version map
    AuditUtils.getAuditUtils().buildHierarchyVersionMap(this.getClass());
  }

  /**
   * Get the batch size for the input adapter. This determines the maximum
   * number of records that will be read from the input stream before a batch
   * is pushed into the processing stream.
   *
   * @param PipelineName The name of the pipeline that is using this adapter
   * @param ModuleName The module name of this adapter
   * @throws OpenRate.exception.InitializationException
   */
  @Override
  public void init(String PipelineName, String ModuleName)
            throws InitializationException
  {
    String ConfigHelper;
    setSymbolicName(ModuleName);

    // store the pipe we are in
    this.pipeName = PipelineName;

    // Get the pipe log
    PipeLog = LogUtil.getLogUtil().getLogger(PipelineName);

    registerClientManager();

    // Get the batch size we should be working on
    ConfigHelper = initGetBatchSize();
    processControlEvent(SERVICE_BATCHSIZE, true, ConfigHelper);
    ConfigHelper = initGetBufferSize();
    processControlEvent(SERVICE_BUFFERSIZE, true, ConfigHelper);
  }

  /**
   * No-op cleanup method. Meant to be overridden if necessary.
   */
  @Override
  public void cleanup()
  {
    // no op
  }

  /**
   * Push a set of records into the pipeline.
   *
   * @param validBuffer The buffer that will receive the good records
   * @return The number of records pushed
   * @throws OpenRate.exception.ProcessingException
   */
  @Override
  public int push(IConsumer validBuffer) throws ProcessingException
  {
    long startTime;
    long endTime;
    long BatchTime = 0;
    int  size = 0;
    Collection<IRecord> validRecords;
    Collection<IRecord> all;

    // load records
    startTime = System.currentTimeMillis();

    try
    {
      // Get the batch of records from the implementation class
      validRecords = loadBatch();
      
      // Create a new batch
      all = new ArrayList<>();
      
      // Add all the records to the new batch
      all.addAll(validRecords);

      // see how many records we got
      size = all.size();
      if (size > 0)
      {
        // push the records into the buffer if we had any
        validBuffer.push(validRecords);
      }

      endTime = System.currentTimeMillis();
      BatchTime = (endTime - startTime);
      processingTime += BatchTime;
      recordsProcessed += size;
      outBufferCapacity = validBuffer.getEventCount();

      while (outBufferCapacity > BufferSize)
      {
        bufferHits++;
        StatsLog.debug("Input  <" + getSymbolicName() + "> buffer high water mark! Buffer max = <" + BufferSize + "> current count = <" + outBufferCapacity + ">");
        try {
          Thread.sleep(100);
        } catch (InterruptedException ex) {
         //
        }
        
        // refresh
        outBufferCapacity = validBuffer.getEventCount();
      }
    }
    catch (ProcessingException pe)
    {
      PipeLog.error("Processing exception caught in Input Adapter <" +
                getSymbolicName() + ">", pe);
      getExceptionHandler().reportException(pe);
    }
    catch (NullPointerException npe)
    {
      PipeLog.error("Null Pointer exception caught in Input Adapter <" +
                getSymbolicName() + ">", npe);
      getExceptionHandler().reportException(new ProcessingException(npe));
    }
    catch (Throwable t)
    {
      // ToDo: Force only allowed exception types up
      PipeLog.fatal("Unexpected exception caught in Input Adapter <" +
                getSymbolicName() + ">", t);
      getExceptionHandler().reportException(new ProcessingException(t));
    }

    StatsLog.debug(
          "Input  <" + getSymbolicName() + "> pushed <" +
          size + "> events into the valid buffer <" +
            validBuffer.toString() + "> in <" + BatchTime + "> ms" );

    return size;
  }

  /**
   * Retrieve a batch of records from the adapter.
   *
   * @return The collection of records that was loaded
   * @throws OpenRate.exception.ProcessingException
   */
  protected abstract Collection<IRecord> loadBatch() throws ProcessingException;

 /**
  * Set the buffer that we will be writing to
  *
  * @param ch The buffer for valid records
  */
  @Override
  public void setBatchOutboundValidBuffer(IConsumer ch)
  {
    this.consumer = ch;
  }

 /**
  * Get the buffer that we will be writing to
  *
  * @return The consumer buffer
  */
  @Override
  public IConsumer getBatchOutboundValidBuffer()
  {
    return this.consumer;
  }

 /**
  * Set the exception handler mechanism.
  *
  * @param handler The parent handler to set
  */
  @Override
  public void setExceptionHandler(ExceptionHandler handler)
  {
    this.handler = handler;
  }

 /**
  * return exception handler
  *
  * @return The parent handler that is currently in use
  */
  public ExceptionHandler getExceptionHandler()
  {
    return this.handler;
  }

 /**
  * return the symbolic name
  *
  * @return The symbolic name for this class stack
  */
  @Override
  public String getSymbolicName()
  {
    return SymbolicName;
  }

  /**
  * set the symbolic name
   *
   * @param name The symbolic name for this class stack
   */
  @Override
  public void setSymbolicName(String name)
  {
    SymbolicName = name;
  }

 /**
  * Set the pipeline reference so the input adapter can control the scheduler
  *
  * @param pipeline the Pipeline to set
  */
  @Override
  public void setPipeline(IPipeline pipeline)
  {
    ourPipeline = pipeline;
  }

 /**
  * Increment the streams processed counter
  */
  public void incrementStreamCount()
  {
    streamsProcessed++;
  }
  
  // -----------------------------------------------------------------------------
  // ----------------- Start of published hookable functions ---------------------
  // -----------------------------------------------------------------------------

 /**
  * This is called when the synthetic Header record is encountered, and has the
  * meaning that the stream is starting. In this case we have to open a new
  * dump file each time a stream starts.   *
  *
  * @param r The record we are working on
   * @return The processed record
   * @throws ProcessingException  
  */
  public abstract IRecord procHeader(IRecord r) throws ProcessingException;

 /**
  * This is called when a data record is encountered. You should do any normal
  * processing here.
  *
  * @param r The record we are working on
  * @return The processed record
  * @throws ProcessingException
  */
  public abstract IRecord procValidRecord(IRecord r) throws ProcessingException;

 /**
  * This is called when a data record with errors is encountered. You should do
  * any processing here that you have to do for error records, e.g. statistics,
  * special handling, even error correction!
  *
  * @param r The record we are working on
  * @return The processed record
  * @throws ProcessingException  
  */
  public abstract IRecord procErrorRecord(IRecord r) throws ProcessingException;

 /**
  * This is called just before the trailer, and allows any pending record to
  * be pushed into the pipe before the trailer. Note that this is useful when
  * there is no trailer in a file, otherwise the file (not the synthetic trailer)
  * trailer will normally be used for this.
  *
  * @return The possible pending record in the adapter at the moment
  * @throws ProcessingException  
  */
  public abstract IRecord purgePendingRecord() throws ProcessingException;

 /**
  * This is called when the synthetic trailer record is encountered, and has the
  * meaning that the stream is now finished. In this example, all we do is
  * pass the control back to the transactional layer.
  *
  * @param r The record we are working on
  * @return The processed record
  * @throws ProcessingException  
  */
  public abstract IRecord procTrailer(IRecord r) throws ProcessingException;

  // -----------------------------------------------------------------------------
  // ------------- Start of inherited IEventInterface functions ------------------
  // -----------------------------------------------------------------------------

 /**
  * registerClientManager registers this class as a client of the ECI listener
  * and publishes the commands that the plug in understands. The listener is
  * responsible for delivering only these commands to the plug in.
  *
  */
  @Override
  public void registerClientManager() throws InitializationException
  {
    // Set the client reference and the base services first
    ClientManager.registerClient(pipeName,getSymbolicName(), this);

    //Register services for this Client
    ClientManager.registerClientService(getSymbolicName(), SERVICE_BATCHSIZE, ClientManager.PARAM_MANDATORY);
    ClientManager.registerClientService(getSymbolicName(), SERVICE_BUFFERSIZE, ClientManager.PARAM_MANDATORY);
    ClientManager.registerClientService(getSymbolicName(), SERVICE_STATS, ClientManager.PARAM_NONE);
    ClientManager.registerClientService(getSymbolicName(), SERVICE_STATSRESET, ClientManager.PARAM_DYNAMIC);
  }

  /**
  * processControlEvent is the event processing hook for the External Control
  * Interface (ECI). This allows interaction with the external world.
   *
  * @param Command The command that we are to work on
  * @param Init True if the pipeline is currently being constructed
  * @param Parameter The parameter value for the command
  * @return The result message of the operation
   */
  @Override
  public String processControlEvent(String Command, boolean Init,
                                    String Parameter)
  {
    int ResultCode = -1;
    double CDRsPerSec;

    // Reset the Statistics
    if (Command.equalsIgnoreCase(SERVICE_STATSRESET))
    {
      if (Parameter.equalsIgnoreCase("true"))
      {
        processingTime = 0;
        recordsProcessed = 0;
        streamsProcessed = 0;
        bufferHits = 0;
      }
      ResultCode = 0;
    }

    // Return the Statistics
    if (Command.equalsIgnoreCase(SERVICE_STATS))
    {
      if (processingTime == 0)
      {
        CDRsPerSec = 0;
      }
      else
      {
        CDRsPerSec = (double)((recordsProcessed*1000)/processingTime);
      }

      return Long.toString(recordsProcessed) + ":" +
             Long.toString(processingTime) + ":" +
             Long.toString(streamsProcessed) + ":" +
             Double.toString(CDRsPerSec) + ":" +
             Long.toString(outBufferCapacity) + ":" +
             Long.toString(bufferHits) + ":" +
             Long.toString(getBatchOutboundValidBuffer().getEventCount());
    }

    if (Command.equalsIgnoreCase(SERVICE_BATCHSIZE))
    {
      if (Parameter.equals(""))
      {
        return Integer.toString(BatchSize);
      }
      else
      {
        try
        {
          BatchSize = Integer.parseInt(Parameter);
        }
        catch (NumberFormatException nfe)
        {
          PipeLog.error("Invalid number for batch size. Passed value = <" +
                Parameter + ">");
        }

        ResultCode = 0;
      }
    }

    if (Command.equalsIgnoreCase(SERVICE_BUFFERSIZE))
    {
      if (Parameter.equals(""))
      {
        return Integer.toString(BufferSize);
      }
      else
      {
        try
        {
          BufferSize = Integer.parseInt(Parameter);
        }
        catch (NumberFormatException nfe)
        {
          PipeLog.error(
                "Invalid number for buffer size. Passed value = <" +
                Parameter + ">");
        }

        ResultCode = 0;
      }
    }

    if (ResultCode == 0)
    {
      PipeLog.debug(LogUtil.LogECIPipeCommand(getSymbolicName(), pipeName, Command, Parameter));

      return "OK";
    }
    else
    {
      return "Command Not Understood";
    }
  }

  // -----------------------------------------------------------------------------
  // -------------------- Start of local utility functions -----------------------
  // -----------------------------------------------------------------------------

  /**
  * Temporary function to gather the information from the properties file. Will
  * be removed with the introduction of the new configuration model.
  */
  private String initGetBatchSize()
                           throws InitializationException
  {
    String tmpValue;
    tmpValue = PropertyUtils.getPropertyUtils().getBatchInputAdapterPropertyValueDef(pipeName, getSymbolicName(),
                                                   SERVICE_BATCHSIZE, DEFAULT_BATCHSIZE);

    return tmpValue;
  }

  /**
  * Temporary function to gather the information from the properties file. Will
  * be removed with the introduction of the new configuration model.
  */
  private String initGetBufferSize()
                           throws InitializationException
  {
    String tmpValue;
    tmpValue = PropertyUtils.getPropertyUtils().getBatchInputAdapterPropertyValueDef(pipeName, getSymbolicName(), 
                                                   SERVICE_BUFFERSIZE, DEFAULT_BUFFERSIZE);

    return tmpValue;
  }
}