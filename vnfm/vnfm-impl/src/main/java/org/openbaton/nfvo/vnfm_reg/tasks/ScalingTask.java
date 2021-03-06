/*
 * Copyright (c) 2015-2018 Open Baton (http://openbaton.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.openbaton.nfvo.vnfm_reg.tasks;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.openbaton.catalogue.mano.common.Event;
import org.openbaton.catalogue.mano.descriptor.VNFComponent;
import org.openbaton.catalogue.mano.descriptor.VirtualDeploymentUnit;
import org.openbaton.catalogue.mano.record.Status;
import org.openbaton.catalogue.mano.record.VNFCInstance;
import org.openbaton.catalogue.nfvo.Action;
import org.openbaton.catalogue.nfvo.messages.Interfaces.NFVMessage;
import org.openbaton.catalogue.nfvo.messages.OrVnfmErrorMessage;
import org.openbaton.catalogue.nfvo.messages.OrVnfmGenericMessage;
import org.openbaton.catalogue.nfvo.viminstances.BaseVimInstance;
import org.openbaton.exceptions.VimDriverException;
import org.openbaton.exceptions.VimException;
import org.openbaton.nfvo.core.interfaces.ResourceManagement;
import org.openbaton.nfvo.core.interfaces.VNFLifecycleOperationGranting;
import org.openbaton.nfvo.core.interfaces.VnfPlacementManagement;
import org.openbaton.nfvo.vnfm_reg.tasks.abstracts.AbstractTask;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

/** Created by lto on 06/08/15. */
@Service
@Scope("prototype")
public class ScalingTask extends AbstractTask {

  @Autowired private ResourceManagement resourceManagement;
  @Autowired private VNFLifecycleOperationGranting lifecycleOperationGranting;

  @Value("${nfvo.quota.check:true}")
  private boolean checkQuota;

  @Autowired private VnfPlacementManagement vnfPlacementManagement;
  private BaseVimInstance vimInstance;

  public void setUserdata(String userdata) {
    this.userdata = userdata;
  }

  private String userdata;

  @Override
  protected NFVMessage doWork() throws Exception {

    log.debug("NFVO: SCALING");
    log.trace("VNFR reiceived hibernate version = " + virtualNetworkFunctionRecord.getHbVersion());
    log.debug(
        "The VNFR: "
            + virtualNetworkFunctionRecord.getName()
            + " is in status: "
            + virtualNetworkFunctionRecord.getStatus());

    saveVirtualNetworkFunctionRecord();

    VNFComponent componentToAdd = null;
    VirtualDeploymentUnit vdu = null;
    for (VirtualDeploymentUnit virtualDeploymentUnit : virtualNetworkFunctionRecord.getVdu()) {
      for (VNFComponent vnfComponent : virtualDeploymentUnit.getVnfc()) {
        boolean found = false;
        for (VNFCInstance vnfcInstance : virtualDeploymentUnit.getVnfc_instance()) {
          if (vnfComponent.getId().equals(vnfcInstance.getVnfComponent().getId())) {
            found = true;
            break;
          }
        }
        if (!found) { // new vnfComponent!
          componentToAdd = vnfComponent;
          vdu = virtualDeploymentUnit;
          break;
        }
      }
    }

    log.info(
        "The VNFC to add to VNFR ("
            + virtualNetworkFunctionRecord.getId()
            + ") is: "
            + componentToAdd);
    if (vdu == null)
      throw new VimException("Error while scaling, no vdu found. This should not happen");
    Map<String, BaseVimInstance> vimInstanceMap = new HashMap<>();
    if (checkQuota) {
      if (vimInstance == null) {
        vimInstanceMap =
            lifecycleOperationGranting.grantLifecycleOperation(virtualNetworkFunctionRecord);
      } else {
        vimInstance =
            lifecycleOperationGranting.checkQuotaOnVimInstance(
                virtualNetworkFunctionRecord, vimInstance);
      }
      try {
        if (vimInstance == null
            && vimInstanceMap.size()
                == virtualNetworkFunctionRecord.getVdu().size()) { // TODO needs to be one?

          Future<VNFCInstance> future =
              resourceManagement.allocate(
                  vdu,
                  virtualNetworkFunctionRecord,
                  componentToAdd,
                  vimInstanceMap.get(vdu.getId()),
                  userdata);
          log.debug(
              "Added new VNFC with id: "
                  + future.get()
                  + " to VNFR "
                  + virtualNetworkFunctionRecord.getId());
        } else if (vimInstance != null) {
          Future<VNFCInstance> future =
              resourceManagement.allocate(
                  vdu, virtualNetworkFunctionRecord, componentToAdd, vimInstance, userdata);
          log.debug(
              "Added new VNFC with id: "
                  + future.get()
                  + " to VNFR "
                  + virtualNetworkFunctionRecord.getId());
        } else {
          log.error(
              "Not enough resources on any of the PoPs in order to scale out. Please check your quota");
          log.error(
              "VNFR " + virtualNetworkFunctionRecord.getName() + " remains in status ACTIVE.");
          virtualNetworkFunctionRecord.setStatus(Status.ACTIVE);
          OrVnfmErrorMessage errorMessage = new OrVnfmErrorMessage();
          errorMessage.setMessage(
              "Not enough resources on any of the PoPs in order to scale out. Please check your quota");
          errorMessage.setVnfr(virtualNetworkFunctionRecord);
          errorMessage.setAction(Action.ERROR);
          saveVirtualNetworkFunctionRecord();
          vnfmManager.findAndSetNSRStatus(virtualNetworkFunctionRecord);
          return errorMessage;
        }
      } catch (ExecutionException exe) {
        try {
          Throwable realException = exe.getCause();
          if (realException instanceof VimException) {
            throw (VimException) realException;
          }
          if (realException instanceof VimDriverException) {
            throw (VimDriverException) realException;
          }
          throw exe;
        } catch (VimException e) {
          resourceManagement.release(vdu, e.getVnfcInstance());
          virtualNetworkFunctionRecord.setStatus(Status.ACTIVE);
          saveVirtualNetworkFunctionRecord();
          OrVnfmErrorMessage errorMessage = new OrVnfmErrorMessage();
          errorMessage.setMessage(
              "Error creating VM for VNFR ("
                  + virtualNetworkFunctionRecord.getId()
                  + ") while scaling out. "
                  + e.getLocalizedMessage());
          errorMessage.setVnfr(virtualNetworkFunctionRecord);
          errorMessage.setAction(Action.ERROR);
          vnfmManager.findAndSetNSRStatus(virtualNetworkFunctionRecord);
          return errorMessage;
        } catch (VimDriverException e) {
          virtualNetworkFunctionRecord.setStatus(Status.ACTIVE);
          saveVirtualNetworkFunctionRecord();
          OrVnfmErrorMessage errorMessage = new OrVnfmErrorMessage();
          errorMessage.setMessage(
              "Error creating VM for VNFR ("
                  + virtualNetworkFunctionRecord.getId()
                  + ") while scaling out. "
                  + e.getLocalizedMessage());
          errorMessage.setVnfr(virtualNetworkFunctionRecord);
          errorMessage.setAction(Action.ERROR);
          vnfmManager.findAndSetNSRStatus(virtualNetworkFunctionRecord);
          return errorMessage;
        }
      }
    } else {
      log.warn(
          "Please consider turning the check quota (nfvo.quota.check in openbaton-nfvo.properties) to true.");
      try {
        if (vimInstance == null) {
          log.debug(
              "Added new component with id: "
                  + resourceManagement
                      .allocate(
                          vdu,
                          virtualNetworkFunctionRecord,
                          componentToAdd,
                          vnfPlacementManagement.choseRandom(
                              vdu.getVimInstanceName(),
                              virtualNetworkFunctionRecord.getProjectId()),
                          userdata)
                      .get());
        } else {
          log.debug(
              "Added new component with id: "
                  + resourceManagement
                      .allocate(
                          vdu, virtualNetworkFunctionRecord, componentToAdd, vimInstance, userdata)
                      .get());
        }
      } catch (ExecutionException | VimException exception) {
        VimException e;
        if (exception instanceof VimException) e = (VimException) exception;
        else if (exception.getCause() instanceof VimException)
          e = (VimException) exception.getCause();
        else throw exception;
        log.error(e.getLocalizedMessage());
        if (e.getVnfcInstance() != null) {
          resourceManagement.release(vdu, e.getVnfcInstance());
        }
        virtualNetworkFunctionRecord.setStatus(Status.ACTIVE);
        OrVnfmErrorMessage errorMessage = new OrVnfmErrorMessage();
        errorMessage.setMessage(
            "Error creating VM for VNFR ("
                + virtualNetworkFunctionRecord.getId()
                + ") while scaling out. Please consider enabling checkQuota ;)");
        errorMessage.setVnfr(virtualNetworkFunctionRecord);
        errorMessage.setAction(Action.ERROR);
        saveVirtualNetworkFunctionRecord();
        vnfmManager.findAndSetNSRStatus(virtualNetworkFunctionRecord);
        return errorMessage;
      }
    }
    setHistoryLifecycleEvent();
    saveVirtualNetworkFunctionRecord();
    log.trace(
        "VNFR ("
            + virtualNetworkFunctionRecord.getId()
            + ") received hibernate version = "
            + virtualNetworkFunctionRecord.getHbVersion());
    return new OrVnfmGenericMessage(virtualNetworkFunctionRecord, Action.SCALED);
  }

  @Override
  public boolean isAsync() {
    return true;
  }

  @Override
  protected void setEvent() {
    event = Event.SCALE_OUT.name();
  }

  @Override
  protected void setDescription() {
    description = "The resources of this VNFR are scaling at the moment";
  }

  public void setVimInstance(BaseVimInstance vimInstance) {
    this.vimInstance = vimInstance;
  }
}
