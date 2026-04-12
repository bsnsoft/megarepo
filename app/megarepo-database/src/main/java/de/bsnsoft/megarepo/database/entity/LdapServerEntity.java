package de.bsnsoft.megarepo.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ldap_servers")
public class LdapServerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id")
    private UUID id;

    @Column(name = "name", nullable = false, unique = true, length = 200)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "protocol", nullable = false, length = 10)
    private String protocol = "ldap";

    @Column(name = "hostname", nullable = false, length = 500)
    private String hostname;

    @Column(name = "port", nullable = false)
    private int port = 389;

    @Column(name = "search_base", nullable = false, length = 500)
    private String searchBase;

    @Column(name = "auth_scheme", nullable = false, length = 50)
    private String authScheme = "simple";

    @Column(name = "auth_username", length = 500)
    private String authUsername;

    @Column(name = "auth_password", length = 500)
    private String authPassword;

    @Column(name = "connection_timeout", nullable = false)
    private int connectionTimeout = 30;

    @Column(name = "retry_delay", nullable = false)
    private int retryDelay = 300;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries = 3;

    @Column(name = "user_base_dn", length = 500)
    private String userBaseDn;

    @Column(name = "user_subtree", nullable = false)
    private boolean userSubtree = true;

    @Column(name = "user_object_class", nullable = false, length = 100)
    private String userObjectClass = "inetOrgPerson";

    @Column(name = "user_id_attribute", nullable = false, length = 100)
    private String userIdAttribute = "uid";

    @Column(name = "user_name_attribute", nullable = false, length = 100)
    private String userNameAttribute = "cn";

    @Column(name = "user_email_attribute", nullable = false, length = 100)
    private String userEmailAttribute = "mail";

    @Column(name = "ldap_groups_as_roles", nullable = false)
    private boolean ldapGroupsAsRoles = true;

    @Column(name = "group_type", nullable = false, length = 50)
    private String groupType = "dynamic";

    @Column(name = "group_base_dn", length = 500)
    private String groupBaseDn;

    @Column(name = "group_subtree", nullable = false)
    private boolean groupSubtree = true;

    @Column(name = "group_object_class", length = 100)
    private String groupObjectClass = "groupOfNames";

    @Column(name = "group_id_attribute", length = 100)
    private String groupIdAttribute = "cn";

    @Column(name = "group_member_attribute", length = 100)
    private String groupMemberAttribute = "member";

    @Column(name = "group_member_format", length = 200)
    private String groupMemberFormat = "uid=${username},${dn}";

    @Column(name = "user_member_of_attribute", length = 100)
    private String userMemberOfAttribute = "memberOf";

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public LdapServerEntity() {}

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getSearchBase() {
        return searchBase;
    }

    public void setSearchBase(String searchBase) {
        this.searchBase = searchBase;
    }

    public String getAuthScheme() {
        return authScheme;
    }

    public void setAuthScheme(String authScheme) {
        this.authScheme = authScheme;
    }

    public String getAuthUsername() {
        return authUsername;
    }

    public void setAuthUsername(String authUsername) {
        this.authUsername = authUsername;
    }

    public String getAuthPassword() {
        return authPassword;
    }

    public void setAuthPassword(String authPassword) {
        this.authPassword = authPassword;
    }

    public int getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(int connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public int getRetryDelay() {
        return retryDelay;
    }

    public void setRetryDelay(int retryDelay) {
        this.retryDelay = retryDelay;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public String getUserBaseDn() {
        return userBaseDn;
    }

    public void setUserBaseDn(String userBaseDn) {
        this.userBaseDn = userBaseDn;
    }

    public boolean isUserSubtree() {
        return userSubtree;
    }

    public void setUserSubtree(boolean userSubtree) {
        this.userSubtree = userSubtree;
    }

    public String getUserObjectClass() {
        return userObjectClass;
    }

    public void setUserObjectClass(String userObjectClass) {
        this.userObjectClass = userObjectClass;
    }

    public String getUserIdAttribute() {
        return userIdAttribute;
    }

    public void setUserIdAttribute(String userIdAttribute) {
        this.userIdAttribute = userIdAttribute;
    }

    public String getUserNameAttribute() {
        return userNameAttribute;
    }

    public void setUserNameAttribute(String userNameAttribute) {
        this.userNameAttribute = userNameAttribute;
    }

    public String getUserEmailAttribute() {
        return userEmailAttribute;
    }

    public void setUserEmailAttribute(String userEmailAttribute) {
        this.userEmailAttribute = userEmailAttribute;
    }

    public boolean isLdapGroupsAsRoles() {
        return ldapGroupsAsRoles;
    }

    public void setLdapGroupsAsRoles(boolean ldapGroupsAsRoles) {
        this.ldapGroupsAsRoles = ldapGroupsAsRoles;
    }

    public String getGroupType() {
        return groupType;
    }

    public void setGroupType(String groupType) {
        this.groupType = groupType;
    }

    public String getGroupBaseDn() {
        return groupBaseDn;
    }

    public void setGroupBaseDn(String groupBaseDn) {
        this.groupBaseDn = groupBaseDn;
    }

    public boolean isGroupSubtree() {
        return groupSubtree;
    }

    public void setGroupSubtree(boolean groupSubtree) {
        this.groupSubtree = groupSubtree;
    }

    public String getGroupObjectClass() {
        return groupObjectClass;
    }

    public void setGroupObjectClass(String groupObjectClass) {
        this.groupObjectClass = groupObjectClass;
    }

    public String getGroupIdAttribute() {
        return groupIdAttribute;
    }

    public void setGroupIdAttribute(String groupIdAttribute) {
        this.groupIdAttribute = groupIdAttribute;
    }

    public String getGroupMemberAttribute() {
        return groupMemberAttribute;
    }

    public void setGroupMemberAttribute(String groupMemberAttribute) {
        this.groupMemberAttribute = groupMemberAttribute;
    }

    public String getGroupMemberFormat() {
        return groupMemberFormat;
    }

    public void setGroupMemberFormat(String groupMemberFormat) {
        this.groupMemberFormat = groupMemberFormat;
    }

    public String getUserMemberOfAttribute() {
        return userMemberOfAttribute;
    }

    public void setUserMemberOfAttribute(String userMemberOfAttribute) {
        this.userMemberOfAttribute = userMemberOfAttribute;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
