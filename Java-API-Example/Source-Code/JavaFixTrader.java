import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import com.fxcm.external.api.transport.FXCMLoginProperties;
import com.fxcm.external.api.transport.GatewayFactory;
import com.fxcm.external.api.transport.IGateway;
import com.fxcm.external.api.transport.listeners.IGenericMessageListener;
import com.fxcm.external.api.transport.listeners.IStatusMessageListener;
import com.fxcm.external.api.util.MessageGenerator;
import com.fxcm.fix.ContingencyTypeFactory;
import com.fxcm.fix.FXCMOrdStatusFactory;
import com.fxcm.fix.IFixDefs;
import com.fxcm.fix.ISide;
import com.fxcm.fix.ITimeInForce;
import com.fxcm.fix.Instrument;
import com.fxcm.fix.NotDefinedException;
import com.fxcm.fix.OrdTypeFactory;
import com.fxcm.fix.PositionQty;
import com.fxcm.fix.SideFactory;
import com.fxcm.fix.TimeInForceFactory;
import com.fxcm.fix.TradingSecurity;
import com.fxcm.fix.posttrade.CollateralReport;
import com.fxcm.fix.posttrade.PositionReport;
import com.fxcm.fix.posttrade.RequestForPositionsAck;
import com.fxcm.fix.pretrade.MarketDataRequest;
import com.fxcm.fix.pretrade.MarketDataSnapshot;
import com.fxcm.fix.pretrade.TradingSessionStatus;
import com.fxcm.fix.trade.ExecutionReport;
import com.fxcm.fix.trade.OrderList;
import com.fxcm.fix.trade.OrderSingle;
import com.fxcm.messaging.ISessionStatus;
import com.fxcm.messaging.ITransportable;

/**
 * Example of how to use the FXCM Java API. The object can be reused, however by default it takes
 * login information at console to place short market orders on the first account under login on 
 * all available currencies, then attempt to close them approximately 5 seconds later.
 * 
 * @author Richard Kichenama
 */
public class JavaFixTrader
  implements IGenericMessageListener, IStatusMessageListener
{
  private static final String server = "http://www.fxcorporate.com/Hosts.jsp";
  private final ITimeInForce TIME_IN_FORCE = TimeInForceFactory.FILL_OR_KILL;
  private final ISide TO_OPEN = SideFactory.SELL;
  private final ISide TO_CLOSE = SideFactory.BUY;
  
  private FXCMLoginProperties login;
  private IGateway gateway;
  private String currentRequest, currentResult;
  private boolean requestComplete;
  
  private ArrayList<CollateralReport> accounts = new ArrayList<CollateralReport>();
  private ArrayList<TradingSecurity> instruments = new ArrayList<TradingSecurity>();
  private ArrayList<String> orders = new ArrayList<String>();
  private ArrayList<String> closed = new ArrayList<String>();
  private HashMap<String, PositionReport> tickets = new HashMap<String, PositionReport>();
  private HashMap<String, MarketDataSnapshot> dealing = new HashMap<String, MarketDataSnapshot>();
  private boolean opening = true;
  
  private static PrintWriter output = new PrintWriter((OutputStream)System.out, true);
  public PrintWriter getOutput() { return output; }
  public void setOutput(PrintWriter newOutput) { output = newOutput; }
  
  /**
   * Creates a new JavaFixTrader with credentials with configuration file
   * 
   * @param username
   * @param password 
   * @param terminal - which terminal to login into, dependent on the type of account, case sensitive
   * @param server - url, like 'http://www.fxcorporate.com/Hosts.jsp'
   * @param file - a local file used to define configuration
   */
  public JavaFixTrader(String username, String password, String terminal, String file)
  {
    // if file is not specified
    if(file == null)
      // create a local LoginProperty
      this.login = new FXCMLoginProperties(username, password, terminal, server);
    else
      this.login = new FXCMLoginProperties(username, password, terminal, server, file);
  }

  /**
   * Creates a new JavaFixTrader with credentials and no configuration file
   * 
   * @param username
   * @param password
   * @param terminal - which terminal to login into, dependent on the type of account, case sensitive
   * @param server - url, like 'http://www.fxcorporate.com/Hosts.jsp'
   */
  public JavaFixTrader(String username, String password, String terminal)
  {
    // call the proper constructor
    this(username, password, terminal, null);
  }

  public JavaFixTrader(String[] args)
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
    // display the number of accounts processed
    output.println("Count of Accounts: " + accounts.size());
  }

  /**
   * Send a fully formed order to the API and wait for the response.
   *  
   *  @return the market order number of placed trade, NONE if the trade did not execute, null on error 
   */
  public String sendRequest(ITransportable order)
  {
    try
    {
      // set the completion status of the requst to false
      requestComplete = false;
      // send the request message to the api
      currentRequest = gateway.sendMessage(order);
      // wait until the api answers on this particular request
      while(!requestComplete) {}
      // if there is a value to return, it will be passed by currentResult
      return currentResult;
    }
    catch(Exception e) { e.printStackTrace(); }
    // if an error occured, return no result
    return null;
  }

  /**
   * Simple function to check what the multiplier would be from the instruments min quantity to
   * a contract size
   * 
   * @param symbol - string represenation of the securitys code
   * @return 10000 if the security.isForex() is true, 1 otherwise
   */
  private int contractMultiplier(Instrument security)
  {
    if(security.isForex())
      return 10000;
    return 1;
  }
  
  /**
   * Initiate and send an order, on the first account, for each currency for the minimum lot size
   */
  public void generateBatchOrders()
  {
    // create a market order for each security available
    
    try
    {
      opening = true;
      // assign an identifier for the first account
      CollateralReport account = accounts.get(0);
      // for each security on the instruments list
      for(int i = 0; i < instruments.size(); i++)
      {
        // assign an identifier for the single instrument
        TradingSecurity instrument = instruments.get(i);
        // create the market order
        OrderSingle market = MessageGenerator.generateMarketOrder(account.getAccount(), // first account
          instrument.getFXCMMinQuantity() * contractMultiplier(instrument), // the min amt * to get contract size
          TO_OPEN, // open direction
          instrument.getSymbol(), // the symbol of the currency to place the order on
          account.getAccount()); // set the custom text of the order to be the account id
        // set the time in force to the application constant
        market.setTimeInForce(TIME_IN_FORCE);
        // send the market order and wait on the response, expecting an order id
        String order = this.sendRequest(market);
        // if the order executed, it would have an order id of something other than NONE
        if(!order.equals("NONE"))
        {
          // add the order number to the opened/placed orders list
          orders.add(order);
          // display note that an order has been successfully placed
          output.println("  " + order + " placed on " + instrument.getSymbol());
        }
      }
      // display the total number of orders requested that have successfully been placed
      output.println("Total orders placed: " + orders.size());
    }
    catch(Exception e) { e.printStackTrace(); }
  }

  /**
   * Attempt, for each order placed and resulting position, to close said position by market order
   */
  public void updateOrder()
  {
    try
    {
      opening = false;
      // display the amount of tickets tracked; the number of positions opened during application run
      output.println("Tracked Tickets: " + tickets.size());
      // for the first account under login
      CollateralReport account = accounts.get(0);
      // go through all the tracked tickets
      for(int t = 0; t < tickets.size(); t++)
      {
        // retrieve the order placed from the top of the list
        String openOrder = (String)tickets.keySet().toArray()[t];
        // retrieve the position that was tracked
        PositionReport pr = tickets.get(openOrder);
        // if it has, the pr would not be null
        if(pr != null)
        {
          //set up an order like the open position except in the opposite direction
          
          // get the position contract size
          PositionQty pq = pr.getPositionQty();
          // create the order
          OrderSingle market = MessageGenerator.generateMarketOrder(account.getAccount(), // first account
            pq.getQty(), // same quantity
            TO_CLOSE, // opposite direction
            pr.getInstrument().getSymbol(), // same symbol
            account.getAccount()); // set the custom text for the order to be the account id
          // set the time in force to the application constant
          market.setTimeInForce(TIME_IN_FORCE);
          // send the market order and wait on the response, expecting an order id
          String orderID = this.sendRequest(market);
          // if the order executed, it would have an order id of something other than NONE
          if(!orderID.equals("NONE"))
          {
            // add the order id to the closed positions list
            closed.add(orderID);
          }
        }
      }
      // display the amount of positions tracked as closed
      output.println("Total closed positions: " + closed.size());
    }
    catch(Exception e) { e.printStackTrace(); }
  }
    
  /**
   * Implementing IGenericMessageListener to capture and process messages sent back from API
   * 
   * @param message - message received for processing by API
   */
  @Override public void messageArrived(ITransportable message)
  {
    // decide which child function to send an cast instance of the message

    // if it is a an instance of MarketDataSnapshot, capture it in the dealing table
    if(message instanceof MarketDataSnapshot) messageArrived((MarketDataSnapshot)message);
    // if the message is an instance of CollateralReport, cast it and send to child function
    else if(message instanceof CollateralReport) messageArrived((CollateralReport)message);
    // if the message is an instance of ExecutionReport, cast it and send to child function
    else if(message instanceof ExecutionReport) messageArrived((ExecutionReport)message);
    // if the message is an instance of RequestForPositionsAck, cast it and send to child function
    else if(message instanceof RequestForPositionsAck) messageArrived((RequestForPositionsAck)message);
    // if the message is an instance of PositionReport, cast it and send to child function
    else if(message instanceof PositionReport) messageArrived((PositionReport)message);
    // if the message is an instance of TradingSessionStatus, cast it and send to child function
    else if(message instanceof TradingSessionStatus) messageArrived((TradingSessionStatus)message);
  }

  /**
   * Separate function to handle the processing of dealing rates as they are updated
   * 
   * @param mds - message interpreted as an instance of MarketDataSnapshot
   */
  public void messageArrived(MarketDataSnapshot mds)
  {
    // synchronize access to the dealing rates
    synchronized (dealing)
    {
      // try to place the market data snapshot into the table with the key being the Symbol
      /**
       * Since dealing is a HashMap and the rate datais indexed by the symbol, the new update
       * will overwrite the old, keeping the reference as the most updated information during
       * application run
       */
      try { dealing.put((mds).getInstrument().getSymbol(), mds); }
      catch (NotDefinedException e) { e.printStackTrace(); }
    }
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
   * Separate function to handle execution reports
   * 
   * @param er - message interpreted as an instance of ExecutionReport
   */
  public void messageArrived(ExecutionReport er)
  {
    // check to see if there is a process waiting for a response
    if(currentRequest.equals(er.getRequestID()))
    {
      // if the order status is negative
      if(er.getFXCMOrdStatus() == FXCMOrdStatusFactory.CANCELLED ||
        er.getFXCMOrdStatus() == FXCMOrdStatusFactory.PENDING_CANCEL ||
        er.getFXCMOrdStatus() == FXCMOrdStatusFactory.PENDING_CANCEL_CALCULATED ||
        er.getFXCMOrdStatus() == FXCMOrdStatusFactory.EXPIRED ||
        er.getFXCMOrdStatus() == FXCMOrdStatusFactory.REJECTED ||
        er.getFXCMOrdStatus() == FXCMOrdStatusFactory.REQUOTED ||
        er.getFXCMOrdStatus() == FXCMOrdStatusFactory.PEDNING_CALCULATED || 
        er.getFXCMOrdStatus() == FXCMOrdStatusFactory.DEALER_INTERVENTION)
      {
        // set the return value to no result
        currentResult = "NONE";
        // display notification that there was a problem with the order on the instrument
        try { output.println("Unable to place order on " + er.getInstrument().getSymbol() + "\n\t" + er.getFXCMErrorDetails()); }
        // if there was an error displaying the above, then notify on inability to place order
        catch (Exception e) { output.println("Unable to place order"); }
      }
      else
      {
        // set the return value to the order id
        currentResult = er.getOrderID();
      }
      // set the sate of the request to be complete
      requestComplete = true;
    }
    else
    // this is not a direct request but a streaming update from the api
    {
      // display message regarding the orders execution
      // attempt to display '[orderid] ([symbol]) reports '
      try { output.print("    " + er.getOrderID() + " (" + er.getInstrument().getSymbol() + ") reports "); }
      // ignore any problems and continue
      catch (Exception e) { }
      // add the status as text to the output line
      if(er.getFXCMOrdStatus() == FXCMOrdStatusFactory.WAITING) output.println("WAITING");
      // add the status as text to the output line
      if(er.getFXCMOrdStatus() == FXCMOrdStatusFactory.EXECUTING) output.println("EXECUTING");
      // add the status as text to the output line
      if(er.getFXCMOrdStatus() == FXCMOrdStatusFactory.INPROCESS) output.println("INPROCESS");
      // add the status as executed and details of the position affected
      if(er.getFXCMOrdStatus() == FXCMOrdStatusFactory.EXECUTED) output.println("EXECUTED on " + er.getFXCMPosID() +
        " at " + er.getPrice() + " for " + er.getOrderQty());
    }
  }

  /**
   * Separate function to handle the requests for positions
   * 
   * @param rfpa - message interpreted as an instance of RequestForPositionsAck
   */
  public void messageArrived(RequestForPositionsAck rfpa)
  {
    // if there is a halted thread execution waiting for a response
    if(currentRequest.equals(rfpa.getRequestID()))
    {
      // indicate that this request is complete
      requestComplete = true;
    }    
  }

  /**
   * Separate function to handle the position reports
   * 
   * @param pr - message interpreted as an instance of PositionReport
   */
  public void messageArrived(PositionReport pr)
  {
    // add the position report to the tickets list, key being the order id
    if(opening) tickets.put(pr.getOrderID(), pr);
    output.println("      " + pr.getOrderID() + " now tracked as position " + pr.getFXCMPosID());
  }

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
      // it is a requested update, so we draw information from it
      try
      {
        // clear the instrument list
        instruments.clear();
        // draw an Enumeration of TradingSecurity from the trading session status
        @SuppressWarnings("unchecked") Enumeration<TradingSecurity> securities = (Enumeration<TradingSecurity>)tss.getSecurities();
        // while there are more securities available
        while(securities.hasMoreElements())
        {
          // add it to the instruments list
          instruments.add(securities.nextElement());
        }
      }
      catch(Exception e)
      {
        e.printStackTrace();
      }
      // set that the request is complete for any waiting thread
      requestComplete = true;
    }
  }
  
  /**
   * Separate function to handle the market data request for historical dealing rates
   */
  public void messageArrived(MarketDataRequest message)
  {
    
  }
  
  /**
   * Implementing IStatusMessageListener to capture and process messages sent back from API
   * 
   * @param status - status message received by API
   */
  @Override public void messageArrived(ISessionStatus status)
  {
    // check to see if the status code is an Error, Disconnecting or Disconnected warning
    if(status.getStatusCode() == ISessionStatus.STATUSCODE_ERROR ||
       status.getStatusCode() == ISessionStatus.STATUSCODE_DISCONNECTING ||
       status.getStatusCode() == ISessionStatus.STATUSCODE_DISCONNECTED)
    {
      // display error message
      output.println(status.getStatusMessage());
    }
  }
  
  public static void main(String[] args)
	{
 // verify that the program is run with enough command line arguments
	  if(args.length >= 3)
	  {
  	  try
      {
  	    // create a new instance of the example class
        JavaFixTrader jt = new JavaFixTrader(args);
        // attempt to login
        output.println("Logging in");
        // trigger the collection of the dealing rates as well as login
        jt.login();
        // gather the collateral reports into accounts map
        // also gain a list of all the instruments as TradingSecuritys
        jt.retrieveAccounts();
        // generate the market orders to open a position on each instrument
        jt.generateBatchOrders();
        // wait 5 seconds after notifying
  	    output.println("Waiting...");
        Thread.sleep(5000);
        // generate market orders to close a position for each instrument
  	    jt.updateOrder();
  	    // processing of the base example done, attempt to log out
  	    output.println("Logging out");
  	    jt.logout();
  	    // end application
  	    output.println("Done");
  	    System.exit(0);
      }
      catch (Exception e) { e.printStackTrace(); }
	  }
	  else
	    // otherwise deplay a notice
	    output.println("USAGE: <username> <password> <terminal>");
	}

  /**
   * Place an entry order with stop and limit attached relative to the current dealing rate
   */
  public String stopBracketOrder(String currency, int entryDistance, int stopDistance, int limitDistance)
  {
    // create an order list
    OrderList ol = new OrderList();
    // set the contingency for the order list to ELS, signaling that the orders are linked as Entry, stop, and limit
    ol.setContingencyType(ContingencyTypeFactory.ELS);
    // get the most current rate data
    // create a new market data snapshot and set it to null
    MarketDataSnapshot quote = null;
    // attempt to gain access to the dealing rates table and assign the object above to the most updated
    // market data snapshot
    synchronized (dealing)
    { quote = dealing.get(currency); }
    // calculate the rates using the instruments point size to ensure proper decimal placing
    // the entry orders trigger rate will be below the current Bid (for Entry Stop Sell) 
    double entryRate = quote.getBidClose() - (entryDistance * quote.getInstrument().getFXCMSymPointSize());
    // the stop rate is relative to the entry rate, and is the pip distance specified from entry
    double stopRate = entryRate + (stopDistance * quote.getInstrument().getFXCMSymPointSize());
    // the limit rate is relative to the entry rate, and is the pip distance specified from entry
    double limitRate = entryRate - (limitDistance * quote.getInstrument().getFXCMSymPointSize());
    double lotSize = quote.getInstrument().getFXCMMinQuantity() * contractMultiplier(quote.getInstrument());
    // create the bracket order
    // create the primary: the entry stop sell order
    OrderSingle myOrder = MessageGenerator.generateStopLimitEntry(
      entryRate,                          // trigger rate
      OrdTypeFactory.STOP,                // type of entry order
      accounts.get(0).getAccount(),       // account to place the order on
      lotSize,                            // the amount of contracts for the order 
      SideFactory.SELL,                   // direction for the order
      currency,                           // the currency to place the order on
      "primary order");                   // some custom text 
    // set this order to be the Primary in a linked group
    myOrder.setClOrdLinkID(IFixDefs.CLORDLINKID_PRIMARY);
    // add the order to the list
    ol.addOrder(myOrder); 
    // create the contingent stop order
    OrderSingle stop = MessageGenerator.generateStopLimitClose( 
      stopRate,                           // trigger rate
      null,                               // the order this stop is attached to does not yet have a postion id
      OrdTypeFactory.STOP,                // type of linked conditional order
      accounts.get(0).getAccount(),       // account to place the order on
      lotSize,                            // the amount of contracts for the order 
      SideFactory.BUY,                    // direction for the order at execution, opposite of entry order
      currency,                           // the currency to place the order on
      "stop loss");                       // some custom text
    // set the stop to be contingent on the Primary order
    stop.setClOrdLinkID(IFixDefs.CLORDLINKID_CONTINGENT);
    // add the order to the list
    ol.addOrder(stop);
    // create the contingent limit order
    OrderSingle limit = MessageGenerator.generateStopLimitClose( 
      limitRate,                          // trigger rate 
      null,                               // the order this limit is attached to does not yet have a postion id
      OrdTypeFactory.LIMIT,               // type of linked conditional order
      accounts.get(0).getAccount(),       // account to place the order on
      lotSize,                            // the amount of contracts for the order 
      SideFactory.BUY,                    // direction for the order at execution, opposite of entry order
      currency,                           // the currency to place the order on
      "limit profit");                    // some custom text
    // set the stop to be contingent on the Primary order
    limit.setClOrdLinkID(IFixDefs.CLORDLINKID_CONTINGENT);
    // add the order to the list
    ol.addOrder(limit);
    // send the order to the API and recieve the order id of the primary entry order
    String els = sendRequest(ol);
    // return to the caller of this function the entry order id
    return els;
  }

}
