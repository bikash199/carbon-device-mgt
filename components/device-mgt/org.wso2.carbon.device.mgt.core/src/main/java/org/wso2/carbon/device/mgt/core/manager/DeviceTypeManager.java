/*
 *   Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *   WSO2 Inc. licenses this file to you under the Apache License,
 *   Version 2.0 (the "License"); you may not use this file except
 *   in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing,
 *   software distributed under the License is distributed on an
 *   "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *   KIND, either express or implied.  See the License for the
 *   specific language governing permissions and limitations
 *   under the License.
 *
 */
package org.wso2.carbon.device.mgt.core.manager;

import org.wso2.carbon.device.mgt.common.DeviceType;
import org.wso2.carbon.device.mgt.common.exception.DeviceManagementException;

import java.util.List;
import java.util.Optional;

/**
 * This interface provides device types
 */
public interface DeviceTypeManager {
    /**
     * Get list of device types.
     * @return list of device types
     * @throws DeviceManagementException
     */
    Optional<List<DeviceType>> getDeviceTypes() throws DeviceManagementException;

    DeviceType addDeviceType(DeviceType type) throws DeviceManagementException;

    DeviceType updateDeviceType(DeviceType map) throws DeviceManagementException;
}
