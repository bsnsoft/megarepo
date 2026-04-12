package de.bsnsoft.megarepo.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "anonymous_access_settings")
public class AnonymousAccessSettingsEntity {

    @Id
    @Column(name = "id")
    private Integer id = 1;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "user_id", nullable = false, length = 200)
    private String userId = "anonymous";

    @Column(name = "realm_name", nullable = false, length = 200)
    private String realmName = "NexusAuthorizingRealm";

    public AnonymousAccessSettingsEntity() {
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getRealmName() {
        return realmName;
    }

    public void setRealmName(String realmName) {
        this.realmName = realmName;
    }
}
