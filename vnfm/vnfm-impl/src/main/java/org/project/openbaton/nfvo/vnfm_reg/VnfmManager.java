/*
 * Copyright (c) 2015 Fraunhofer FOKUS
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

package org.project.openbaton.nfvo.vnfm_reg;

import org.project.openbaton.catalogue.mano.record.NetworkServiceRecord;
import org.project.openbaton.catalogue.mano.record.Status;
import org.project.openbaton.catalogue.mano.record.VirtualNetworkFunctionRecord;
import org.project.openbaton.catalogue.nfvo.*;
import org.project.openbaton.catalogue.util.EventFinishEvent;
import org.project.openbaton.nfvo.core.interfaces.ConfigurationManagement;
import org.project.openbaton.nfvo.core.interfaces.DependencyManagement;
import org.project.openbaton.nfvo.core.interfaces.ResourceManagement;
import org.project.openbaton.nfvo.core.interfaces.VNFLifecycleOperationGranting;
import org.project.openbaton.nfvo.common.exceptions.NotFoundException;
import org.project.openbaton.nfvo.common.exceptions.VimException;
import org.project.openbaton.nfvo.repositories_interfaces.GenericRepository;
import org.project.openbaton.nfvo.vnfm_reg.tasks.abstracts.AbstractTask;
import org.project.openbaton.vnfm.interfaces.sender.VnfmSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import javax.persistence.NoResultException;
import java.io.Serializable;
import java.util.concurrent.Future;

/**
 * Created by lto on 08/07/15.
 */
@Service
@Scope("singleton")
@Order(value = Ordered.LOWEST_PRECEDENCE)
public class VnfmManager implements org.project.openbaton.vnfm.interfaces.manager.VnfmManager, ApplicationEventPublisherAware, ApplicationListener<EventFinishEvent>, CommandLineRunner {
    protected Logger log = LoggerFactory.getLogger(this.getClass());

    @Autowired
    @Qualifier("vnfmRegister")
    private VnfmRegister vnfmRegister;

    private ApplicationEventPublisher publisher;

    private ThreadPoolTaskExecutor asyncExecutor;

    private SyncTaskExecutor serialExecutor;

    @Autowired
    private ConfigurableApplicationContext context;

    @Autowired
    private DependencyManagement dependencyManagement;

    @Autowired
    private ConfigurationManagement configurationManagement;

    @Autowired
    private ResourceManagement resourceManagement;

    @Autowired
    private VNFLifecycleOperationGranting lifecycleOperationGranting;

    @Autowired
    @Qualifier("VNFRRepository")
    private GenericRepository<VirtualNetworkFunctionRecord> vnfrRepository;

    @Autowired
    @Qualifier("NSRRepository")
    private GenericRepository<NetworkServiceRecord> nsrRepository;

    @Override
    public void init() {
        /**
         * Asynchronous thread executor configuration
         */
        Configuration system = null;
        try {
            system = configurationManagement.queryByName("system");
        } catch (NotFoundException e) {
            throw new RuntimeException(e);
        }

        this.asyncExecutor = new ThreadPoolTaskExecutor();

        this.asyncExecutor.setThreadNamePrefix("OpenbatonTask-");

        int maxPoolSize = 0;
        int corePoolSize = 0;
        int queueCapacity = 0;
        int keepAliveSeconds = 0;

        for (ConfigurationParameter configurationParameter : system.getConfigurationParameters()) {
            if (configurationParameter.getConfKey().equals("vmanager-executor-max-pool-size")) {
                maxPoolSize = Integer.parseInt(configurationParameter.getValue());
            }
            if (configurationParameter.getConfKey().equals("vmanager-executor-core-pool-size")) {
                corePoolSize = Integer.parseInt(configurationParameter.getValue());
            }
            if (configurationParameter.getConfKey().equals("vmanager-executor-queue-capacity")) {
                queueCapacity = Integer.parseInt(configurationParameter.getValue());
            }
            if (configurationParameter.getConfKey().equals("vmanager-keep-alive")) {
                keepAliveSeconds = Integer.parseInt(configurationParameter.getValue());
            }

        }

        if (maxPoolSize != 0) {
            this.asyncExecutor.setMaxPoolSize(maxPoolSize);
        } else {
            this.asyncExecutor.setMaxPoolSize(30);
        }
        if (corePoolSize != 0) {
            this.asyncExecutor.setCorePoolSize(corePoolSize);
        } else {
            this.asyncExecutor.setCorePoolSize(5);
        }

        if (queueCapacity != 0) {
            this.asyncExecutor.setQueueCapacity(queueCapacity);
        } else {
            this.asyncExecutor.setQueueCapacity(0);
        }
        if (keepAliveSeconds != 0) {
            this.asyncExecutor.setKeepAliveSeconds(keepAliveSeconds);
        } else {
            this.asyncExecutor.setKeepAliveSeconds(20);
        }


        this.asyncExecutor.initialize();

        log.trace("~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~");
        log.debug("ThreadPollTaskExecutor configuration:");
        log.debug("MaxPoolSize = " + this.asyncExecutor.getMaxPoolSize());
        log.debug("CorePoolSize = " + this.asyncExecutor.getCorePoolSize());
        log.debug("QueueCapacity = " + this.asyncExecutor.getThreadPoolExecutor().getQueue().size());
        log.debug("KeepAlive = " + this.asyncExecutor.getKeepAliveSeconds());
        log.trace("~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~");


        this.serialExecutor = new SyncTaskExecutor();

    }

    @Override
    @Async
    public Future<Void> deploy(NetworkServiceRecord networkServiceRecord) throws NotFoundException {
        for (VirtualNetworkFunctionRecord vnfr : networkServiceRecord.getVnfr()) {
            CoreMessage coreMessage = new CoreMessage();
            coreMessage.setPayload(vnfr);
            if (vnfr.getVnfPackage() == null)
                coreMessage.setAction(Action.INSTANTIATE);
            else
                coreMessage.setAction(Action.CONFIGURE);

            VnfmManagerEndpoint endpoint = vnfmRegister.getVnfm(vnfr.getEndpoint());
            if (endpoint == null) {
                throw new NotFoundException("VnfManager of type " + vnfr.getType() + " (endpoint = " + vnfr.getEndpoint() + ") is not registered");
            }

            /**
             *  TODO Here use an abstraction to call the particular vnfm_reg
             */
            VnfmSender vnfmSender;
            try {
                vnfmSender = this.getVnfmSender(endpoint.getEndpointType());
            } catch (BeansException e) {
                throw new NotFoundException(e);
            }

            vnfmSender.sendCommand(coreMessage, endpoint);
        }
        return new AsyncResult<Void>(null);
    }

    @Override
    public VnfmSender getVnfmSender(EndpointType endpointType) throws BeansException{
        String senderName = endpointType.toString().toLowerCase() + "VnfmSender";
        return (VnfmSender) this.context.getBean(senderName);
    }

    @Override
    public void executeAction(CoreMessage message) throws VimException, NotFoundException {

        String actionName = message.getAction().toString().replace("_", "").toLowerCase();
        String beanName = actionName + "Task";
        log.debug("Looking for bean called: " + beanName);
        AbstractTask task = (AbstractTask) context.getBean(beanName);

        task.setAction(message.getAction());
        VirtualNetworkFunctionRecord virtualNetworkFunctionRecord = message.getPayload();
        virtualNetworkFunctionRecord.setTask(actionName);
        task.setVirtualNetworkFunctionRecord(virtualNetworkFunctionRecord);

        if (task.isAsync()){
            asyncExecutor.submit(task);
        }else
            serialExecutor.execute(task);

        log.debug("Queue is: " + asyncExecutor.getThreadPoolExecutor().getActiveCount());

    }

    private void findAndSetNSRStatus(VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) {

        if (virtualNetworkFunctionRecord == null)
            return;

        log.debug("The nsr id is: " + virtualNetworkFunctionRecord.getParent_ns_id());

        Status status = Status.TERMINATED;
        NetworkServiceRecord networkServiceRecord;
        try {
            networkServiceRecord = nsrRepository.find(virtualNetworkFunctionRecord.getParent_ns_id());
        } catch (NoResultException e) {
            log.error("No NSR found with id " + virtualNetworkFunctionRecord.getParent_ns_id());
            return;
        }
        log.debug("Checking the status of NSR: " + networkServiceRecord.getName());

        for (VirtualNetworkFunctionRecord vnfr : networkServiceRecord.getVnfr()) {
            log.debug("VNFR " + vnfr.getName() + " is in state: " + vnfr.getStatus());
            if (status.ordinal() > vnfr.getStatus().ordinal()) {
                status = vnfr.getStatus();
            }
        }

        log.debug("Setting NSR status to: " + status);
        networkServiceRecord.setStatus(status);
        networkServiceRecord = nsrRepository.merge(networkServiceRecord);
//        log.debug("Now the status is: " + networkServiceRecord.getStatus());
        if (status.ordinal() == Status.ACTIVE.ordinal())
            publishEvent(Action.INSTANTIATE_FINISH, networkServiceRecord);
        else if (status.ordinal() == Status.TERMINATED.ordinal()) {
            publishEvent(Action.RELEASE_RESOURCES_FINISH, networkServiceRecord);
            nsrRepository.remove(networkServiceRecord);
        }
    }

    private void publishEvent(CoreMessage message) {
        publishEvent(message.getAction(), message.getPayload());
    }

    private void publishEvent(Action action, Serializable payload) {
        ApplicationEventNFVO event = new ApplicationEventNFVO(this, action, payload);
        log.debug("Publishing event: " + event);
        publisher.publishEvent(event);
    }

    @Override
    @Async
    public Future<Void> modify(VirtualNetworkFunctionRecord virtualNetworkFunctionRecordDest, CoreMessage coreMessage) throws NotFoundException {
        VnfmManagerEndpoint endpoint = vnfmRegister.getVnfm(virtualNetworkFunctionRecordDest.getEndpoint());
        if (endpoint == null) {
            throw new NotFoundException("VnfManager of type " + virtualNetworkFunctionRecordDest.getType() + " (endpoint = " + virtualNetworkFunctionRecordDest.getEndpoint() + ") is not registered");
        }
        VnfmSender vnfmSender;
        try {

            vnfmSender = this.getVnfmSender(endpoint.getEndpointType());
        } catch (BeansException e) {
            throw new NotFoundException(e);
        }

        log.debug("Sending message " + coreMessage.getAction() + " to endpoint " + endpoint);
        vnfmSender.sendCommand(coreMessage, endpoint);
        return new AsyncResult<Void>(null);
    }

    @Override
    @Async
    public Future<Void> release(VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) throws NotFoundException {
        VnfmManagerEndpoint endpoint = vnfmRegister.getVnfm(virtualNetworkFunctionRecord.getEndpoint());
        if (endpoint == null) {
            throw new NotFoundException("VnfManager of type " + virtualNetworkFunctionRecord.getType() + " (endpoint = " + virtualNetworkFunctionRecord.getEndpoint() + ") is not registered");
        }
        CoreMessage coreMessage = new CoreMessage();
        coreMessage.setAction(Action.RELEASE_RESOURCES);
        coreMessage.setPayload(virtualNetworkFunctionRecord);
        VnfmSender vnfmSender;
        try {

            vnfmSender = this.getVnfmSender(endpoint.getEndpointType());
        } catch (BeansException e) {
            throw new NotFoundException(e);
        }

        vnfmSender.sendCommand(coreMessage, endpoint);
        return new AsyncResult<Void>(null);
    }

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.publisher = applicationEventPublisher;
    }

    @Override
    public void onApplicationEvent(EventFinishEvent event) {
        VirtualNetworkFunctionRecord virtualNetworkFunctionRecord = event.getVirtualNetworkFunctionRecord();
        publishEvent(event.getAction(), virtualNetworkFunctionRecord);
        findAndSetNSRStatus(virtualNetworkFunctionRecord);
    }

    @Override
    public void run(String... args) throws Exception {
        init();
    }
}