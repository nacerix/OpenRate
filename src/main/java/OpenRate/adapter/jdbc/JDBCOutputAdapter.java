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

package OpenRate.adapter.jdbc;

import OpenRate.CommonConfig;
import OpenRate.adapter.AbstractTransactionalSTOutputAdapter;
import OpenRate.configurationmanager.ClientManager;
import OpenRate.db.DBUtil;
import OpenRate.exception.InitializationException;
import OpenRate.exception.ProcessingException;
import OpenRate.logging.LogUtil;
import OpenRate.record.DBRecord;
import OpenRate.record.IRecord;
import OpenRate.utils.PropertyUtils;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Iterator;

/**
 * Please <a target='new' href='http://www.open-rate.com/wiki/index.php?title=JDBC_Output_Adapter'>click here</a> to go to wiki page.
 * 
 * <p>JDBC Output Adapter.<br>
 * 
 * This module writes records into a table using JDBC.
 * The processing needs to be a little more complicated than is first obvious
 * to allow for the case that a transaction is rolled back. We therefore have
 * to mark the records we are inserting in some way to segregate them from
 * the records from previous transactions. Only when we reach the commit point
 * do we make the new records like all the others, and in the case that we
 * encounter a rollback, we have to remove the inserted records.<br>
 * 
 * The management of the connection and statements is dynamic: When a connection
 * is needed, it is created out of the framework managed pool. When the
 * processing is finished, the connection is released.<br>
 *
 * This module performs as insert on every record received for processing, and
 * therefore does not offer very high performance. This can often lead to the
 * pipeline "backing up" during processing (because the performance bottleneck
 * is at the end of the processing chain. Faster alternatives are the
 * JDBCBatchOutputAdapter and DirectLoad adapters. However the faster types are
 * progressively trickier to use (because the detection of an error gets
 * progressively "further away" from the cause), therefore usually initial
 * development should happen on the most primitive adapter, and during the
 * hardening of the project for productive purposes, the right level of
 * performance tuning should be found.
 */
public abstract class JDBCOutputAdapter
  extends AbstractTransactionalSTOutputAdapter
{
  /**
   * CVS version info - Automatically captured and written to the Framework
   * Version Audit log at Framework startup. For more information
   * please <a target='new' href='http://www.open-rate.com/wiki/index.php?title=Framework_Version_Map'>click here</a> to go to wiki page.
   */
  public static String CVS_MODULE_INFO = "OpenRate, $RCSfile: JDBCOutputAdapter.java,v $, $Revision: 1.89 $, $Date: 2013-05-13 18:12:12 $";

  /**
   * The query that is used to prepare the database for record insert
   */
  protected String initQuery;
  
  /**
   * The insert query
   */
  protected String insertQuery;
  
  /**
   * The commit query if the transaction ended correctly
   */
  protected String commitQuery;
  
  /**
   * The rollback query if the transaction did not end correctly
   */
  protected String rollbackQuery;

  /**
   * This is the name of the data source
   */
  protected String dataSourceName;

  /**
   * This is the query that is used to make any preparation for the insert
   */
  protected PreparedStatement stmtInitQuery;

  /**
   * This is the query that is used to make the record insert into the table
   */
  protected PreparedStatement stmtInsertQuery;

  /**
   * This is the query that is used to commit the records in the table.
   */
  protected PreparedStatement stmtCommitQuery;

  /**
   * This is the statement that is used to roll back records from a table.
   */
  protected PreparedStatement stmtRollbackQuery;

  // this is the connection from the connection pool that we are using
  private static final String DATASOURCE_KEY = "DataSource";

  // The SQL statements from the properties that are used to get the records
  private static final String INIT_QUERY_KEY = "InitStatement";
  private static final String INSERT_QUERY_KEY = "RecordInsertStatement";
  private static final String COMMIT_QUERY_KEY = "CommitStatement";
  private static final String ROLLBACK_QUERY_KEY = "RollbackStatement";

  // List of Services that this Client supports
  private final static String SERVICE_DATASOURCE_KEY = "DataSource";
  private final static String SERVICE_INIT_QUERY_KEY = "InitStatement";
  private final static String SERVICE_INSERT_QUERY_KEY = "RecordInsertStatement";
  private final static String SERVICE_COMMIT_QUERY_KEY = "CommitStatement";
  private final static String SERVICE_ROLLBACK_QUERY_KEY = "RollbackStatement";
  private final static String SERVICE_STATUS_KEY = "PrintStatus";

  // This tells us if we should look for new work or continue with something
  // that is going on at the moment
  private boolean OutputStreamOpen = false;
  
  // used for controlling if statements are used
  private boolean useInit;
  private boolean useCommit;
  private boolean useRollback;

  // Extended validation of the columns we are going to insert - number
  private Integer insertQueryParamCount = null;

/**
 * This is our connection object
 */
  protected Connection JDBCcon;

  /**
   * Default constructor
   */
  public JDBCOutputAdapter()
  {
    super();
  }

 /**
  * Initialise the module. Called during pipeline creation.
  * Initialise the Logger, and load the SQL statements.
  *
  * @param PipelineName The name of the pipeline this module is in
  * @param ModuleName The module symbolic name of this module
  * @throws OpenRate.exception.InitializationException
  */
  @Override
  public void init(String PipelineName, String ModuleName)
            throws InitializationException
  {
    String ConfigHelper;
    super.init(PipelineName, ModuleName);
    registerClientManager();

    // Register ourself with the client manager
    setSymbolicName(ModuleName);

    // get any initialisation SQL that we need to perform, such as marking
    // records for select and removal
    ConfigHelper = initInitQuery();
    processControlEvent(SERVICE_INIT_QUERY_KEY, true, ConfigHelper);

    // this is the SQL that will tidy up after the select, and ensure that
    // they are not selected next time, in the case that the processing
    // was completed correctly
    ConfigHelper = initInsertQuery();
    processControlEvent(SERVICE_INSERT_QUERY_KEY, true, ConfigHelper);

    // this is the SQL that will tidy up after the select, and ensure that
    // they are not selected next time, in the case that the processing
    // was completed correctly
    ConfigHelper = initCommitQuery();
    processControlEvent(SERVICE_COMMIT_QUERY_KEY, true, ConfigHelper);

    // this is the SQL that will tidy up after the select, and ensure that
    // they are not selected next time, in the case that the processing
    // was completed correctly
    ConfigHelper = initRollbackQuery();
    processControlEvent(SERVICE_ROLLBACK_QUERY_KEY, true, ConfigHelper);

    // The datasource property was added to allow database to database
    // JDBC adapters to work properly using 1 configuration file.
    ConfigHelper = initDataSourceName();
    processControlEvent(SERVICE_DATASOURCE_KEY, true, ConfigHelper);

    // prepare the data source - this does not open a connection
    if(DBUtil.initDataSource(dataSourceName) == null)
    {
      String Message = "Could not initialise DB connection <" + dataSourceName + "> to in module <" + getSymbolicName() + ">.";
      pipeLog.error(Message);
      throw new InitializationException(Message);
    }
    
    // Set up the optional statements
    useInit     = ((initQuery == null || initQuery.isEmpty()) == false);
    useCommit   = ((commitQuery == null || commitQuery.isEmpty()) == false);
    useRollback = ((rollbackQuery == null || rollbackQuery.isEmpty()) == false);
  }

 /**
  * Process the stream header. Get the file base name and open the transaction.
  *
  * @param r The record we are working on
  * @return The processed record
  * @throws ProcessingException  
  */
  @Override
  public IRecord procHeader(IRecord r) throws ProcessingException
  {
    // perform any parent processing first
    super.procHeader(r);

    try
    {
      // get the connection we will be using for writing
      JDBCcon = DBUtil.getConnection(dataSourceName);
    }
    catch (InitializationException ex)
    {
      getExceptionHandler().reportException(new ProcessingException(ex));
    }

    // prepare the statements used for writing
    try
    {
      prepareStatements();
    }
    catch (SQLException Sex)
    {
      // Not good. Abort the transaction
      String Message = "Error preparing statements. Message <" + Sex.getMessage() + ">. Aborting transaction.";
      pipeLog.fatal(Message);
      getExceptionHandler().reportException(new ProcessingException(Message, Sex));
      setTransactionAbort(getTransactionNumber());
    }

    // Get the count of the insert params we are going to use, but only once
    if (insertQueryParamCount == null)
    {
      try
      {
        insertQueryParamCount = stmtInsertQuery.getParameterMetaData().getParameterCount();
      }
      catch (SQLException Sex)
      {
        // Not good. Abort the transaction
        String Message = "Could not count the parameters for insert statement. Message <" + Sex.getMessage() + ">. Aborting transaction.";
        pipeLog.fatal(Message);
        getExceptionHandler().reportException(new ProcessingException(Message, Sex));
        setTransactionAbort(getTransactionNumber());
      }

      String Message = "Parameter count for insert statement in module <" + getSymbolicName() + "> is <" + insertQueryParamCount + ">";
      pipeLog.info(Message);
    }

    // perform the Init (if defined)
    if (useInit)
    {
      try
      {
        stmtInitQuery.execute();
      }
      catch (SQLException Sex)
      {
        // Not good. Abort the transaction
        String Message = "Error executing init statement. Message <" + Sex.getMessage() + ">. Aborting transaction.";
        pipeLog.fatal(Message);
        getExceptionHandler().reportException(new ProcessingException(Message, Sex));
        setTransactionAbort(getTransactionNumber());
      }
    }
    
    return r;
  }

 /**
  * Prepare good records for writing to the defined output stream.
  * 
  * @param r The current record we are working on
  * @return The prepared record
  * @throws ProcessingException  
  */
  @Override
  public IRecord prepValidRecord(IRecord r) throws ProcessingException
  {
    int i;
    Collection<IRecord> outRecCol = null;
    DBRecord            outRec;
    Iterator<IRecord>   outRecIter;

    try
    {
      outRecCol = procValidRecord(r);
    }
    catch (ProcessingException pe)
    {
      // Pass the exception up
      String Message = "Processing exception preparing valid record in module <" +
                       getSymbolicName() + ">. Message <" + pe.getMessage() +
                       ">. Aborting transaction.";
      pipeLog.fatal(Message);
      getExceptionHandler().reportException(new ProcessingException(pe));
      setTransactionAbort(getTransactionNumber());
    }
    catch (ArrayIndexOutOfBoundsException aiex)
    {
      // Not good. Abort the transaction
      String Message = "Column Index preparing valid record in module <" +
                       getSymbolicName() + ">. Message <" + aiex.getMessage() + 
                       ">. Aborting transaction.";
      pipeLog.fatal(Message);
      getExceptionHandler().reportException(new ProcessingException(Message, aiex));
      setTransactionAbort(getTransactionNumber());
    }
    catch (Exception ex)
    {
      // Not good. Abort the transaction
      String Message = "Unexpected Exception preparing valid record in module <" +
                        getSymbolicName() + ">. Message <" + ex.getMessage() + 
                        ">. Aborting transaction.";
      pipeLog.fatal(Message);
      getExceptionHandler().reportException(new ProcessingException(Message, ex));
      setTransactionAbort(getTransactionNumber());
    }

    // Null return means "do not bother to process"
    if (outRecCol != null && stmtInsertQuery != null)
    {
      outRecIter = outRecCol.iterator();

      while (outRecIter.hasNext())
      {
        outRec = (DBRecord)outRecIter.next();

        if (outRec.getOutputColumnCount() != insertQueryParamCount)
        {
          // columns we go does not match the expected columns
          String Message = "Column count in module <" +
                          getSymbolicName() + "> does not match definition. Expected <" +
                          insertQueryParamCount + ">, got <" + outRec.getOutputColumnCount() + ">";
          pipeLog.error(Message);
        }
        else
        {
          try
          {
            // Prepare the parameter values
            stmtInsertQuery.clearParameters();

            for (i = 0; i < outRec.getOutputColumnCount(); i++)
            {
              if (outRec.getOutputColumnType(i) == DBRecord.COL_TYPE_STRING)
              {
                // String value
                stmtInsertQuery.setString(i + 1, outRec.getOutputColumnValueString(i));
              }
              else if (outRec.getOutputColumnType(i) == DBRecord.COL_TYPE_INTEGER)
              {
                // Integer value
                stmtInsertQuery.setInt(i + 1, outRec.getOutputColumnValueInt(i));
              }
              else if (outRec.getOutputColumnType(i) == DBRecord.COL_TYPE_DOUBLE)
              {
                // Double value
                stmtInsertQuery.setDouble(i + 1, outRec.getOutputColumnValueDouble(i));
              }
              else if (outRec.getOutputColumnType(i) == DBRecord.COL_TYPE_LONG)
              {
                // Long value
                stmtInsertQuery.setLong(i + 1, outRec.getOutputColumnValueLong(i));
              }
              else if (outRec.getOutputColumnType(i) == DBRecord.COL_TYPE_DATE)
              {
                // Date value
                long DateValue = outRec.getOutputColumnValueLong(i);
                java.sql.Date DateToSet;
                DateToSet = new java.sql.Date(DateValue);
                stmtInsertQuery.setDate(i + 1,DateToSet);
              }
              else if (outRec.getOutputColumnType(i) == DBRecord.COL_TYPE_BOOL)
              {
                // Boolean value
                boolean value;
                value = outRec.getOutputColumnValueString(i).equals("1");
                stmtInsertQuery.setBoolean(i + 1, value);
              }
              else if (outRec.getOutputColumnType(i) == DBRecord.COL_TYPE_TIME)
              {
                // Time value
                long DateValue = outRec.getOutputColumnValueLong(i);
                java.sql.Time TimeToSet;
                TimeToSet = new java.sql.Time(DateValue);
                stmtInsertQuery.setTime(i + 1,TimeToSet);
              }
              else if(outRec.getOutputColumnType(i) == DBRecord.COL_TYPE_BINARY)
              {
                // Binary value
                byte[] byteArray = outRec.getOutputColumnValueBytes(i);
                stmtInsertQuery.setBytes(i+1, byteArray );
              }
            }

            stmtInsertQuery.execute();
          }
          catch (SQLException Sex)
          {
            // Not good. Abort the transaction
            String Message = "SQL Exception inserting valid record in module <" +
                            getSymbolicName() + ">. Message <" + Sex.getMessage() +
                            ">. Aborting transaction.";
            pipeLog.fatal(Message);
            getExceptionHandler().reportException(new ProcessingException(Message, Sex));
            setTransactionAbort(getTransactionNumber());
          }
          catch (ArrayIndexOutOfBoundsException aiex)
          {
            // Not good. Abort the transaction
            String Message = "Column Index inserting valid record in module <" +
                            getSymbolicName() + ">. Message <" + aiex.getMessage() +
                            ">. Aborting transaction.";
            pipeLog.fatal(Message);
            getExceptionHandler().reportException(new ProcessingException(Message, aiex));
            setTransactionAbort(getTransactionNumber());
          }
          catch (NumberFormatException nfe)
          {
            // Not good. Abort the transaction
            String Message = "Number format inserting valid record in module <" +
                            getSymbolicName() + ">. Message <" + nfe.getMessage() +
                            ">. Aborting transaction.";
            pipeLog.fatal(Message);
            getExceptionHandler().reportException(new ProcessingException(Message, nfe));
            setTransactionAbort(getTransactionNumber());
          }
          catch (Exception ex)
          {
            // Not good. Abort the transaction
            String Message = "Unknown Exception inserting valid record in module <" +
                            getSymbolicName() + ">. Message <" + ex.getMessage() +
                            ">. Aborting transaction.";
            pipeLog.fatal(Message);

            getExceptionHandler().reportException(new ProcessingException(Message, ex));
            setTransactionAbort(getTransactionNumber());
          }
        }
      }
    }

    return r;
  }

 /**
  * Prepare bad records for writing to the defined output stream.
  * 
  * @param r The current record we are working on
  * @return The prepared record
  * @throws ProcessingException  
  */
  @Override
  public IRecord prepErrorRecord(IRecord r) throws ProcessingException
  {
    int i;
    Collection<IRecord> outRecCol = null;
    DBRecord            outRec;
    Iterator<IRecord>   outRecIter;

    try
    {
      outRecCol = procErrorRecord(r);
    }
    catch (ProcessingException pe)
    {
      // Pass the exception up
      String Message = "Processing exception preparing error record in module <" +
                       getSymbolicName() + ">. Message <" + pe.getMessage() +
                       ">. Aborting transaction.";
      pipeLog.fatal(Message);
      getExceptionHandler().reportException(new ProcessingException(pe));
      setTransactionAbort(getTransactionNumber());
    }
    catch (ArrayIndexOutOfBoundsException aiex)
    {
      // Not good. Abort the transaction
      String Message = "Column Index preparing error record in module <" +
                       getSymbolicName() + ">. Message <" + aiex.getMessage() +
                       ">. Aborting transaction.";
      pipeLog.fatal(Message);
      getExceptionHandler().reportException(new ProcessingException(Message, aiex));
      setTransactionAbort(getTransactionNumber());
    }
    catch (Exception ex)
    {
      // Not good. Abort the transaction
      String Message = "Unknown Exception preparing error record in module <" +
                        getSymbolicName() + ">. Message <" + ex.getMessage() +
                        ">. Aborting transaction.";
      pipeLog.fatal(Message);
      getExceptionHandler().reportException(new ProcessingException(Message, ex));
      setTransactionAbort(getTransactionNumber());
    }

    // Null return means "do not bother to process"
    if (outRecCol != null && stmtInsertQuery != null)
    {
      outRecIter = outRecCol.iterator();

      while (outRecIter.hasNext())
      {
        outRec = (DBRecord)outRecIter.next();

        try
        {
          // Prepare the parameter values
          stmtInsertQuery.clearParameters();

          for (i = 0; i < outRec.getOutputColumnCount(); i++)
          {
            if (outRec.getOutputColumnType(i) == DBRecord.COL_TYPE_STRING)
            {
              // String value
              stmtInsertQuery.setString(i + 1, outRec.getOutputColumnValueString(i));
            }
            else if (outRec.getOutputColumnType(i) == DBRecord.COL_TYPE_INTEGER)
            {
              // Integer value
              stmtInsertQuery.setInt(i + 1, outRec.getOutputColumnValueInt(i));
            }
            else if (outRec.getOutputColumnType(i) == DBRecord.COL_TYPE_DOUBLE)
            {
              // Double value
              stmtInsertQuery.setDouble(i + 1, outRec.getOutputColumnValueDouble(i));
            }
            else if (outRec.getOutputColumnType(i) == DBRecord.COL_TYPE_LONG)
            {
              // Long value
              stmtInsertQuery.setLong(i + 1, outRec.getOutputColumnValueLong(i));
            }
            else if (outRec.getOutputColumnType(i) == DBRecord.COL_TYPE_DATE)
            {
              // Date value
              long DateValue = outRec.getOutputColumnValueLong(i);
              java.sql.Date DateToSet;
              DateToSet = new java.sql.Date(DateValue);
              stmtInsertQuery.setDate(i + 1,DateToSet);
            }
            else if (outRec.getOutputColumnType(i) == DBRecord.COL_TYPE_BOOL)
            {
              // Boolean value
              boolean value;
              value = outRec.getOutputColumnValueString(i).equals("1");
              stmtInsertQuery.setBoolean(i + 1, value);
            }
            else if (outRec.getOutputColumnType(i) == DBRecord.COL_TYPE_TIME)
            {
              // Time value
              long DateValue = outRec.getOutputColumnValueLong(i);
              java.sql.Time TimeToSet;
              TimeToSet = new java.sql.Time(DateValue);
              stmtInsertQuery.setTime(i + 1,TimeToSet);
            }
            else if(outRec.getOutputColumnType(i) == DBRecord.COL_TYPE_BINARY)
            {
              // Binary value
            	byte[] byteArray = outRec.getOutputColumnValueBytes(i);
            	stmtInsertQuery.setBytes(i+1, byteArray );
            }
          }

          stmtInsertQuery.execute();
        }
        catch (SQLException Sex)
        {
          // Not good. Abort the transaction
          String Message = "SQL Exception inserting error record in module <" +
                          getSymbolicName() + ">. Message <" + Sex.getMessage() +
                          ">. Aborting transaction.";
          pipeLog.fatal(Message);
          getExceptionHandler().reportException(new ProcessingException(Message, Sex));
          setTransactionAbort(getTransactionNumber());
        }
        catch (ArrayIndexOutOfBoundsException aiex)
        {
          // Not good. Abort the transaction
          String Message = "Column Index inserting error record in module <" +
                          getSymbolicName() + ">. Message <" + aiex.getMessage() +
                          ">. Aborting transaction.";
          pipeLog.fatal(Message);
          getExceptionHandler().reportException(new ProcessingException(Message, aiex));
          setTransactionAbort(getTransactionNumber());
        }
        catch (NumberFormatException nfe)
        {
          // Not good. Abort the transaction
          String Message = "Number format inserting error record in module <" +
                          getSymbolicName() + ">. Message <" + nfe.getMessage() +
                          ">. Aborting transaction.";
          pipeLog.fatal(Message);
          getExceptionHandler().reportException(new ProcessingException(Message, nfe));
          setTransactionAbort(getTransactionNumber());
        }
        catch (Exception ex)
        {
          // Not good. Abort the transaction
          String Message = "Unknown Exception inserting error record in module <" +
                          getSymbolicName() + ">. Message <" + ex.getMessage() +
                          ">. Aborting transaction.";
          pipeLog.fatal(Message);
          getExceptionHandler().reportException(new ProcessingException(Message, ex));
          setTransactionAbort(getTransactionNumber());
        }
      }
    }

    return r;
  }

  // -----------------------------------------------------------------------------
  // ------------------ Custom connection management functions -------------------
  // -----------------------------------------------------------------------------

 /**
  * PrepareStatements creates the statements from the SQL expressions
  * so that they can be run as needed.
  */
  private void prepareStatements() throws SQLException
  {
    // prepare the SQL for the Insert statement
    if(insertQuery == null || insertQuery.isEmpty())
    {
      stmtInsertQuery = null;
    }
    else
    {
      stmtInsertQuery = JDBCcon.prepareStatement(insertQuery,
                                                 ResultSet.TYPE_SCROLL_INSENSITIVE,
                                                 ResultSet.CONCUR_READ_ONLY);
    }

    // prepare the SQL for the Commit Statement
    if (useCommit)
    {
      stmtCommitQuery = JDBCcon.prepareStatement(commitQuery,
                                                  ResultSet.TYPE_SCROLL_INSENSITIVE,
                                                  ResultSet.CONCUR_READ_ONLY);
    }

    if (useRollback)
    {
      // prepare the SQL for the Rollback Statement
      stmtRollbackQuery = JDBCcon.prepareStatement(rollbackQuery,
                                                  ResultSet.TYPE_SCROLL_INSENSITIVE,
                                                  ResultSet.CONCUR_READ_ONLY);
    }
    
    if (useInit)
    {
      // prepare the SQL for the Rollback Statement
      stmtInitQuery    = JDBCcon.prepareStatement(initQuery,
                                                  ResultSet.TYPE_SCROLL_INSENSITIVE,
                                                  ResultSet.CONCUR_READ_ONLY);
    }
  }

  /*
   * closeStream() is called by the pipeline when no more information comes
   * down it. We must perform a transaction state change here to FLUSHED
   */
  @Override
  public void closeStream(int TransactionNumber)
  {
    if (OutputStreamOpen)
    {
      setTransactionFlushed(TransactionNumber);
      OutputStreamOpen = false;
    }
  }

 /**
  * perform the final commit statement. This will only be performed if we
  * have defined a commot statement. Not all types of procesing require a
  * commit.
  *
  * @param TransactionNumber The transaction we are commiting for
  */
  public void finaliseOutputCommit(int TransactionNumber)
  {
    // commit the changes
    if (useCommit)
    {
      try
      {
        // Open the init statement
        pipeLog.debug("Adapter <" + getSymbolicName() + "> performing commit.");
        stmtCommitQuery.execute();
      }
      catch (SQLException Sex)
      {
        String Message = "JDBCOutputAdapter Error performing commit <" + Sex.getMessage() + ">";
        pipeLog.fatal(Message);
        getExceptionHandler().reportException(new ProcessingException(Message, Sex));
        setTransactionAbort(getTransactionNumber());
      }
    }
  }

 /**
  * perform the rollback statement, optionally removing records
  *
  * @param TransactionNumber The transaction number that is rolling back
  */
  public void finaliseOutputRollback(int TransactionNumber)
  {
    if (useRollback)
    {
      // rollback the changes
      try
      {
        // Open the init statement
        pipeLog.debug("Adapter <" + getSymbolicName() + "> performing rollback.");
        stmtRollbackQuery.execute();
      }
      catch (SQLException Sex)
      {
        String Message = "JDBCOutputAdapter Error performing rollback <" + Sex.getMessage() + ">";
        pipeLog.fatal(Message);
        getExceptionHandler().reportException(new ProcessingException(Message, Sex));
        setTransactionAbort(getTransactionNumber());
      }
    }
  }

 /**
  * Used to skip to the end of the stream in the case that the transaction is
  * aborted.
  *
  * @return True if the rest of the transaction was skipped otherwise false
  */
  @Override
  public boolean SkipRestOfStream()
  {
    return getTransactionAborted(getTransactionNumber());
  }

  // -----------------------------------------------------------------------------
  // ------------- Start of inherited IEventInterface functions ------------------
  // -----------------------------------------------------------------------------

 /**
  * processControlEvent is the event processing hook for the External Control
  * Interface (ECI). This allows interaction with the external world, for
  * example turning the dumping on and off.
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

    if (Command.equalsIgnoreCase(SERVICE_DATASOURCE_KEY))
    {
      if (Init)
      {
        dataSourceName = Parameter;
        ResultCode = 0;
      }
      else
      {
        if (Parameter.equals(""))
        {
          return dataSourceName;
        }
        else
        {
          return CommonConfig.NON_DYNAMIC_PARAM;
        }
      }
    }

    if (Command.equalsIgnoreCase(SERVICE_INIT_QUERY_KEY))
    {
      if (Init)
      {
        initQuery = Parameter;
        ResultCode = 0;
      }
      else
      {
        if (Parameter.equals(""))
        {
          return initQuery;
        }
        else
        {
          return CommonConfig.NON_DYNAMIC_PARAM;
        }
      }
    }

    if (Command.equalsIgnoreCase(SERVICE_INSERT_QUERY_KEY))
    {
      if (Init)
      {
        insertQuery = Parameter;
        ResultCode = 0;
      }
      else
      {
        if (Parameter.equals(""))
        {
          return insertQuery;
        }
        else
        {
          return CommonConfig.NON_DYNAMIC_PARAM;
        }
      }
    }

    if (Command.equalsIgnoreCase(SERVICE_COMMIT_QUERY_KEY))
    {
      if (Init)
      {
        commitQuery = Parameter;
        ResultCode = 0;
      }
      else
      {
        if (Parameter.equals(""))
        {
          return commitQuery;
        }
        else
        {
          return CommonConfig.NON_DYNAMIC_PARAM;
        }
      }
    }

    if (Command.equalsIgnoreCase(SERVICE_ROLLBACK_QUERY_KEY))
    {
      if (Init)
      {
        rollbackQuery = Parameter;
        ResultCode = 0;
      }
      else
      {
        if (Parameter.equals(""))
        {
          return rollbackQuery;
        }
        else
        {
          return CommonConfig.NON_DYNAMIC_PARAM;
        }
      }
    }

    if (Command.equalsIgnoreCase(SERVICE_STATUS_KEY))
    {      
      return "OK";
    }

    if (ResultCode == 0)
    {
      pipeLog.debug(LogUtil.LogECIPipeCommand(getSymbolicName(), pipeName, Command, Parameter));

      return "OK";
    }
    else
    {
      // This is not our event, pass it up the stack
      return super.processControlEvent(Command, Init, Parameter);
    }
  }

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
    super.registerClientManager();

    //Register services for this Client
    ClientManager.registerClientService(getSymbolicName(), SERVICE_DATASOURCE_KEY, ClientManager.PARAM_MANDATORY);
    ClientManager.registerClientService(getSymbolicName(), SERVICE_INIT_QUERY_KEY, ClientManager.PARAM_NONE);
    ClientManager.registerClientService(getSymbolicName(), SERVICE_INSERT_QUERY_KEY,ClientManager.PARAM_MANDATORY);
    ClientManager.registerClientService(getSymbolicName(), SERVICE_COMMIT_QUERY_KEY,ClientManager.PARAM_NONE);
    ClientManager.registerClientService(getSymbolicName(), SERVICE_ROLLBACK_QUERY_KEY,ClientManager.PARAM_NONE);
    ClientManager.registerClientService(getSymbolicName(), SERVICE_STATUS_KEY,ClientManager.PARAM_DYNAMIC);
  }

  // -----------------------------------------------------------------------------
  // --------------- Start of transactional layer functions ----------------------
  // -----------------------------------------------------------------------------

  /**
  * When a transaction is started, the transactional layer calls this method to
  * see if we have any reson to stop the transaction being started, and to do
  * any preparation work that may be necessary before we start.
  * 
  * @param transactionNumber The transaction to start
  */
  @Override
  public int startTransaction(int transactionNumber)
  {
    // We do not have any reason to inhibit the transaction start, so return
    // the OK flag
    return 0;
  }

 /**
  * Perform any processing that needs to be done when we are flushing the
  * transaction;
  * 
  * @param transactionNumber The transaction to flush
  */
  @Override
  public int flushTransaction(int transactionNumber)
  {
    // close the input stream
    closeStream(transactionNumber);

    return 0;
  }

  /**
  * Perform any processing that needs to be done when we are committing the
  * transaction;
  * 
  * @param transactionNumber The transaction to commit
  */
  @Override
  public void commitTransaction(int transactionNumber)
  {
    finaliseOutputCommit(transactionNumber);
  }

  /**
  * Perform any processing that needs to be done when we are rolling back the
  * transaction;
  * 
  * @param transactionNumber The transaction to rollback
  */
  @Override
  public void rollbackTransaction(int transactionNumber)
  {
    finaliseOutputRollback(transactionNumber);
  }

 /**
  * Close Transaction is the trigger to clean up transaction related information
  * such as variables, status etc.
  *
  * Close down the statements we opened. Because the commit and rollback
  * statements are optional, we check if they have been defined before we ry
  * to close them.
  * 
  * @param transactionNumber The transaction we are working on
  */
  @Override
  public void closeTransaction(int transactionNumber)
  {
    // Close the insert statement
    DBUtil.close(stmtInsertQuery);

    // Close the commit statement
    if(useCommit)
    {
      DBUtil.close(stmtCommitQuery);
    }

    // Close the rollback statement
    if(useRollback)
    {
      DBUtil.close(stmtRollbackQuery);
    }
    
    // Close the connection
    DBUtil.close(JDBCcon);
  }

  // -----------------------------------------------------------------------------
  // --------------- Start of custom initialisation functions ---------------------
  // -----------------------------------------------------------------------------

 /**
  * The initQuery is the query that will be executed at the beginning of a
  * new stream of data. This is executed once, and should be used to prepare
  * data for extraction.
  *
  * @return The query string
  * @throws OpenRate.exception.InitializationException
  */
  public String initInitQuery()
    throws InitializationException
  {
    String query;

    // Get the init statement from the properties
    query = PropertyUtils.getPropertyUtils().getBatchOutputAdapterPropertyValueDef(pipeName, getSymbolicName(),
                                                   INIT_QUERY_KEY,
                                                   "None");

    if ((query == null) || query.equalsIgnoreCase("None"))
    {
      String Message = "Output <" + getSymbolicName() + "> - Initialisation statement not found from <" + INIT_QUERY_KEY + ">";
      pipeLog.error(Message);
      throw new InitializationException(Message);
    }

    return query;
  }

 /**
  * The insertQuery is the query that will be executed to actually handle the
  * data writing.
  *
  * @return The query string
  * @throws OpenRate.exception.InitializationException
  */
  public String initInsertQuery()
                         throws InitializationException
  {
    String query;

    // Get the init statement from the properties
    query = PropertyUtils.getPropertyUtils().getBatchOutputAdapterPropertyValueDef(pipeName, getSymbolicName(),
                                                   INSERT_QUERY_KEY,
                                                   "None");

    if ((query == null) || query.equalsIgnoreCase("None"))
    {
      String Message = "Output <" + getSymbolicName() + "> - Initialisation statement not found from <" + INSERT_QUERY_KEY + ">";
      pipeLog.error(Message);
      throw new InitializationException(Message);
    }

    return query;
  }

 /**
  * The commitQuery is used to finally persist the records to the target table
  * and "fix" them into the table.
  *
  * @return The query string
  * @throws OpenRate.exception.InitializationException
  */
  public String initCommitQuery()
                         throws InitializationException
  {
    String query;

    // Get the init statement from the properties
    query = PropertyUtils.getPropertyUtils().getBatchOutputAdapterPropertyValueDef(pipeName, getSymbolicName(),
                                                   COMMIT_QUERY_KEY,
                                                   "None");

    if ((query == null) || query.equalsIgnoreCase("None"))
    {
      String Message = "Output <" + getSymbolicName() + "> - Initialisation statement not found from <" + COMMIT_QUERY_KEY + ">";
      pipeLog.error(Message);
      throw new InitializationException(Message);
    }

    return query;
  }

 /**
  * The rollbackQuery is used to remove any records that have been written to
  * the target table, but which have not yet been "fixed".
  *
  * @return The query string
  * @throws OpenRate.exception.InitializationException
  */
  public String initRollbackQuery()
                           throws InitializationException
  {
    String query;

    // Get the init statement from the properties
    query = PropertyUtils.getPropertyUtils().getBatchOutputAdapterPropertyValueDef(pipeName, getSymbolicName(),
                                                   ROLLBACK_QUERY_KEY,
                                                   "None");

    if ((query == null) || query.equalsIgnoreCase("None"))
    {
      String Message = "Output <" + getSymbolicName() + "> - Initialisation statement not found from <" + ROLLBACK_QUERY_KEY + ">";
      pipeLog.error(Message);
      throw new InitializationException(Message);
    }

    return query;
  }

 /**
  * Get the data source name from the properties
  *
  * @return The query string
  * @throws OpenRate.exception.InitializationException
  */
  public String initDataSourceName()
                        throws InitializationException
  {
    String DSN;
    DSN = PropertyUtils.getPropertyUtils().getBatchOutputAdapterPropertyValueDef(pipeName, getSymbolicName(),
                                                 DATASOURCE_KEY,
                                                 "None");

    if ((DSN == null) || DSN.equalsIgnoreCase("None"))
    {
      String Message = "Output <" + getSymbolicName() + "> - Datasource name not found from <" + DATASOURCE_KEY + ">";
      pipeLog.error(Message);
      throw new InitializationException(Message);
    }

    return DSN;
  }
}