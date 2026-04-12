package de.bsnsoft.megarepo.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "group_members")
@IdClass(GroupMemberId.class)
public class GroupMemberEntity {

    @Id
    @Column(name = "group_repo_id", nullable = false)
    private UUID groupRepoId;

    @Id
    @Column(name = "member_repo_id", nullable = false)
    private UUID memberRepoId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    public GroupMemberEntity() {
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

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
