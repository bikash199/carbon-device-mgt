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
package org.wso2.carbon.device.application.mgt.core.impl;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.CarbonConstants;
import org.wso2.carbon.context.CarbonContext;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.device.application.mgt.common.*;
import org.wso2.carbon.device.application.mgt.common.exception.ApplicationManagementException;
import org.wso2.carbon.device.application.mgt.common.services.ApplicationManager;
import org.wso2.carbon.device.application.mgt.core.dao.ApplicationDAO;
import org.wso2.carbon.device.application.mgt.core.dao.LifecycleStateDAO;
import org.wso2.carbon.device.application.mgt.core.dao.VisibilityDAO;
import org.wso2.carbon.device.application.mgt.core.dao.common.ApplicationManagementDAOFactory;
import org.wso2.carbon.device.application.mgt.core.exception.NotFoundException;
import org.wso2.carbon.device.application.mgt.core.exception.ValidationException;
import org.wso2.carbon.device.application.mgt.core.internal.DataHolder;
import org.wso2.carbon.device.application.mgt.core.util.ConnectionManagerUtil;
import org.wso2.carbon.device.mgt.core.dao.*;
import org.wso2.carbon.device.mgt.core.dto.DeviceType;
import org.wso2.carbon.user.api.UserRealm;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import java.util.ArrayList;
import java.util.List;
import java.sql.Timestamp;
import java.util.Date;

/**
 * Default Concrete implementation of Application Management related implementations.
 */
public class ApplicationManagerImpl implements ApplicationManager {

    private static final Log log = LogFactory.getLog(ApplicationManagerImpl.class);
    private DeviceTypeDAO deviceTypeDAO;
    private VisibilityDAO visibilityDAO;
    private LifecycleStateDAO lifecycleStateDAO;
    private ApplicationDAO applicationDAO;

    public ApplicationManagerImpl() {
        initDataAccessObjects();
    }

    private void initDataAccessObjects() {
        this.deviceTypeDAO = ApplicationManagementDAOFactory.getDeviceTypeDAO();
        this.visibilityDAO = ApplicationManagementDAOFactory.getVisibilityDAO();
        this.lifecycleStateDAO = ApplicationManagementDAOFactory.getLifecycleStateDAO();
        this.applicationDAO = ApplicationManagementDAOFactory.getApplicationDAO();
    }

    @Override public Application createApplication(Application application) throws ApplicationManagementException {

        User loggedInUser = new User(PrivilegedCarbonContext.getThreadLocalCarbonContext().getUsername(),
                PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId(true));
        application.setUser(loggedInUser);
        if (log.isDebugEnabled()) {
            log.debug("Create Application received for the tenant : " + application.getUser().getTenantId() + " From"
                    + " the user : " + application.getUser().getUserName());
        }

        validateAppCreatingRequest(application);
        validateReleaseCreateRequest(application.getApplicationReleases());
        DeviceType deviceType;
        ApplicationRelease applicationRelease;
        try {
            ConnectionManagerUtil.beginDBTransaction();
            int tenantId = application.getUser().getTenantId();
            deviceType = this.deviceTypeDAO.getDeviceType(application.getType(), application.getUser().getTenantId());

            if (deviceType == null) {
                log.error("Device type is not matched with application type");
                return null;
            }
            application.setDevicetype(deviceType);
            int appId = this.applicationDAO.createApplication(application, deviceType.getId());

            if (appId != -1) {
                log.error("Application creation Failed");
                ConnectionManagerUtil.rollbackDBTransaction();
            } else {
                if (!application.getTags().isEmpty()) {
                    this.applicationDAO.addTags(application.getTags(), appId, tenantId);
                }
                if (application.getIsRestricted() == 1 && !application.getUnrestrictedRoles().isEmpty()) {
                    this.visibilityDAO.addUnrestrictedRoles(application.getUnrestrictedRoles(), appId, tenantId);
                } else {
                    application.setIsRestricted(0);
                }
                ConnectionManagerUtil.commitDBTransaction();
                applicationRelease = application.getApplicationReleases().get(0);
                applicationRelease.setCreatedAt((Timestamp) new Date());
                applicationRelease = ApplicationManagementDAOFactory.getApplicationReleaseDAO().
                        createRelease(applicationRelease, application.getId());
                //todo add lifecycle and add this into application
            }

            return application;

        } catch (DeviceManagementDAOException e) {
            String msg = "Error occurred while getting device type id of " + application.getType();
            log.error(msg, e);
            ConnectionManagerUtil.rollbackDBTransaction();
            throw new ApplicationManagementException(msg, e);
        } catch (ApplicationManagementException e) {
            String msg = "Error occurred while adding application";
            log.error(msg, e);
            ConnectionManagerUtil.rollbackDBTransaction();
            throw new ApplicationManagementException(msg, e);
        } finally {
            ConnectionManagerUtil.closeDBConnection();
        }
    }

    @Override public ApplicationList getApplications(Filter filter) throws ApplicationManagementException {
        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId(true);
        String userName = PrivilegedCarbonContext.getThreadLocalCarbonContext().getUsername();
        ApplicationList applicationList;
        List<ApplicationRelease> applicationReleases;

        try {
            filter.setUserName(userName);
            ConnectionManagerUtil.openDBConnection();
            applicationList = applicationDAO.getApplications(filter, tenantId);
            if (!isAdminUser(userName, tenantId, CarbonConstants.UI_ADMIN_PERMISSION_COLLECTION)) {
                applicationList = getRoleRestrictedApplicationList(applicationList, userName);
            }

            for (Application application : applicationList.getApplications()) {
                applicationReleases = getReleases(application.getId());
                application.setApplicationReleases(applicationReleases);
            }

            return applicationList;
        } catch (UserStoreException e) {
            throw new ApplicationManagementException(
                    "User-store exception while checking whether the user " + userName + " of tenant " + tenantId
                            + " has the publisher permission");
        } finally {
            ConnectionManagerUtil.closeDBConnection();
        }
    }

    @Override public String getUuidOfLatestRelease(int appId) throws ApplicationManagementException {
        try {
            ConnectionManagerUtil.openDBConnection();
            return applicationDAO.getUuidOfLatestRelease(appId);
        } finally {
            ConnectionManagerUtil.closeDBConnection();
        }

    }

    private boolean isRoleExists(List<UnrestrictedRole> unrestrictedRoleList, String userName)
            throws UserStoreException {
        String[] roleList;
        roleList = getRoleOfUser(userName);
        for (UnrestrictedRole unrestrictedRole : unrestrictedRoleList) {
            for (String role : roleList) {
                if (unrestrictedRole.getRole().equals(role)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String[] getRoleOfUser(String userName) throws UserStoreException {
        UserRealm userRealm = CarbonContext.getThreadLocalCarbonContext().getUserRealm();
        String[] roleList = {};
        if (userRealm != null) {
            roleList = userRealm.getUserStoreManager().getRoleListOfUser(userName);
        } else {
            log.error("role list is empty of user :" + userName);
        }
        return roleList;
    }

    @Override public Application getApplication(String appType, String appName) throws ApplicationManagementException {
        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId(true);
        String userName = PrivilegedCarbonContext.getThreadLocalCarbonContext().getUsername();
        Application application;
        boolean isAppAllowed = false;
        List<ApplicationRelease> applicationReleases;
        try {
            ConnectionManagerUtil.openDBConnection();
            application = ApplicationManagementDAOFactory.getApplicationDAO()
                    .getApplication(appType, appName, tenantId);
            if (isAdminUser(userName, tenantId, CarbonConstants.UI_ADMIN_PERMISSION_COLLECTION)) {
                return application;
            }

            if (!application.getUnrestrictedRoles().isEmpty()) {
                if (isRoleExists(application.getUnrestrictedRoles(), userName)) {
                    isAppAllowed = true;
                }
            } else {
                isAppAllowed = true;
            }

            if (!isAppAllowed) {
                return null;
            }

            applicationReleases = getReleases(application.getId());
            application.setApplicationReleases(applicationReleases);
            return application;
        } catch (UserStoreException e) {
            throw new ApplicationManagementException(
                    "User-store exception while getting application with the " + "application name " + appName);
        } finally {
            ConnectionManagerUtil.closeDBConnection();
        }
    }

    @Override public Application getApplicationById(int applicationId) throws ApplicationManagementException {
        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId(true);
        String userName = PrivilegedCarbonContext.getThreadLocalCarbonContext().getUsername();
        Application application;
        boolean isAppAllowed = false;
        try {
            ConnectionManagerUtil.openDBConnection();
            application = ApplicationManagementDAOFactory.getApplicationDAO()
                    .getApplicationById(applicationId, tenantId);
            if (isAdminUser(userName, tenantId, CarbonConstants.UI_ADMIN_PERMISSION_COLLECTION)) {
                return application;
            }

            if (!application.getUnrestrictedRoles().isEmpty()) {
                if (isRoleExists(application.getUnrestrictedRoles(), userName)) {
                    isAppAllowed = true;
                }
            } else {
                isAppAllowed = true;
            }

            if (!isAppAllowed) {
                return null;
            }
            return application;
        } catch (UserStoreException e) {
            throw new ApplicationManagementException(
                    "User-store exception while getting application with the " + "application id " + applicationId);
        } finally {
            ConnectionManagerUtil.closeDBConnection();
        }
    }

    public Boolean verifyApplicationExistenceById(int appId) throws ApplicationManagementException {
        try {
            Boolean isAppExist;
            ConnectionManagerUtil.openDBConnection();
            isAppExist = ApplicationManagementDAOFactory.getApplicationDAO().verifyApplicationExistenceById(appId);
            return isAppExist;
        } finally {
            ConnectionManagerUtil.closeDBConnection();
        }
    }

    public Boolean isUserAllowable(List<UnrestrictedRole> unrestrictedRoles, String userName)
            throws ApplicationManagementException {
        try {
            return isRoleExists(unrestrictedRoles, userName);
        } catch (UserStoreException e) {
            throw new ApplicationManagementException(
                    "User-store exception while verifying whether user have assigned" + "unrestricted roles or not");
        }
    }

    @Override public List<ApplicationRelease> getReleases(int applicationId) throws ApplicationManagementException {
        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId(true);

        Application application = validateApplication(applicationId);
        List<ApplicationRelease> applicationReleases;
        List<ApplicationRelease> filteredApplicationReleases = new ArrayList<>();
        if (log.isDebugEnabled()) {
            log.debug("Request is received to retrieve all the releases related with the application " + application
                    .toString());
        }
        try {
            ConnectionManagerUtil.openDBConnection();
            applicationReleases = ApplicationManagementDAOFactory.getApplicationReleaseDAO()
                    .getApplicationReleases(application.getName(), application.getType(), tenantId);
            for (ApplicationRelease applicationRelease : applicationReleases) {
                if (!"REMOVED".equals(ApplicationManagementDAOFactory.getLifecycleStateDAO().
                        getLatestLifeCycleStateByReleaseID(applicationRelease.getId()).getCurrentState())) {
                    filteredApplicationReleases.add(applicationRelease);
                }
            }
            return filteredApplicationReleases;
        } finally {
            ConnectionManagerUtil.closeDBConnection();
        }
    }

    /**
     * To check whether current user is application owner or admin.
     *
     * @param applicationUUID UUID of the Application.
     * @return true if the current user is application owner or admin, unless false.
     * @throws ApplicationManagementException Application Management Exception.
     */
    private boolean isApplicationOwnerOrAdmin(String applicationUUID, String userName, int tenantId)
            throws ApplicationManagementException {
        //        try {
        //            if (isAdminUser(userName, tenantId, CarbonConstants.UI_ADMIN_PERMISSION_COLLECTION)) {
        //                return true;
        //            }
        //        } catch (UserStoreException e) {
        //            throw new ApplicationManagementException("Userstore exception while checking whether user is an admin", e);
        //        }
        //        try {
        //            ConnectionManagerUtil.openDBConnection();
        //            Application application = ApplicationManagementDAOFactory.getApplicationDAO()
        //                    .getApplication(applicationUUID, tenantId, userName);
        //            return application.getUser().getUserName().equals(userName)
        //                    && application.getUser().getTenantId() == tenantId;
        //        } finally {
        //            ConnectionManagerUtil.closeDBConnection();
        //        }
        return false;
    }

    /**
     * To check whether current user has the permission to do some secured operation.
     *
     * @param username   Name of the User.
     * @param tenantId   ID of the tenant.
     * @param permission Permission that need to be checked.
     * @return true if the current user has the permission, otherwise false.
     * @throws UserStoreException UserStoreException
     */
    private boolean isAdminUser(String username, int tenantId, String permission) throws UserStoreException {
        UserRealm userRealm = DataHolder.getInstance().getRealmService().getTenantUserRealm(tenantId);
        return userRealm != null && userRealm.getAuthorizationManager() != null && userRealm.getAuthorizationManager()
                .isUserAuthorized(MultitenantUtils.getTenantAwareUsername(username), permission,
                        CarbonConstants.UI_PERMISSION_ACTION);
    }

    /**
     * To validate the application
     *
     * @param application Application that need to be created
     * @throws ValidationException Validation Exception
     */
    private void validateAppCreatingRequest(Application application) throws ValidationException {

        if (application.getName() == null) {
            throw new ValidationException("Application name cannot be empty");
        }
        if (application.getUser() == null || application.getUser().getUserName() == null
                || application.getUser().getTenantId() == 0) {
            throw new ValidationException("Username and tenant Id cannot be empty");
        }
        if (application.getAppCategory() == null) {
            throw new ValidationException("Username and tenant Id cannot be empty");
        }
        try {
            validateApplicationExistence(application);
        } catch (ApplicationManagementException e) {
            throw new ValidationException("Error occured while validating whether there is already an application "
                    + "registered with same name.", e);
        }
    }

    private void validateApplicationExistence(Application application) throws ApplicationManagementException {
        Filter filter = new Filter();
        filter.setFullMatch(true);
        filter.setSearchQuery(application.getName().trim());
        filter.setOffset(0);
        filter.setLimit(1);

        ApplicationList applicationList = getApplications(filter);
        if (applicationList != null && applicationList.getApplications() != null && !applicationList.getApplications()
                .isEmpty()) {
            throw new ValidationException(
                    "Already an application registered with same name - " + applicationList.getApplications().get(0)
                            .getName());
        }
    }

    /**
     * To validate the pre-request of the ApplicationRelease.
     *
     * @param applicationID ID of the Application.
     * @return Application related with the UUID
     */
    private Application validateApplication(int applicationID) throws ApplicationManagementException {
        if (applicationID <= 0) {
            throw new ApplicationManagementException("Application UUID is null. Application UUID is a required "
                    + "parameter to get the relevant application.");
        }
        Application application = DataHolder.getInstance().getApplicationManager().getApplicationById(applicationID);
        if (application == null) {
            throw new NotFoundException("Application of the " + applicationID + " does not exist.");
        }
        return application;
    }

    /**
     * To get role restricted application list.
     *
     * @param applicationList list of applications.
     * @param userName        user name
     * @return Application related with the UUID
     */
    private ApplicationList getRoleRestrictedApplicationList(ApplicationList applicationList, String userName)
            throws ApplicationManagementException {
        ApplicationList roleRestrictedApplicationList = new ApplicationList();
        ArrayList<Application> unRestrictedApplications = new ArrayList<>();
        for (Application application : applicationList.getApplications()) {
            if (application.getUnrestrictedRoles().isEmpty()) {
                unRestrictedApplications.add(application);
            } else {
                try {
                    if (isRoleExists(application.getUnrestrictedRoles(), userName)) {
                        unRestrictedApplications.add(application);
                    }
                } catch (UserStoreException e) {
                    throw new ApplicationManagementException("Role restriction verifying is failed");
                }
            }
        }
        roleRestrictedApplicationList.setApplications(unRestrictedApplications);
        return roleRestrictedApplicationList;
    }

    /**
     * To validate a create release request to make sure all the pre-conditions satisfied.
     *
     * @param applicationReleases ApplicationRelease that need to be created.
     * @throws ApplicationManagementException Application Management Exception.
     */
    private void validateReleaseCreateRequest(List<ApplicationRelease> applicationReleases)
            throws ApplicationManagementException {

        if (applicationReleases.isEmpty() || applicationReleases.size() > 1) {
            throw new ApplicationManagementException("ApplicationRelease size is grater than minimal release size or "
                    + "request doesn't contains application release");
        }
        if (applicationReleases.get(0).getVersion() == null) {
            throw new ApplicationManagementException("ApplicationRelease version name is a mandatory parameter for "
                    + "creating release. It cannot be found.");
        }
        //todo
        //        if (getRelease(applicationReleases.get(0).getUuid(), applicationReleases.get(0).getVersion(),
        //                applicationReleases.get(0).getReleaseType()) != null) {
        //            throw new ApplicationManagementException( "Application Release for the Application UUID " +
        //                    applicationReleases.get(0).getUuid() + " " + "with the version "
        //                    + applicationReleases.get(0).getVersion() + " already exists. Cannot create an " +
        //                    "application release with the same version.");
        //        }
    }

    @Override public Application editApplication(Application application) throws ApplicationManagementException {
        //        String userName = PrivilegedCarbonContext.getThreadLocalCarbonContext().getUsername();
        //        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId(true);
        //        if (application.getUuid() == null) {
        //            throw new ValidationException("Application UUID cannot be empty");
        //        }
        //
        //        if (!isApplicationOwnerOrAdmin(application.getUuid(), userName, tenantId)) {
        //            throw new ApplicationManagementException(
        //                    "User " + userName + " does not have permissions to edit the " + "application with the UUID "
        //                            + application.getUuid());
        //        }
        //        if (this.getApplication(application.getUuid()) != null) {
        //            if (application.getPlatform() == null || application.getPlatform().getIdentifier() == null) {
        //                throw new NotFoundException("Platform information not available with the application!");
        //            }
        //            Platform platform = DataHolder.getInstance().getPlatformManager()
        //                    .getPlatform(tenantId, application.getPlatform().getIdentifier());
        //            if (platform == null) {
        //                throw new NotFoundException(
        //                        "Platform specified by identifier " + application.getPlatform().getIdentifier()
        //                                + " is not found. Please give a valid platform identifier.");
        //            }
        //            application.setPlatform(platform);
        //            if (application.getCategory() != null) {
        //                String applicationCategoryName = application.getCategory().getName();
        //                if (applicationCategoryName == null || applicationCategoryName.isEmpty()) {
        //                    throw new ApplicationManagementException(
        //                            "Application category name cannot be null or " + "empty. Cannot edit the application.");
        //                }
        //                Category category = DataHolder.getInstance().getCategoryManager()
        //                        .getCategory(application.getCategory().getName());
        //                if (category == null) {
        //                    throw new NotFoundException(
        //                            "Invalid Category is provided for the application " + application.getUuid() + ". "
        //                                    + "Cannot edit application");
        //                }
        //                application.setCategory(category);
        //            }
        //            try {
        //                ConnectionManagerUtil.beginDBTransaction();
        //                ApplicationDAO applicationDAO = ApplicationManagementDAOFactory.getApplicationDAO();
        //                application.setModifiedAt(new Date());
        //                Application modifiedApplication = applicationDAO.editApplication(application, tenantId);
        //                Visibility visibility = DataHolder.getInstance().getVisibilityManager()
        //                        .put(application.getId(), application.getVisibility());
        //                modifiedApplication.setVisibility(visibility);
        //                ConnectionManagerUtil.commitDBTransaction();
        //                return modifiedApplication;
        //            } catch (ApplicationManagementDAOException e) {
        //                ConnectionManagerUtil.rollbackDBTransaction();
        //                throw e;
        //            } finally {
        //                ConnectionManagerUtil.closeDBConnection();
        //            }
        //        } else {
        //            throw new NotFoundException("No applications found with application UUID - " + application.getUuid());
        //        }
        return application;
    }

    @Override public void deleteApplication(String uuid) throws ApplicationManagementException {
        //        String userName = PrivilegedCarbonContext.getThreadLocalCarbonContext().getUsername();
        //        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId(true);
        //        if (!isApplicationOwnerOrAdmin(uuid, userName, tenantId)) {
        //            throw new ApplicationManagementException("User '" + userName + "' of tenant - " + tenantId + " does have"
        //                    + " the permission to delete the application with UUID " + uuid);
        //        }
        //        try {
        //            ApplicationDAO applicationDAO = ApplicationManagementDAOFactory.getApplicationDAO();
        //            ConnectionManagerUtil.beginDBTransaction();
        //            int appId = applicationDAO.getApplicationId(uuid, tenantId);
        //            if (appId != -1) {
        //                applicationDAO.deleteTags(appId);
        //                applicationDAO.deleteProperties(appId);
        //                DataHolder.getInstance().getVisibilityManager().remove(appId);
        //                applicationDAO.deleteApplication(uuid, tenantId);
        //            }
        //            ConnectionManagerUtil.commitDBTransaction();
        //        } catch (ApplicationManagementDAOException e) {
        //            ConnectionManagerUtil.rollbackDBTransaction();
        //            String msg = "Failed to delete application: " + uuid;
        //            throw new ApplicationManagementException(msg, e);
        //        } finally {
        //            ConnectionManagerUtil.closeDBConnection();
        //        }
    }

    @Override public void changeLifecycle(String applicationUuid, String lifecycleIdentifier)
            throws ApplicationManagementException {
        //        boolean isAvailableNextState = false;
        //        String userName = PrivilegedCarbonContext.getThreadLocalCarbonContext().getUsername();
        //        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId(true);
        //        List<LifecycleStateTransition> nextLifeCycles = getLifeCycleStates(applicationUuid);
        //
        //        for (LifecycleStateTransition lifecycleStateTransition : nextLifeCycles) {
        //            if (log.isDebugEnabled()) {
        //                log.debug("Lifecycle state of the application " + applicationUuid + " can be changed to"
        //                        + lifecycleStateTransition.getNextState());
        //            }
        //            if (lifecycleStateTransition.getNextState().equalsIgnoreCase(lifecycleIdentifier)) {
        //                isAvailableNextState = true;
        //                break;
        //            }
        //        }
        //        if (!isAvailableNextState) {
        //            throw new ApplicationManagementException("User " + userName + " does not have the permission to change "
        //                    + "the lifecycle state of the application " + applicationUuid + " to lifecycle state "
        //                    + lifecycleIdentifier);
        //        }
        //        try {
        //            ConnectionManagerUtil.beginDBTransaction();
        //            ApplicationDAO applicationDAO = ApplicationManagementDAOFactory.getApplicationDAO();
        //            applicationDAO.changeLifecycle(applicationUuid, lifecycleIdentifier, userName, tenantId);
        //            ConnectionManagerUtil.commitDBTransaction();
        //        } catch (ApplicationManagementDAOException e) {
        //            ConnectionManagerUtil.rollbackDBTransaction();
        //            throw e;
        //        } finally {
        //            ConnectionManagerUtil.closeDBConnection();
        //        }
    }

    @Override public List<LifecycleStateTransition> getLifeCycleStates(String applicationUUID)
            throws ApplicationManagementException {
        //        int tenantId = PrivilegedCarbonContext.getThreadLocalCarbonContext().getTenantId(true);
        //        String userName = PrivilegedCarbonContext.getThreadLocalCarbonContext().getUsername();
        //        boolean isAdminOrApplicationOwner = isApplicationOwnerOrAdmin(applicationUUID, userName, tenantId);
        //
        //        if (log.isDebugEnabled()) {
        //            log.debug("User " + userName + " in tenant " + tenantId + " is an Admin or Application owner of the "
        //                    + "application " + applicationUUID);
        //        }
        //        try {
        //            ConnectionManagerUtil.openDBConnection();
        //            List<LifecycleStateTransition> transitions = ApplicationManagementDAOFactory.getApplicationDAO()
        //                    .getNextLifeCycleStates(applicationUUID, tenantId);
        //            List<LifecycleStateTransition> filteredTransitions = new ArrayList<>();
        //
        //            if (log.isDebugEnabled()) {
        //                log.debug("Lifecycle of the application with UUID : " + applicationUUID + " can be changed to "
        //                        + transitions.size() + ". The number may vary according to the permission level of user : "
        //                        + userName + " of tenant " + tenantId);
        //            }
        //            for (LifecycleStateTransition transition : transitions) {
        //                String permission = transition.getPermission();
        //                if (permission != null) {
        //                    if (log.isDebugEnabled()) {
        //                        log.debug("In order to make the state change to " + transition.getNextState() + " permission "
        //                                + permission + "  is required");
        //                    }
        //                    if (isAdminUser(userName, tenantId, permission)) {
        //                        filteredTransitions.add(transition);
        //                    } else {
        //                        if (log.isDebugEnabled()) {
        //                            log.debug("User " + userName + " does not have the permission " + permission + " to "
        //                                    + "change the life-cycle state to " + transition.getNextState() + "  of the "
        //                                    + "application " + applicationUUID);
        //                        }
        //                    }
        //                } else if (isAdminOrApplicationOwner) {
        //                    filteredTransitions.add(transition);
        //                }
        //            }
        //            if (log.isDebugEnabled()) {
        //                log.debug("User " + userName + " can do " + filteredTransitions.size() + " life-cyle state changes "
        //                        + "currently on application with the UUID " + applicationUUID);
        //            }
        //            return filteredTransitions;
        //        } catch (UserStoreException e) {
        //            throw new ApplicationManagementException(
        //                    "Userstore exception while checking whether user " + userName + " from tenant " + tenantId
        //                            + " is authorized to do a life-cycle status change in an application ", e);
        //        } finally {
        //            ConnectionManagerUtil.closeDBConnection();
        //        }
        return null;
    }

}