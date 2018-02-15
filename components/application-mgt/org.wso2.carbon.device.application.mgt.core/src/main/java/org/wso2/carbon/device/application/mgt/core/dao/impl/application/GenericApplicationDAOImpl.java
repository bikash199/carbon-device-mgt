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
package org.wso2.carbon.device.application.mgt.core.dao.impl.application;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.JSONException;
import org.wso2.carbon.device.application.mgt.common.Application;
import org.wso2.carbon.device.application.mgt.common.ApplicationList;
import org.wso2.carbon.device.application.mgt.common.ApplicationRelease;
import org.wso2.carbon.device.application.mgt.common.Filter;
import org.wso2.carbon.device.application.mgt.common.Pagination;
import org.wso2.carbon.device.application.mgt.common.Tag;
import org.wso2.carbon.device.application.mgt.common.UnrestrictedRole;
import org.wso2.carbon.device.application.mgt.common.exception.ApplicationManagementException;
import org.wso2.carbon.device.application.mgt.common.exception.DBConnectionException;
import org.wso2.carbon.device.application.mgt.core.dao.ApplicationDAO;
import org.wso2.carbon.device.application.mgt.core.dao.common.Util;
import org.wso2.carbon.device.application.mgt.core.dao.impl.AbstractDAOImpl;
import org.wso2.carbon.device.application.mgt.core.exception.ApplicationManagementDAOException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * This handles ApplicationDAO related operations.
 */
public class GenericApplicationDAOImpl extends AbstractDAOImpl implements ApplicationDAO {

    private static final Log log = LogFactory.getLog(GenericApplicationDAOImpl.class);

    @Override
    public int createApplication(Application application, int deviceId) throws ApplicationManagementDAOException {
        if (log.isDebugEnabled()) {
            log.debug("Request received in DAO Layer to create an application");
            log.debug("Application Details : ");
            log.debug("App Name : " + application.getName() + " App Type : "
                    + application.getType() + " User Name : " + application.getUser().getUserName());
        }
        Connection conn;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        int index = 0;
        int applicationId = -1;
        try {
            conn = this.getDBConnection();
            stmt = conn.prepareStatement("INSERT INTO AP_APP (NAME, TYPE, APP_CATEGORY, "
                    + "IS_FREE, PAYMENT_CURRENCY, RESTRICTED, TENANT_ID) VALUES "
                    + "(?, ?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            stmt.setString(++index, application.getName());
            stmt.setString(++index, application.getType());
            stmt.setString(++index, application.getAppCategory());
            stmt.setInt(++index, application.getIsFree());
            stmt.setString(++index, application.getPaymentCurrency());
            stmt.setInt(++index, application.getIsRestricted());
            stmt.setInt(++index, application.getUser().getTenantId());
            stmt.executeUpdate();

            rs = stmt.getGeneratedKeys();
            if (rs.next()) {
                applicationId = rs.getInt(1);
            }
            return applicationId;

        } catch (DBConnectionException e) {
            throw new ApplicationManagementDAOException("Error occurred while obtaining the DB connection when application creation", e);
        } catch (SQLException e) {
            throw new ApplicationManagementDAOException("Error occurred while adding the application", e);
        } finally {
            Util.cleanupResources(stmt, rs);
        }
    }

    @Override
    public void addTags(List<Tag> tags, int applicationId, int tenantId) throws ApplicationManagementDAOException {
        if (log.isDebugEnabled()) {
            log.debug("Request received in DAO Layer to add tags");
        }
        Connection conn;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        int index = 0;
        String sql = "INSERT INTO AP_APP_TAG (TAG, TENANT_ID, AP_APP_ID) VALUES (?, ?, ?)";
        try{
            conn = this.getDBConnection();
            conn.setAutoCommit(false);
            stmt = conn.prepareStatement(sql);
            for (Tag tag : tags) {
                stmt.setString(++index, tag.getTagName());
                stmt.setInt(++index, tenantId);
                stmt.setInt(++index, applicationId);
                stmt.addBatch();
            }
            stmt.executeBatch();

        }catch (DBConnectionException e) {
            throw new ApplicationManagementDAOException("Error occurred while obtaining the DB connection when adding tags", e);
        }catch (SQLException e) {
            throw new ApplicationManagementDAOException("Error occurred while adding tags", e);
        } finally {
            Util.cleanupResources(stmt, rs);
        }
    }

    @Override
    public int isExistApplication(String appName, String type, int tenantId) throws ApplicationManagementDAOException {
        if (log.isDebugEnabled()) {
            log.debug("Request received in DAO Layer to verify whether the registering app is registered or not");
        }
        Connection conn;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        int isExist = 0;
        int index = 0;
        String sql = "SELECT * FROM AP_APP WHERE NAME = ? AND TYPE = ? AND TENANT_ID = ?";
        try{
            conn = this.getDBConnection();
            conn.setAutoCommit(false);
            stmt = conn.prepareStatement(sql);
            stmt.setString(++index , appName);
            stmt.setString(++index , type);
            stmt.setInt(++index, tenantId);
            rs = stmt.executeQuery();
            if (rs.next()) {
                isExist = 1;
           }

           return isExist;

        }catch (DBConnectionException e) {
            throw new ApplicationManagementDAOException("Error occurred while obtaining the DB connection when verifying application existence", e);
        }catch (SQLException e) {
            throw new ApplicationManagementDAOException("Error occurred while adding unrestricted roles", e);
        } finally {
            Util.cleanupResources(stmt, rs);
        }
    }

    @Override
    public ApplicationList getApplications(Filter filter, int tenantId) throws ApplicationManagementDAOException {
        if (log.isDebugEnabled()) {
            log.debug("Getting application data from the database");
            log.debug(String.format("Filter: limit=%s, offset=%s", filter.getLimit(), filter.getOffset()));
        }

        Connection conn;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        ApplicationList applicationList = new ApplicationList();
        Pagination pagination = new Pagination();
        int index = 0;
        String sql = "SELECT AP_APP.ID AS APP_ID, AP_APP.NAME AS APP_NAME, AP_APP.TYPE AS APP_TYPE, AP_APP.APP_CATEGORY"
                + " AS APP_CATEGORY, AP_APP.IS_FREE, AP_APP.RESTRICTED, AP_APP_TAG.TAG AS APP_TAG, AP_UNRESTRICTED_ROLES.ROLE "
                + "AS APP_UNRESTRICTED_ROLES FROM ((AP_APP LEFT JOIN AP_APP_TAG ON AP_APP.ID = AP_APP_TAG.AP_APP_ID) "
                + "LEFT JOIN AP_UNRESTRICTED_ROLES ON AP_APP.ID = AP_UNRESTRICTED_ROLES.AP_APP_ID) "
                + "WHERE AP_APP.TENANT_ID =  ?";


        if (filter == null) {
            throw new ApplicationManagementDAOException("Filter need to be instantiated");
        }

        if (filter.getSearchQuery() != null && !filter.getSearchQuery().isEmpty()) {
            sql += " AND LOWER (AP_APP.NAME) ";
            if (filter.isFullMatch()) {
                sql += "= ?";
            } else {
                sql += "LIKE ?";
            }
        }

        sql += " LIMIT ? OFFSET ? ORDER BY DESC APP_ID";

        pagination.setLimit(filter.getLimit());
        pagination.setOffset(filter.getOffset());

        try {
            conn = this.getDBConnection();
            stmt = conn.prepareStatement(sql);
            stmt.setInt(++index, tenantId);

            if (filter.getSearchQuery() != null && !filter.getSearchQuery().isEmpty()) {
                if (filter.isFullMatch()) {
                    stmt.setString(++index, filter.getSearchQuery().toLowerCase());
                } else {
                    stmt.setString(++index, "%" + filter.getSearchQuery().toLowerCase() + "%");
                }
            }

            stmt.setInt(++index, filter.getLimit());
            stmt.setInt(++index, filter.getOffset());
            rs = stmt.executeQuery();
            applicationList.setApplications(Util.loadApplications(rs));
            pagination.setSize(filter.getOffset());
            pagination.setCount(this.getApplicationCount(filter));
            applicationList.setPagination(pagination);

        } catch (SQLException e) {
            throw new ApplicationManagementDAOException("Error occurred while getting application list for the tenant"
                    + " " + tenantId + ". While executing " + sql, e);
        }
        catch (DBConnectionException e) {
            throw new ApplicationManagementDAOException("Error occurred while obtaining the DB connection while "
                    + "getting application list for the tenant " + tenantId, e);
        } catch (JSONException e) {
            throw new ApplicationManagementDAOException("Error occurred while parsing JSON ", e);
        } finally {
            Util.cleanupResources(stmt, rs);
        }
        return applicationList;
    }

    @Override
    public String getUuidOfLatestRelease(int appId) throws ApplicationManagementDAOException {
        if (log.isDebugEnabled()) {
            log.debug("Getting UUID from the latest app release");
        }

        Connection conn;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        String sql = "";
        int index = 0;
        String uuId = null;
        try {
            conn = this.getDBConnection();
            sql += "SELECT APP_RELEASE.UUID AS UUID FROM AP_APP_RELEASE AS APP_RELEASE, AP_APP_LIFECYCLE_STATE "
                    + "AS LIFECYCLE WHERE APP_RELEASE.AP_APP_ID=? AND APP_RELEASE.ID = LIFECYCLE.AP_APP_RELEASE_ID "
                    + "AND LIFECYCLE.CURRENT_STATE = ? order by APP_RELEASE.ID DESC;";

            stmt = conn.prepareStatement(sql);
            stmt.setInt(++index, appId);
            stmt.setString(++index, "PUBLISHED");
            rs = stmt.executeQuery();
            if (rs.next()) {
                uuId = rs.getString("UUID");
            }
            return  uuId;
        } catch (SQLException e) {
            throw new ApplicationManagementDAOException("Error occurred while getting uuid of latest app release", e);
        } catch (DBConnectionException e) {
            throw new ApplicationManagementDAOException("Error occurred while obtaining the DB connection for "
                    + "getting app release id", e);
        } finally {
            Util.cleanupResources(stmt, rs);
        }
    }

    @Override
    public int getApplicationCount(Filter filter) throws ApplicationManagementDAOException {
        if (log.isDebugEnabled()) {
            log.debug("Getting application count from the database");
            log.debug(String.format("Filter: limit=%s, offset=%s", filter.getLimit(), filter.getOffset()));
        }

        Connection conn;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        String sql = "";
        int count = 0;

        if (filter == null) {
            throw new ApplicationManagementDAOException("Filter need to be instantiated");
        }

        try {
            conn = this.getDBConnection();
            sql += "SELECT count(APP.ID) AS APP_COUNT FROM AP_APP AS APP WHERE TENANT_ID = ?";

            if (filter.getSearchQuery() != null && !filter.getSearchQuery().isEmpty()) {
                sql += " AND LOWER (APP.NAME) LIKE ? ";
            }
            sql += ";";

            stmt = conn.prepareStatement(sql);
            int index = 0;
            if (filter.getSearchQuery() != null && !filter.getSearchQuery().isEmpty()) {
                stmt.setString(++index, "%" + filter.getSearchQuery().toLowerCase() + "%");
            }
            rs = stmt.executeQuery();
            if (rs.next()) {
                count = rs.getInt("APP_COUNT");
            }
        } catch (SQLException e) {
            throw new ApplicationManagementDAOException("Error occurred while getting application List", e);
        } catch (DBConnectionException e) {
            throw new ApplicationManagementDAOException("Error occurred while obtaining the DB connection.", e);
        } finally {
            Util.cleanupResources(stmt, rs);
        }
        return count;
    }

    @Override
    public Application getApplication(String appName, String appType, int tenantId) throws
            ApplicationManagementDAOException {
        if (log.isDebugEnabled()){
            log.debug("Getting application with the type(" + appType + " and Name " + appName +
                    " ) from the database");
        }
        Connection conn;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            conn = this.getDBConnection();
            String sql = "SELECT AP_APP.ID AS APP_ID, AP_APP.NAME AS APP_NAME, AP_APP.TYPE AS APP_TYPE, AP_APP.APP_CATEGORY "
                    + "AS APP_CATEGORY, AP_APP.IS_FREE, AP_APP_TAG.TAG, AP_UNRESTRICTED_ROLES.ROLE AS RELESE_ID FROM "
                    + "AP_APP, AP_APP_TAG, AP_UNRESTRICTED_ROLES WHERE AP_APP.NAME=? AND AP_APP.TYPE= ? "
                    + "AND AP_APP.TENANT_ID=?;";

            stmt = conn.prepareStatement(sql);
            stmt.setString(1, appName);
            stmt.setString(2, appType);
            stmt.setInt(3, tenantId);
            rs = stmt.executeQuery();

            if (log.isDebugEnabled()) {
                log.debug("Successfully retrieved basic details of the application with the type "
                        + appType +"and app name "+ appName);
            }

            return Util.loadApplication(rs);

        } catch (SQLException e) {
            throw new ApplicationManagementDAOException(
                    "Error occurred while getting application details with app name " + appName + " While executing query ", e);
        } catch (JSONException e) {
            throw new ApplicationManagementDAOException("Error occurred while parsing JSON", e);
        } catch (DBConnectionException e) {
            throw new ApplicationManagementDAOException("Error occurred while obtaining the DB connection.", e);
        } finally {
            Util.cleanupResources(stmt, rs);
        }
    }

    @Override
    public Application getApplicationById(int applicationId, int tenantId) throws
            ApplicationManagementDAOException {
        if (log.isDebugEnabled()){
            log.debug("Getting application with the id (" + applicationId + ") from the database");
        }
        Connection conn;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            conn = this.getDBConnection();
            String sql = "SELECT AP_APP.ID AS APP_ID, AP_APP.NAME AS APP_NAME, AP_APP.TYPE AS APP_TYPE, AP_APP.APP_CATEGORY \n"
                    + "AS APP_CATEGORY, AP_APP.IS_FREE, AP_APP_TAG.TAG, AP_UNRESTRICTED_ROLES.ROLE AS RELESE_ID FROM \n"
                    + "AP_APP, AP_APP_TAG, AP_UNRESTRICTED_ROLES WHERE AP_APP.ID=? AND AP_APP.TENANT_ID=?;";

            stmt = conn.prepareStatement(sql);
            stmt.setInt(1, applicationId);
            stmt.setInt(2, tenantId);
            rs = stmt.executeQuery();

            if (log.isDebugEnabled()) {
                log.debug("Successfully retrieved basic details of the application with the id "
                        + applicationId);
            }

            return Util.loadApplication(rs);

        } catch (SQLException e) {
            throw new ApplicationManagementDAOException(
                    "Error occurred while getting application details with app id " + applicationId + " While executing query ", e);
        } catch (JSONException e) {
            throw new ApplicationManagementDAOException("Error occurred while parsing JSON", e);
        } catch (DBConnectionException e) {
            throw new ApplicationManagementDAOException("Error occurred while obtaining the DB connection.", e);
        } finally {
            Util.cleanupResources(stmt, rs);
        }
    }

    @Override
    public Boolean verifyApplicationExistenceById(int appId) throws ApplicationManagementDAOException {
        if (log.isDebugEnabled()){
            log.debug("Getting application with the application ID(" + appId + " ) from the database");
        }
        Connection conn;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        Boolean isAppExist = false;
        try {
            conn = this.getDBConnection();
            String sql = "SELECT AP_APP.ID AS APP_ID, AP_APP.NAME AS APP_NAME, AP_APP.TYPE AS APP_TYPE, AP_APP.APP_CATEGORY "
                    + "AS APP_CATEGORY, AP_APP.IS_FREE, AP_APP_TAG.TAG, AP_UNRESTRICTED_ROLES.ROLE AS RELESE_ID FROM "
                    + "AP_APP, AP_APP_TAG, AP_UNRESTRICTED_ROLES WHERE AP_APP.ID=?;";

            stmt = conn.prepareStatement(sql);
            stmt.setInt(1, appId);
            rs = stmt.executeQuery();

            if (log.isDebugEnabled()) {
                log.debug("Successfully retrieved basic details of the application with the application ID " + appId);
            }

            if (rs.next()){
                isAppExist = true;
            }

            return isAppExist;

        } catch (SQLException e) {
            throw new ApplicationManagementDAOException(
                    "Error occurred while getting application details with app ID " + appId + " While executing query ", e);
        }
        catch (DBConnectionException e) {
            throw new ApplicationManagementDAOException("Error occurred while obtaining the DB connection.", e);
        } finally {
            Util.cleanupResources(stmt, rs);
        }
    }

    @Override
    public Application editApplication(Application application, int tenantId) throws ApplicationManagementException {
        Connection conn;
        PreparedStatement stmt = null;
        Application existingApplication = this.getApplication(application.getName(), application.getType(), tenantId);

        if (existingApplication == null){
            throw new ApplicationManagementException("There doesn't have an application for updating");
        }
        try {
            conn = this.getDBConnection();
            int index = 0;
            String sql = "UPDATE AP_APP SET ";


            if (application.getName() != null && !application.getName().equals(existingApplication.getName())) {
                sql += "NAME = ?, ";
            }
            if (application.getType() != null && !application.getType().equals(existingApplication.getType())){
                sql += "TYPE = ?, ";
            }
            if (application.getAppCategory() != null && !application.getAppCategory().equals(existingApplication.getAppCategory())){
                sql += "APP_CATEGORY = ?, ";
            }
            if (application.getIsRestricted() != existingApplication.getIsRestricted()){
                sql += "RESTRICTED = ? ";
            }
            if (application.getIsFree() != existingApplication.getIsFree()){
                sql += "IS_FREE = ? ";
            }

            sql += "WHERE ID = ?";

            stmt = conn.prepareStatement(sql);
            if (application.getName() != null && !application.getName().equals(existingApplication.getName())) {
                stmt.setString(++index, application.getName());
            }
            if (application.getType() != null && !application.getType().equals(existingApplication.getType())){
                stmt.setString(++index, application.getType());
            }
            if (application.getAppCategory() != null && !application.getAppCategory().equals(existingApplication.getAppCategory())){
                stmt.setString(++index, application.getAppCategory());
            }
            if (application.getIsRestricted() != existingApplication.getIsRestricted()){
                stmt.setInt(++index, application.getIsRestricted());
            }
            if (application.getIsFree() != existingApplication.getIsFree()){
                stmt.setInt(++index, application.getIsFree());
            }

            stmt.setInt(++index, application.getId());
            stmt.executeUpdate();
            return application;
        } catch (DBConnectionException e) {
            throw new ApplicationManagementDAOException("Error occurred while obtaining the DB connection.", e);
        } catch (SQLException e) {
            throw new ApplicationManagementDAOException("Error occurred while adding the application", e);
        } finally {
            Util.cleanupResources(stmt, null);
        }
    }

    @Override
    public void deleteApplication(int appId) throws ApplicationManagementDAOException {
        Connection conn;
        PreparedStatement stmt = null;
        try {
            conn = this.getDBConnection();
            String sql = "DELETE FROM AP_APP WHERE ID = ? ";
            stmt = conn.prepareStatement(sql);
            stmt.setInt(1, appId);
            stmt.executeUpdate();

        } catch (DBConnectionException e) {
            throw new ApplicationManagementDAOException("Error occurred while obtaining the DB connection.", e);
        } catch (SQLException e) {
            throw new ApplicationManagementDAOException("Error occurred while deleting the application: " , e);
        } finally {
            Util.cleanupResources(stmt, null);
        }
    }

    @Override
    public void deleteTags(int applicationId) throws ApplicationManagementDAOException {
        Connection conn;
        PreparedStatement stmt = null;
        try {
            conn = this.getDBConnection();
            String sql = "DELETE FROM AP_APP_TAG WHERE ID = ?";
            stmt = conn.prepareStatement(sql);
            stmt.setInt(1, applicationId);
            stmt.executeUpdate();

        } catch (DBConnectionException e) {
            throw new ApplicationManagementDAOException("Error occurred while obtaining the DB connection.", e);
        } catch (SQLException e) {
            throw new ApplicationManagementDAOException(
                    "Error occurred while deleting tags of application: " + applicationId, e);
        } finally {
            Util.cleanupResources(stmt, null);
        }
    }

    @Override
    public Application getApplicationByRelease(String appReleaseUUID, int tenantId)
            throws ApplicationManagementDAOException {
        if (log.isDebugEnabled()){
            log.debug("Getting application with the UUID (" + appReleaseUUID + ") from the database");
        }
        Connection conn;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            conn = this.getDBConnection();
            String sql = "SELECT AP_APP_RELEASE.ID AS RELEASE_ID, AP_APP_RELEASE.VERSION, AP_APP_RELEASE.TENANT_ID,"
                    + "AP_APP_RELEASE.UUID, AP_APP_RELEASE.RELEASE_TYPE, AP_APP_RELEASE.APP_PRICE, "
                    + "AP_APP_RELEASE.STORED_LOCATION, AP_APP_RELEASE.BANNER_LOCATION, AP_APP_RELEASE.SC_1_LOCATION,"
                    + "AP_APP_RELEASE.SC_2_LOCATION, AP_APP_RELEASE.SC_3_LOCATION, AP_APP_RELEASE.APP_HASH_VALUE,"
                    + "AP_APP_RELEASE.SHARED_WITH_ALL_TENANTS, AP_APP_RELEASE.APP_META_INFO, AP_APP_RELEASE.CREATED_BY,"
                    + "AP_APP_RELEASE.CREATED_AT, AP_APP_RELEASE.PUBLISHED_BY, AP_APP_RELEASE.PUBLISHED_AT, "
                    + "AP_APP_RELEASE.STARS,"
                    + "AP_APP.ID AS APP_ID, AP_APP.NAME AS APP_NAME, AP_APP.TYPE AS APP_TYPE, "
                    + "AP_APP.APP_CATEGORY AS APP_CATEGORY, AP_APP.IS_FREE, AP_UNRESTRICTED_ROLES.ROLE AS ROLE "
                    + "FROM AP_APP, AP_UNRESTRICTED_ROLES, AP_APP_RELEASE "
                    + "WHERE AP_APP_RELEASE.UUID=? AND AP_APP.TENANT_ID=?;";

            stmt = conn.prepareStatement(sql);
            stmt.setString(1, appReleaseUUID);
            stmt.setInt(2, tenantId);
            rs = stmt.executeQuery();

            if (log.isDebugEnabled()) {
                log.debug("Successfully retrieved details of the application with the UUID " + appReleaseUUID);
            }

            Application application = null;
            while(rs.next()) {
                ApplicationRelease appRelease = Util.readApplicationRelease(rs);
                application = new Application();

                application.setId(rs.getInt("APP_ID"));
                application.setName(rs.getString("APP_NAME"));
                application.setType(rs.getString("APP_TYPE"));
                application.setAppCategory(rs.getString("APP_CATEGORY"));
                application.setIsFree(rs.getInt("IS_FREE"));
                application.setIsRestricted(rs.getInt("RESTRICTED"));

                UnrestrictedRole unrestrictedRole = new UnrestrictedRole();
                unrestrictedRole.setRole(rs.getString("ROLE"));
                List<UnrestrictedRole> unrestrictedRoleList = new ArrayList<>();
                unrestrictedRoleList.add(unrestrictedRole);

                application.setUnrestrictedRoles(unrestrictedRoleList);

                List<ApplicationRelease> applicationReleaseList = new ArrayList<>();
                applicationReleaseList.add(appRelease);

                application.setApplicationReleases(applicationReleaseList);
            }
            return application;
        } catch (SQLException e) {
            throw new ApplicationManagementDAOException("Error occurred while getting application details with UUID "
                    + appReleaseUUID + " While executing query ", e);
        } catch (JSONException e) {
            throw new ApplicationManagementDAOException("Error occurred while parsing JSON", e);
        } catch (DBConnectionException e) {
            throw new ApplicationManagementDAOException("Error occurred while obtaining the DB connection.", e);
        } finally {
            Util.cleanupResources(stmt, rs);
        }
    }

    @Override
    public int getApplicationId(String appName, String appType, int tenantId) throws ApplicationManagementDAOException {
        Connection conn;
        PreparedStatement stmt = null;
        ResultSet rs = null;
        String sql;
        int id = -1;
        try {
            conn = this.getDBConnection();
            sql = "SELECT ID FROM AP_APP WHERE NAME = ? AND TYPE = ? AND TENANT_ID = ?";
            stmt = conn.prepareStatement(sql);
            stmt.setString(1, appName);
            stmt.setString(2, appType);
            stmt.setInt(3, tenantId);
            rs = stmt.executeQuery();
            if (rs.next()) {
                id = rs.getInt(1);
            }
        } catch (DBConnectionException e) {
            throw new ApplicationManagementDAOException("Error occurred while obtaining the DB connection.", e);
        } catch (SQLException e) {
            throw new ApplicationManagementDAOException("Error occurred while getting application List", e);
        } finally {
            Util.cleanupResources(stmt, rs);
        }
        return id;
    }
}
