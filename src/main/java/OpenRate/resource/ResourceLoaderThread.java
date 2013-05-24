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

package OpenRate.resource;

import OpenRate.configurationmanager.IEventInterface;
import OpenRate.exception.ExceptionHandler;
import OpenRate.exception.InitializationException;
import java.util.HashMap;

/**
 * Runnable container for threaded resource loading.
 * 
 * @author tgdspia1
 */
public class ResourceLoaderThread extends Thread
{
  private IResource resource;
  private String    resourceName;
  private ExceptionHandler handler;
  private ResourceContext resourceContext;
  private HashMap<String, IEventInterface> syncPointResourceMap;

  /**
   * Constructor for creating the loader thread.
   * 
   * @param tmpGrpResource The thread group we assign to.
   * @param tmpResourceName The resource we are creating for.
   */
  public ResourceLoaderThread(ThreadGroup tmpGrpResource, String tmpResourceName) 
  {
    super(tmpGrpResource,tmpResourceName);
  }
  
  /**
   * Setter for the IResource we are managing.
   * 
   * @param resourceToInit The resource we are creating the thread for.
   */
  public void setResource(IResource resourceToInit)
  {
    this.resource = resourceToInit;
  }

  /**
   * Setter for the name of the resource we are managing. Needed to access the
   * properties configuration.
   * 
   * @param resourceName The resource name.
   */
  public void setResourceName(String resourceName)
  {
    this.resourceName = resourceName;
  }
  
  /**
   * Set the exception handler mechanism.
   *
   * @param handler The exception handler to be used for this class
   */
  public void setExceptionHandler(ExceptionHandler handler)
  {
    this.handler = handler;
  }
  
 /**
  * Main execution thread of teh resource loader thread. Initialises the
  * resource and registers it with the context and sync point map before ending.
  */
  @Override
  public void run()
  {
    try
    {
      // Set the exception handler - we might need this during the intialisation
      resource.setHandler(handler);

      // initalise the resource
      resource.init(resourceName);
      
      //resource.init(tmpResourceName);
      resourceContext.register(resourceName, resource);

      // Now see if we have to register with the config manager
      if (resource instanceof IEventInterface)
      {
        // Register
        IEventInterface tmpEventIntf = (IEventInterface)resource;
        tmpEventIntf.registerClientManager();

        // Add the resource to the list of the resources that can call for
        // a sync point
        syncPointResourceMap.put(resourceName, tmpEventIntf);
      }
    }
    catch (InitializationException ie)
    {
      handler.reportException(ie);
    }
  }

  /**
   * Setter for the resource context. Used for registering the resource with 
   * the context once we have made it.
   * 
   * @param resourceContext The resource context we are using.
   */
  public void setResourceContext(ResourceContext resourceContext)
  {
    this.resourceContext = resourceContext;
  }

  /**
   * Setter for the Sync Point Resource Map. In the case that this resource
   * is bound into the synchronisation framework, we need access to the map
   * to register ourselves for management of sync point handling.
   * 
   * @param syncPointResourceMap The resource map.
   */
  public void setsyncPointResourceMap(HashMap<String, IEventInterface> syncPointResourceMap) 
  {
    this.syncPointResourceMap = syncPointResourceMap;
  }
}