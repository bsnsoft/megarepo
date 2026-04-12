package de.bsnsoft.megarepo.database.repository;

import de.bsnsoft.megarepo.database.entity.GroupMemberEntity;
import de.bsnsoft.megarepo.database.entity.GroupMemberId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface GroupMemberJpaRepository extends JpaRepository<GroupMemberEntity, GroupMemberId> {

    List<GroupMemberEntity> findByGroupRepoIdOrderBySortOrder(UUID groupRepoId);
}
