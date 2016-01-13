import java.io.OutputStream;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Set;
import java.util.SortedSet;
import java.util.TimeZone;
import java.util.TreeSet;

import com.fxcm.external.api.transport.FXCMLoginProperties;
import com.fxcm.external.api.transport.GatewayFactory;
import com.fxcm.external.api.transport.IGateway;
import com.fxcm.external.api.transport.listeners.IGenericMessageListener;
import com.fxcm.external.api.transport.listeners.IStatusMessageListener;
import com.fxcm.fix.FXCMTimingIntervalFactory;
import com.fxcm.fix.IFixDefs;
import com.fxcm.fix.NotDefinedException;
import com.fxcm.fix.SubscriptionRequestTypeFactory;
import com.fxcm.fix.UTCDate;
import com.fxcm.fix.UTCTimeOnly;
import com.fxcm.fix.UTCTimestamp;
import com.fxcm.fix.posttrade.CollateralReport;
import com.fxcm.fix.pretrade.MarketDataRequest;
import com.fxcm.fix.pretrade.MarketDataRequestReject;
import com.fxcm.fix.pretrade.MarketDataSnapshot;
import com.fxcm.fix.pretrade.TradingSessionStatus;
import com.fxcm.messaging.ISessionStatus;
import com.fxcm.messaging.ITransportable;

/**
 * Example of how to request and process historical rate data from the Java API 
 * 
 * @author rkichenama
 */
public class JavaFixHistoryMiner
  implements IGenericMessageListener, IStatusMessageListener
{
  private static final String server = "http://www.fxcorporate.com/Hosts.jsp";
  private static final String TEST_CURRENCY = "EUR/USD";

  private FXCMLoginProperties login;
  private IGateway gateway;
  private String currentRequest;
  private boolean requestComplete;

  private ArrayList<CollateralReport> accounts = new ArrayList<CollateralReport>();
  private HashMap<UTCDate, MarketDataSnapshot> historicalRates = new HashMap<UTCDate, MarketDataSnapshot>();
 
  private static PrintWriter output = new PrintWriter((OutputStream)System.out, true);
  public PrintWriter getOutput() { return output; }
  public void setOutput(PrintWriter newOutput) { output = newOutput; }
  
  /**
   * Creates a new JavaFixHistoryMiner with credentials with configuration file
   * 
   * @param username
   * @param password 
   * @param terminal - which terminal to login into, dependent on the type of account, case sensitive
   * @param server - url, like 'http://www.fxcorporate.com/Hosts.jsp'
   * @param file - a local file used to define configuration
   */
  public JavaFixHistoryMiner(String username, String password, String terminal, String file)
  {
    // if file is not specified
    if(file == null)
      // create a local LoginProperty
      this.login = new FXCMLoginProperties(username, password, terminal, server);
    else
      this.login = new FXCMLoginProperties(username, password, terminal, server, file);
  }

  /**
   * Creates a new JavaFixHistoryMiner with credentials and no configuration file
   * 
   * @param username
   * @param password
   * @param terminal - which terminal to login into, dependent on the type of account, case sensitive
   * @param server - url, like 'http://www.fxcorporate.com/Hosts.jsp'
   */
  public JavaFixHistoryMiner(String username, String password, String terminal)
  {
    // call the proper constructor
    this(username, password, terminal, null);
  }

  public JavaFixHistoryMiner(String[] args)
  {
    // call the proper constructor
    this(args[0], args[1], args[2], null);
  }
  
  /**
   * Attempt to login with credentials supplied in constructor, assigning self as listeners
   */
  public boolean login()
  {
    return this.login(this, this);
  }
  
  /**
   * Attempt to login with credentials supplied in constructor
   * 
   * @param genericMessageListener - the listener object for trading events
   * @param statusMessageListener - the listener object for status events
   * 
   * @return true if login successful, false if not
   */
  public boolean login(IGenericMessageListener genericMessageListener, IStatusMessageListener statusMessageListener)
  {
    try
    {
      // if the gateway has not been defined
      if(gateway == null)
        // assign it to a new gateway created by the factory
        gateway = GatewayFactory.createGateway();
      // register the generic message listener with the gateway
      gateway.registerGenericMessageListener(genericMessageListener);
      // register the status message listener with the gateway
      gateway.registerStatusMessageListener(statusMessageListener);
      // if the gateway has not been connected
      if(!gateway.isConnected())
      {
        // attempt to login with the local login properties
        gateway.login(this.login);
      }
      else
      {
        // attempt to re-login to the api
        gateway.relogin();
      }
      // set the state of the request to be incomplete
      requestComplete = false;
      // request the current trading session status
      currentRequest = gateway.requestTradingSessionStatus();
      // wait until the request is complete
      while(!requestComplete) {}
      // return that this process was successful
      return true;
    }
    catch(Exception e) { e.printStackTrace(); }
    // if any error occurred, then return that this process failed
    return false;
  }

  /**
   * Attempt to logout, assuming that the supplied listeners reference self
   */
  public void logout()
  {
    this.logout(this, this);
  }

  /**
   * Attempt to logout, removing the supplied listeners prior to disconnection
   * 
   * @param genericMessageListener - the listener object for trading events
   * @param statusMessageListener - the listener object for status events
   */
  public void logout(IGenericMessageListener genericMessageListener, IStatusMessageListener statusMessageListener)
  {
    // attempt to logout of the api
    gateway.logout();
    // remove the generic message listener, stop listening to updates
    gateway.removeGenericMessageListener(genericMessageListener);
    // remove the status message listener, stop listening to status changes
    gateway.removeStatusMessageListener(statusMessageListener);
  }
  
  /**
   * Request a refresh of the collateral reports under the current login
   */
  public void retrieveAccounts()
  {
    // if the gateway is null then attempt to login
    if(gateway == null) this.login();
    // set the state of the request to be incomplete
    requestComplete = false;
    // request the refresh of all collateral reports
    currentRequest = gateway.requestAccounts();
    // wait until all the reqports have been processed
    while(!requestComplete) {}
  }

  /**
   * Send a fully formed order to the API and wait for the response.
   *  
   *  @return the market order number of placed trade, NONE if the trade did not execute, null on error 
   */
  public String sendRequest(ITransportable request)
  {
    try
    {
      // set the completion status of the requst to false
      requestComplete = false;
      // send the request message to the api
      currentRequest = gateway.sendMessage(request);
      // wait until the api answers on this particular request
      // while(!requestComplete) {}
      // if there is a value to return, it will be passed by currentResult
      return currentRequest;
    }
    catch(Exception e) { e.printStackTrace(); }
    // if an error occured, return no result
    return null;
  }

  /**
   * Implementing IStatusMessageListener to capture and process messages sent back from API
   * 
   * @param status - status message received by API
   */
  @Override public void messageArrived(ISessionStatus status)
  {
    // check to the status code
    if(status.getStatusCode() == ISessionStatus.STATUSCODE_ERROR ||
       status.getStatusCode() == ISessionStatus.STATUSCODE_DISCONNECTING ||
       status.getStatusCode() == ISessionStatus.STATUSCODE_CONNECTING ||
       status.getStatusCode() == ISessionStatus.STATUSCODE_CONNECTED ||
       status.getStatusCode() == ISessionStatus.STATUSCODE_CRITICAL_ERROR ||
       status.getStatusCode() == ISessionStatus.STATUSCODE_EXPIRED ||
       status.getStatusCode() == ISessionStatus.STATUSCODE_LOGGINGIN ||
       status.getStatusCode() == ISessionStatus.STATUSCODE_LOGGEDIN ||
       status.getStatusCode() == ISessionStatus.STATUSCODE_PROCESSING ||
       status.getStatusCode() == ISessionStatus.STATUSCODE_DISCONNECTED)
    {
      // display status message
      output.println("\t\t" + status.getStatusMessage());
    }
  }
    
  /**
   * Implementing IGenericMessageListener to capture and process messages sent back from API
   * 
   * @param message - message received for processing by API
   */
  @Override public void messageArrived(ITransportable message)
  {
    // decide which child function to send an cast instance of the message

    try
    {
      // if it is an instance of CollateralReport, process the collateral report 
      if(message instanceof CollateralReport) messageArrived((CollateralReport)message);
      // if it is an instance of MarketDataSnapshot, process the historical data 
      if(message instanceof MarketDataSnapshot) messageArrived((MarketDataSnapshot)message);
      // if it is an instance of MarketDataRequestReject, process the historical data request error
      if(message instanceof MarketDataRequestReject) messageArrived((MarketDataRequestReject)message);
      // if the message is an instance of TradingSessionStatus, cast it and send to child function
      else if(message instanceof TradingSessionStatus) messageArrived((TradingSessionStatus)message);
    }
    catch(Exception e) { e.printStackTrace(output); }
  }
  
  /**
   * Separate function to handle collateral report requests
   * 
   * @param cr - message interpreted as an instance of CollateralReport
   */
  public void messageArrived(CollateralReport cr)
  {
    // if this report is the result of a direct request by a waiting process
    if(currentRequest.equals(cr.getRequestID()) && !accounts.contains(cr))
    {
      // add the trading account to the account list
      accounts.add(cr);
      // set the state of the request to be completed only if this is the last collateral report
      // requested
      requestComplete = cr.isLastRptRequested();
    }
  }

  /**
 
  /**
   * Separate function to handle the trading session status updates and pull the trading instruments
   * 
   * @param tss - the message interpreted as a TradingSessionStatus instance
   */
  public void messageArrived(TradingSessionStatus tss)
  {
    // check to see if there is a request from main application for a session update
    if(currentRequest.equals(tss.getRequestID()))
    {
      // set that the request is complete for any waiting thread
      requestComplete = true;
      // attempt to set up the historical market data request
      try
      {
        // create a new market data request
        MarketDataRequest mdr = new MarketDataRequest();
        // set the subscription type to ask for only a snapshot of the history
        mdr.setSubscriptionRequestType(SubscriptionRequestTypeFactory.SNAPSHOT);
        // request the response to be formated FXCM style
        mdr.setResponseFormat(IFixDefs.MSGTYPE_FXCMRESPONSE);
        // set the intervale of the data candles
        mdr.setFXCMTimingInterval(FXCMTimingIntervalFactory.MIN15);
        // set the type set for the data candles
        mdr.setMDEntryTypeSet(MarketDataRequest.MDENTRYTYPESET_ALL);
        // configure the start and end dates
        Date now = new Date();
        Calendar calendar = (Calendar)Calendar.getInstance().clone();
        calendar.setTime(now);
        calendar.add(Calendar.DAY_OF_MONTH, -1);
        Date beforeNow = calendar.getTime();
        // set the dates and times for the market data request
        mdr.setFXCMStartDate(new UTCDate(beforeNow));
        mdr.setFXCMStartTime(new UTCTimeOnly(beforeNow));
        mdr.setFXCMEndDate(new UTCDate(now));
        mdr.setFXCMEndTime(new UTCTimeOnly(now));
        // set the instrument on which the we want the historical data
        mdr.addRelatedSymbol(tss.getSecurity(TEST_CURRENCY));
        // send the request
        sendRequest(mdr);
      }
      catch(Exception e) { e.printStackTrace(); }
    }
  }
 
  /**
   * Separate function to handle the rejection of a market data historical snapshot
   * 
   * @param mdrr - message interpreted as an instance of MarketDataRequestReject
   */
  public void messageArrived(MarketDataRequestReject mdrr)
  {
    // display note consisting of the reason the request was rejected
    output.println("Historical data rejected; " + mdrr.getMDReqRejReason());
    // set the state of the request to be complete
    requestComplete = true;
  }

  /**
   * Separate function to handle the receipt of market data snapshots
   * 
   * Current dealing rates are retrieved through the same class as historical requests. The difference
   * is that historical requests are 'answers' to a specific request.
   * 
   * @param mds
   */
  public void messageArrived(MarketDataSnapshot mds)
  {
    // if the market data snapshot is part of the answer to a specific request
    try
    {
      if(mds.getRequestID() != null && mds.getRequestID().equals(currentRequest))
      {
        // add that snapshot to the historicalRates table
        synchronized(historicalRates) { historicalRates.put(mds.getDate(), mds); }
        // set the request to be complete only if the continuous flaf is at the end
        requestComplete = (mds.getFXCMContinuousFlag() == IFixDefs.FXCMCONTINUOUS_END);
      }
    }
    catch (Exception e)
    {
      // TODO Auto-generated catch block
      e.printStackTrace();
    }
  }

  /**
   * Display the historical rates captured
   */
  public void displayHistory()
  {
    // give the table a header
    output.println("Rate 15 minute candle History for " + TEST_CURRENCY);
    // give the table column headings
    output.println("Date\t   Time\t\tOBid\tCBid\tHBid\tLBid");
    // get the keys for the historicalRates table into a sorted list
    SortedSet<UTCDate> candle = new TreeSet<UTCDate>(historicalRates.keySet());
    // define a format for the dates
    SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss z");
    // make the date formatter above convert from GMT to EST
    sdf.setTimeZone(TimeZone.getTimeZone("EST"));
    // go through the keys of the historicalRates table
    for(int i = 0; i < candle.size(); i++)
    {
      // create a single instance of the snapshot
      MarketDataSnapshot candleData;
      synchronized(historicalRates) { candleData = historicalRates.get(candle.toArray()[i]); }
      // convert the key to a Date
      Date candleDate = ((UTCDate)candle.toArray()[i]).toDate();
      // print out the historicalRate table data
      output.println(
        sdf.format(candleDate) + "\t" +    // the date and time formatted and converted to EST
        candleData.getBidOpen() + "\t" +   // the open bid for the candle
        candleData.getBidClose() + "\t" +  // the close bid for the candle
        candleData.getBidHigh() + "\t" +   // the high bid for the candle
        candleData.getBidLow());           // the low bid for the candle
    }
    // repeat the table column headings
    output.println("Date\t   Time\t\tOBid\tCBid\tHBid\tLBid");
  }

  public static void main(String[] args)
  {
    try
    {
      // create an instance of the JavaFixHistoryMiner
      JavaFixHistoryMiner miner = new JavaFixHistoryMiner("rkichenama", "1311016", "Demo");
      // login to the api
      miner.login();
      // retrieve the trader accounts to ensure login process is complete
      miner.retrieveAccounts();
      // display nore that the history display is delayed
      // partially for theatrics, partially to ensure all the rates are collected
      output.println("Displaying history in");
      // wait ~ 2.5 seconds
      for(int i = 5; i > 0; i--)
      {
        output.println(i + "...");
        Thread.sleep(500);
      }
      // display the collected rates
      miner.displayHistory();
      // log out of the api
      miner.logout();
    }
    catch (Exception e) { e.printStackTrace(); }
  }
}
