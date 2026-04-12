package de.bsnsoft.megarepo.database.entity;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class GroupMemberId implements Serializable {

    private UUID groupRepoId;
    private UUID memberRepoId;

    public GroupMemberId() {
    }

    public GroupMemberId(UUID groupRepoId, UUID memberRepoId) {
        this.groupRepoId = groupRepoId;
        this.memberRepoId = memberRepoId;
    }

    public UUID getGroupRepoId() {
        return groupRepoId;
    }

    public void setGroupRepoId(UUID groupRepoId) {
        this.groupRepoId = groupRepoId;
    }

    public UUID getMemberRepoId() {
        return memberRepoId;
    }

    public void setMemberRepoId(UUID memberRepoId) {
        this.memberRepoId = memberRepoId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GroupMemberId that = (GroupMemberId) o;
        return Objects.equals(groupRepoId, that.groupRepoId)
                && Objects.equals(memberRepoId, that.memberRepoId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupRepoId, memberRepoId);
    }
}
