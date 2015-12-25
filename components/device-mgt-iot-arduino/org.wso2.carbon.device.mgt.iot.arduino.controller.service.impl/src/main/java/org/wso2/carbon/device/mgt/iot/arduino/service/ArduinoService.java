/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.device.mgt.iot.arduino.service;

import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.annotations.api.API;
import org.wso2.carbon.apimgt.annotations.device.DeviceType;
import org.wso2.carbon.apimgt.annotations.device.feature.Feature;
import org.wso2.carbon.apimgt.webapp.publisher.KeyGenerationUtil;
import org.wso2.carbon.device.mgt.common.Device;
import org.wso2.carbon.device.mgt.common.DeviceIdentifier;
import org.wso2.carbon.device.mgt.common.DeviceManagementException;
import org.wso2.carbon.device.mgt.common.EnrolmentInfo;
import org.wso2.carbon.device.mgt.iot.DeviceManagement;
import org.wso2.carbon.device.mgt.iot.DeviceValidator;
import org.wso2.carbon.device.mgt.iot.apimgt.AccessTokenInfo;
import org.wso2.carbon.device.mgt.iot.apimgt.TokenClient;
import org.wso2.carbon.device.mgt.iot.arduino.plugin.constants.ArduinoConstants;
import org.wso2.carbon.device.mgt.iot.arduino.service.dto.DeviceJSON;
import org.wso2.carbon.device.mgt.iot.arduino.service.transport.ArduinoMQTTSubscriber;
import org.wso2.carbon.device.mgt.iot.arduino.service.util.ArduinoServiceUtils;
import org.wso2.carbon.device.mgt.iot.controlqueue.mqtt.MqttConfig;
import org.wso2.carbon.device.mgt.iot.exception.AccessTokenException;
import org.wso2.carbon.device.mgt.iot.exception.DeviceControllerException;
import org.wso2.carbon.device.mgt.iot.sensormgt.SensorDataManager;
import org.wso2.carbon.device.mgt.iot.util.ZipArchive;
import org.wso2.carbon.device.mgt.iot.util.ZipUtil;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@API( name="arduino", version="1.0.0", context="/arduino")
@DeviceType( value = "arduino")
public class ArduinoService {

    private static Log log = LogFactory.getLog(ArduinoService.class);

    //TODO; replace this tenant domain
    private static final String SUPER_TENANT = "carbon.super";

    @Context  //injected response proxy supporting multiple thread
    private HttpServletResponse response;

    public static final String HTTP_PROTOCOL = "HTTP";
    public static final String MQTT_PROTOCOL = "MQTT";

    private ArduinoMQTTSubscriber arduinoMQTTSubscriber;
    private static Map<String, LinkedList<String>> replyMsgQueue = new HashMap<>();
    private static Map<String, LinkedList<String>> internalControlsQueue = new HashMap<>();
    private ConcurrentHashMap<String, String> deviceToIpMap = new ConcurrentHashMap<>();

    /**
     * @param arduinoMQTTSubscriber an object of type "ArduinoMQTTSubscriber" specific for this ArduinoService
     */
    @SuppressWarnings("unused")
    public void setArduinoMQTTSubscriber(
            final ArduinoMQTTSubscriber arduinoMQTTSubscriber) {
        this.arduinoMQTTSubscriber = arduinoMQTTSubscriber;

        if (MqttConfig.getInstance().isEnabled()) {
            Runnable xmppStarter = new Runnable() {
                @Override
                public void run() {
                    arduinoMQTTSubscriber.initConnector();
                    arduinoMQTTSubscriber.connectAndSubscribe();
                }
            };

            Thread xmppStarterThread = new Thread(xmppStarter);
            xmppStarterThread.setDaemon(true);
            xmppStarterThread.start();
        } else {
            log.warn("MQTT disabled in 'devicemgt-config.xml'. Hence, ArduinoMQTTSubscriber not started.");
        }
    }

    /**
     * @return the "ArduinoMQTTSubscriber" object of this ArduinoService instance
     */
    @SuppressWarnings("unused")
    public ArduinoMQTTSubscriber getArduinoMQTTSubscriber() {
        return arduinoMQTTSubscriber;
    }

    /**
     * @return the queue containing all the MQTT reply messages from all Arduinos communicating to this service
     */
    public static Map<String, LinkedList<String>> getReplyMsgQueue() {
        return replyMsgQueue;
    }

    /**
     * @return the queue containing all the MQTT controls received to be sent to any Arduinos connected to this server
     */
    public static Map<String, LinkedList<String>> getInternalControlsQueue() {
        return internalControlsQueue;
    }

    /*	---------------------------------------------------------------------------------------
                    Device specific APIs - Control APIs + Data-Publishing APIs
        ---------------------------------------------------------------------------------------	*/

    /**
     * @param owner
     * @param deviceId
     * @param deviceIP
     * @param devicePort
     * @param response
     * @param request
     * @return
     */
    @Path("controller/register/{owner}/{deviceId}/{ip}/{port}")
    @POST
    public String registerDeviceIP(@PathParam("owner") String owner,
                                   @PathParam("deviceId") String deviceId,
                                   @PathParam("ip") String deviceIP,
                                   @PathParam("port") String devicePort,
                                   @Context HttpServletResponse response,
                                   @Context HttpServletRequest request) {

        //TODO:: Need to get IP from the request itself
        String result;

        if (log.isDebugEnabled()) {
            log.debug("Got register call from IP: " + deviceIP + " for Device ID: " + deviceId + " of owner: " + owner);
        }

        String deviceHttpEndpoint = deviceIP + ":" + devicePort;
        deviceToIpMap.put(deviceId, deviceHttpEndpoint);

        result = "Device-IP Registered";
        response.setStatus(Response.Status.OK.getStatusCode());

        if (log.isDebugEnabled()) {
            log.debug(result);
        }

        return result;
    }

    /**
     * @param owner
     * @param deviceId
     * @param protocol
     * @param state
     * @param response
     */
    @Path("controller/bulb")
    @POST
    @Feature( code="bulb", name="Control Bulb", type="operation",
            description="Control Bulb on Arduino Uno")
    public void switchBulb(@HeaderParam("owner") String owner,
                           @HeaderParam("deviceId") String deviceId,
                           @HeaderParam("protocol") String protocol,
                           @FormParam("state") String state,
                           @Context HttpServletResponse response) {

        try {
            DeviceValidator deviceValidator = new DeviceValidator();
            if (!deviceValidator.isExist(owner, SUPER_TENANT, new DeviceIdentifier(deviceId,
                                                                                   ArduinoConstants.DEVICE_TYPE))) {
                response.setStatus(Response.Status.UNAUTHORIZED.getStatusCode());
                return;
            }
        } catch (DeviceManagementException e) {
            log.error("DeviceValidation Failed for deviceId: " + deviceId + " of user: " + owner);
            response.setStatus(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
            return;
        }

        String switchToState = state.toUpperCase();

        if (!switchToState.equals(ArduinoConstants.STATE_ON) && !switchToState.equals(ArduinoConstants.STATE_OFF)) {
            log.error("The requested state change shoud be either - 'ON' or 'OFF'");
            response.setStatus(Response.Status.BAD_REQUEST.getStatusCode());
            return;
        }

        String protocolString = protocol.toUpperCase();
        String callUrlPattern = ArduinoConstants.BULB_CONTEXT + switchToState;

        if (log.isDebugEnabled()) {
            log.debug("Sending request to switch-bulb of device [" + deviceId + "] via " +
                              protocolString);
        }

        try {
            switch (protocolString) {
                case HTTP_PROTOCOL:
                    String deviceHTTPEndpoint = deviceToIpMap.get(deviceId);
                    if (deviceHTTPEndpoint == null) {
                        response.setStatus(Response.Status.PRECONDITION_FAILED.getStatusCode());
                        return;
                    }
                    ArduinoServiceUtils.sendCommandViaHTTP(deviceHTTPEndpoint, callUrlPattern, true);
                    break;
                case MQTT_PROTOCOL:
                    String mqttMessage = ArduinoConstants.BULB_CONTEXT.replace("/", "");
                    ArduinoServiceUtils.sendCommandViaMQTT(owner, deviceId, mqttMessage, switchToState);
                    break;
                default:
                    response.setStatus(Response.Status.NOT_ACCEPTABLE.getStatusCode());
                    return;
            }
        } catch (DeviceManagementException e) {
            log.error("Failed to send switch-bulb request to device [" + deviceId + "] via " + protocolString);
            response.setStatus(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
            return;
        }
        response.setStatus(Response.Status.OK.getStatusCode());
    }


    /**
     * @param dataMsg
     * @param response
     */
    @Path("controller/pushdata")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public void pushData(final DeviceJSON dataMsg, @Context HttpServletResponse response) {

        String owner = dataMsg.owner;
        String deviceId = dataMsg.deviceId;
        String deviceIp = dataMsg.reply;            //TODO:: Get IP from request
        float pinData = dataMsg.value;

        try {
            DeviceValidator deviceValidator = new DeviceValidator();
            if (!deviceValidator.isExist(owner, SUPER_TENANT, new DeviceIdentifier(deviceId,
                                                                                   ArduinoConstants.DEVICE_TYPE))) {
                response.setStatus(Response.Status.UNAUTHORIZED.getStatusCode());
                log.warn("Data Received from unregistered Arduino device [" + deviceId + "] for owner [" + owner + "]");
                return;
            }

            String registeredIp = deviceToIpMap.get(deviceId);

            if (registeredIp == null) {
                log.warn("Unregistered IP: Arduino Pin Data Received from an un-registered IP " + deviceIp +
                                 " for device ID - " + deviceId);
                response.setStatus(Response.Status.PRECONDITION_FAILED.getStatusCode());
                return;
            } else if (!registeredIp.equals(deviceIp)) {
                log.warn("Conflicting IP: Received IP is " + deviceIp + ". Device with ID " + deviceId +
                                 " is already registered under some other IP. Re-registration required");
                response.setStatus(Response.Status.CONFLICT.getStatusCode());
                return;
            }

            if (log.isDebugEnabled()) {
                log.debug("Received Pin Data Value: " + pinData + " degrees C");
            }
            SensorDataManager.getInstance().setSensorRecord(deviceId, ArduinoConstants.SENSOR_TEMPERATURE,
                                                            String.valueOf(pinData),
                                                            Calendar.getInstance().getTimeInMillis());

            if (!ArduinoServiceUtils.publishToDAS(dataMsg.owner, dataMsg.deviceId, dataMsg.value)) {
                response.setStatus(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
                log.warn("An error occured whilst trying to publish pin data of Arduino with ID [" + deviceId +
                                 "] of owner [" + owner + "]");
            }

        } catch (DeviceManagementException e) {
            String errorMsg = "Validation attempt for deviceId [" + deviceId + "] of owner [" + owner + "] failed.\n";
            log.error(errorMsg + Response.Status.INTERNAL_SERVER_ERROR.getReasonPhrase() + "\n" + e.getErrorMessage());
        }
    }

    /**
     * @param owner
     * @param deviceId
     * @param response
     * @return
     */
    @Path("controller/readcontrols")
    @GET
    public String readControls(@HeaderParam("owner") String owner,
                               @HeaderParam("deviceId") String deviceId,
                               @HeaderParam("protocol") String protocol,
                               @Context HttpServletResponse response) {
        String result;
        LinkedList<String> deviceControlList = internalControlsQueue.get(deviceId);

        if (deviceControlList == null) {
            result = "No controls have been set for device " + deviceId + " of owner " + owner;
            response.setStatus(HttpStatus.SC_NO_CONTENT);
        } else {
            try {
                result = deviceControlList.remove(); //returns the  head value
                response.setStatus(HttpStatus.SC_ACCEPTED);

            } catch (NoSuchElementException ex) {
                result = "There are no more controls for device " + deviceId + " of owner " +
                        owner;
                response.setStatus(HttpStatus.SC_NO_CONTENT);
            }
        }
        if (log.isDebugEnabled()) {
            log.debug(result);
        }

        return result;
    }


    /**
     * @param dataMsg
     * @param response
     */
    @Path("controller/push_temperature")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public void pushTemperatureData(final DeviceJSON dataMsg,
                                    @Context HttpServletResponse response,
                                    @Context HttpServletRequest request) {
        String owner = dataMsg.owner;
        String deviceId = dataMsg.deviceId;
        String deviceIp = dataMsg.reply;            //TODO:: Get IP from request
        float temperature = dataMsg.value;

        try {
            DeviceValidator deviceValidator = new DeviceValidator();
            if (!deviceValidator.isExist(owner, SUPER_TENANT, new DeviceIdentifier(deviceId,
                                                                                   ArduinoConstants.DEVICE_TYPE))) {
                response.setStatus(Response.Status.UNAUTHORIZED.getStatusCode());
                log.warn("Temperature data Received from unregistered Arduino device [" + deviceId + "] for owner [" +
                                 owner + "]");
                return;
            }

            String registeredIp = deviceToIpMap.get(deviceId);

            if (registeredIp == null) {
                log.warn("Unregistered IP: Temperature Data Received from an un-registered IP " + deviceIp +
                                 " for device ID - " + deviceId);
                response.setStatus(Response.Status.PRECONDITION_FAILED.getStatusCode());
                return;
            } else if (!registeredIp.equals(deviceIp)) {
                log.warn("Conflicting IP: Received IP is " + deviceIp + ". Device with ID " + deviceId +
                                 " is already registered under some other IP. Re-registration required");
                response.setStatus(Response.Status.CONFLICT.getStatusCode());
                return;
            }

            if (log.isDebugEnabled()) {
                log.debug("Received Pin Data Value: " + temperature + " degrees C");
            }
            SensorDataManager.getInstance().setSensorRecord(deviceId, ArduinoConstants.SENSOR_TEMPERATURE,
                                                            String.valueOf(temperature),
                                                            Calendar.getInstance().getTimeInMillis());

            if (!ArduinoServiceUtils.publishToDAS(dataMsg.owner, dataMsg.deviceId, dataMsg.value)) {
                response.setStatus(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
                log.warn("An error occured whilst trying to publish temperature data of Arduino with ID [" + deviceId +
                                 "] of owner [" + owner + "]");
            }

        } catch (DeviceManagementException e) {
            String errorMsg = "Validation attempt for deviceId [" + deviceId + "] of owner [" + owner + "] failed.\n";
            log.error(errorMsg + Response.Status.INTERNAL_SERVER_ERROR.getReasonPhrase() + "\n" + e.getErrorMessage());
        }
    }
}